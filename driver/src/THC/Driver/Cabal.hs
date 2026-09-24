module THC.Driver.Cabal (PlanOptions(..), defaultPlanOptions, configurePackage, planPackage) where

import Control.Monad (filterM, unless, when)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (sort)
import Distribution.Compiler (CompilerFlavor(GHC))
import Distribution.Package (packageId)
import Distribution.PackageDescription
import Distribution.PackageDescription.Parsec (parseGenericPackageDescription, runParseResult)
import Distribution.Parsec (showPError, showPWarning)
import Distribution.Pretty (prettyShow)
import Distribution.Simple.BuildPaths
import Distribution.Simple.Compiler (PackageDBX(GlobalPackageDB), compilerId)
import Distribution.Simple.Configure (configure)
import Distribution.Simple.LocalBuildInfo hiding (packageRoot)
import Distribution.Simple.Program (defaultProgramDb)
import Distribution.Simple.Setup
import Distribution.Simple.Utils (cabalVersion)
import Distribution.Utils.Path (SymbolicPathX, getSymbolicPath, makeSymbolicPath)
import Distribution.Verbosity (silent)
import System.Directory (canonicalizePath, doesDirectoryExist, doesFileExist, listDirectory, makeAbsolute)
import System.FilePath
import System.IO (hPutStrLn, stderr)
import THC.Driver.Json

data PlanOptions = PlanOptions
  { distDirectory :: FilePath
  , ghcPath :: Maybe FilePath
  , ghcPkgPath :: Maybe FilePath
  , selectedFlags :: [(FlagName, Bool)]
  , enableTests :: Bool
  , enableBenchmarks :: Bool
  }

defaultPlanOptions :: PlanOptions
defaultPlanOptions = PlanOptions "dist-thc" Nothing Nothing [] False False

-- Cabal's configure action resolves against the selected compiler's installed
-- global package database. It is not cabal-install's project dependency solver.
configurePackage :: PlanOptions -> FilePath -> IO (FilePath, LocalBuildInfo)
configurePackage opts target = do
  cabalFile <- discoverPackage target
  let packageRoot = takeDirectory cabalFile
  bytes <- BS.readFile cabalFile
  let (warnings, result) = runParseResult (parseGenericPackageDescription bytes)
  mapM_ (hPutStrLn stderr . showPWarning cabalFile) warnings
  generic <- case result of
    Left (_, errors) -> fail (unlines (map (showPError cabalFile) (toList errors)))
    Right parsed -> pure parsed
  unless (buildType (packageDescription generic) == Simple) $
    fail "only build-type: Simple is supported; custom Setup/configure/hooks are not executed"
  let knownFlags = map flagName (genPackageFlags generic)
  mapM_ (\(name, _) -> unless (name `elem` knownFlags) $
    fail ("unknown package flag: " ++ prettyShow name)) (selectedFlags opts)
  let defaults = defaultConfigFlags defaultProgramDb
      common = (configCommonFlags defaults)
        { setupVerbosity = Flag silent
        , setupWorkingDir = Flag (makeSymbolicPath packageRoot)
        , setupDistPref = Flag (makeSymbolicPath (distDirectory opts))
        , setupCabalFilePath = Flag (makeSymbolicPath (takeFileName cabalFile))
        }
      flags = defaults
        { configCommonFlags = common
        , configHcFlavor = Flag GHC
        , configHcPath = maybe NoFlag Flag (ghcPath opts)
        , configHcPkg = maybe NoFlag Flag (ghcPkgPath opts)
        , configPackageDBs = [Nothing, Just GlobalPackageDB]
        , configUserInstall = Flag False
        , configConfigurationsFlags = mkFlagAssignment (selectedFlags opts)
        , configTests = Flag (enableTests opts)
        , configBenchmarks = Flag (enableBenchmarks opts)
        , configVanillaLib = Flag True
        , configSharedLib = Flag False
        , configGHCiLib = Flag False
        , configProf = Flag False
        }
  lbi <- configure (generic, emptyHookedBuildInfo) flags
  let configuredPackage = localPkgDescr lbi
      components = allComponentsInBuildOrder lbi
  mapM_ (checkComponent . getComponent configuredPackage . componentLocalName) components
  pure (cabalFile, lbi)

planPackage :: PlanOptions -> FilePath -> IO Json
planPackage opts target = do
  (cabalFile, lbi) <- configurePackage opts target
  let packageRoot = takeDirectory cabalFile
      configuredPackage = localPkgDescr lbi
      components = allComponentsInBuildOrder lbi
  pure $ Object
    [ ("schema", String "thc.cabal-package-plan.v1")
    , ("stage", String "cabal-installed-package-configuration")
    , ("solvedProjectPlan", Boolean False)
    , ("artifactsBuilt", Boolean False)
    , ("cabalVersion", String (prettyShow cabalVersion))
    , ("cabalFile", String cabalFile)
    , ("packageRoot", String packageRoot)
    , ("package", String (prettyShow (packageId configuredPackage)))
    , ("compiler", String (prettyShow (compilerId (compiler lbi))))
    , ("platform", String (prettyShow (hostPlatform lbi)))
    , ("packageDatabases", strings ["global"])
    , ("setupConfig", String (distDirectory opts </> "setup-config"))
    , ("flags", Object [(prettyShow n, Boolean v) | (n, v) <- unFlagAssignment (flagAssignment lbi)])
    , ("autogenPackageDirectory", path (autogenPackageModulesDir lbi))
    , ("components", Array (map (componentPlan lbi) components))
    ]

-- An explicit .cabal path deliberately selects a package independent of any
-- surrounding project. Directory mode refuses project files rather than silently
-- discarding constraints, imports, flags, repositories or multiple packages.
discoverPackage :: FilePath -> IO FilePath
discoverPackage target = do
  absolute <- makeAbsolute target
  isDirectory <- doesDirectoryExist absolute
  if isDirectory then do
    canonical <- canonicalizePath absolute
    rejectProject canonical
    entries <- sort . filter ((== ".cabal") . takeExtension) <$> listDirectory canonical
    files <- filterM (doesFileExist . (canonical </>)) entries
    case files of
      [file] -> canonicalizePath (canonical </> file)
      [] -> fail ("no .cabal package found in " ++ canonical)
      _ -> fail "multiple .cabal files; pass one explicit .cabal path"
  else do
    unless (takeExtension absolute == ".cabal") $
      fail "expected a package directory or an explicit .cabal file; cabal.project solving is not supported"
    exists <- doesFileExist absolute
    unless exists (fail ("package file does not exist: " ++ absolute))
    canonicalizePath absolute
  where
    rejectProject directory = do
      projects <- mapM (doesFileExist . (directory </>)) ["cabal.project", "cabal.project.local", "cabal.project.freeze"]
      when (or projects) $
        fail "cabal.project configuration is not supported; pass an explicit .cabal file for independent package configuration"
      let parent = takeDirectory directory
      unless (parent == directory) (rejectProject parent)

checkComponent :: Component -> IO ()
checkComponent (CFLib _) = fail "foreign-library components are not yet supported"
checkComponent (CLib lib) = unless (null (signatures lib) && null (reexportedModules lib)) $
  fail "Backpack signatures and module reexports are not yet supported"
checkComponent (CTest test) = case testInterface test of
  TestSuiteExeV10 _ _ -> pure ()
  _ -> fail "only exitcode-stdio-1.0 test suites are supported"
checkComponent (CBench bench) = case benchmarkInterface bench of
  BenchmarkExeV10 _ _ -> pure ()
  _ -> fail "only exitcode-stdio-1.0 benchmarks are supported"
checkComponent (CExe _) = pure ()

componentPlan :: LocalBuildInfo -> ComponentLocalBuildInfo -> Json
componentPlan lbi clbi = Object
  [ ("name", String (prettyShow (componentLocalName clbi)))
  , ("componentId", String (prettyShow (componentComponentId clbi)))
  , ("unitId", String (prettyShow (componentUnitId clbi)))
  , ("dependencies", Array [Object [("unitId", String (prettyShow unit)), ("package", String (prettyShow pkg))] | (unit, pkg) <- componentPackageDeps clbi])
  , ("internalDependencies", strings (map prettyShow (componentInternalDeps clbi)))
  , ("toolDependencies", strings (map prettyShow (componentExeDeps clbi)))
  , ("declaredDependencies", strings (map prettyShow (targetBuildDepends info)))
  , ("sourceDirectories", Array (map path (hsSourceDirs info)))
  , ("exposedModules", strings (case component of CLib lib -> map prettyShow (exposedModules lib); _ -> []))
  , ("otherModules", strings (map prettyShow (otherModules info)))
  , ("autogenModules", strings (map prettyShow (autogenModules info)))
  , ("virtualModules", strings (map prettyShow (virtualModules info)))
  , ("mainSource", maybe Null String mainSource)
  , ("defaultLanguage", maybe Null (String . prettyShow) (defaultLanguage info))
  , ("defaultExtensions", strings (map prettyShow (defaultExtensions info)))
  , ("cppOptions", strings (cppOptions info))
  , ("ghcOptions", strings (hcOptions GHC info))
  , ("buildDirectory", path (componentBuildDir lbi clbi))
  , ("objectDirectory", String objectDirectory)
  , ("autogenDirectory", path (autogenComponentModulesDir lbi clbi))
  , ("plannedArtifact", String artifact)
  ]
  where
    component = getComponent (localPkgDescr lbi) (componentLocalName clbi)
    info = componentBuildInfo component
    componentDir = getSymbolicPath (componentBuildDir lbi clbi)
    (objectDirectory, mainSource, artifact) = case component of
      CLib _ -> (componentDir, Nothing, componentDir </> mkLibName (componentUnitId clbi))
      CExe exe -> (getSymbolicPath (exeBuildDir lbi exe), Just (getSymbolicPath (modulePath exe)), executable (prettyShow (exeName exe)))
      CTest test -> (getSymbolicPath (testBuildDir lbi test), testMain test, executable (prettyShow (testName test)))
      CBench bench -> (getSymbolicPath (benchmarkBuildDir lbi bench), benchMain bench, executable (prettyShow (benchmarkName bench)))
      CFLib _ -> error "foreign libraries are rejected before rendering"
    executable name = componentDir </> name <.> exeExtension (hostPlatform lbi)
    testMain test = case testInterface test of TestSuiteExeV10 _ file -> Just (getSymbolicPath file); _ -> Nothing
    benchMain bench = case benchmarkInterface bench of BenchmarkExeV10 _ file -> Just (getSymbolicPath file); _ -> Nothing

path :: SymbolicPathX allowAbsolute from to -> Json
path = String . getSymbolicPath

strings :: [String] -> Json
strings = Array . map String
