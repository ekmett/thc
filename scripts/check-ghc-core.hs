-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Run with: runghc -f "$GHC" --ghc-arg=-package --ghc-arg=ghc
--                  --ghc-arg=-package --ghc-arg=Cabal scripts/check-ghc-core.hs check
-- This reads the installed interface payload, rather than testing whether GHC
-- merely accepts -fwrite-if-simplified-core.
module Main (main) where

import Control.Exception (SomeException, displayException, try)
import Control.Monad (filterM, forM, forM_, unless, when)
import Control.Monad.IO.Class (liftIO)
import Data.List (intercalate, isSuffixOf, nub, sort)
import Data.Maybe (isNothing)
import qualified Data.ByteString.Char8 as ByteString
import qualified Data.Set as Set
import Distribution.Backpack (OpenModule (OpenModule), OpenUnitId (DefiniteUnitId))
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo)
import Distribution.Types.ExposedModule (ExposedModule (exposedName, exposedReexport))
import Distribution.Types.InstalledPackageInfo (InstalledPackageInfo, depends, exposedModules, hiddenModules, importDirs, installedUnitId)
import Distribution.Pretty (prettyShow)
import GHC (getSession, getSessionDynFlags, runGhc, setSessionDynFlags)
import GHC.Driver.Env (hsc_unit_env)
import GHC.Driver.Session (DynFlags (packageDBFlags, packageEnv), PackageDBFlag (ClearPackageDBs, PackageDB), PkgDbRef (PkgDbPath), targetProfile)
import GHC.Iface.Binary (CheckHiWay (IgnoreHiWay), TraceBinIFace (QuietBinIFace), readBinIface)
import GHC.Types.Name.Cache (newNameCache)
import GHC.Unit.Module (moduleName, moduleUnitId)
import GHC.Unit.Module.ModIface (mi_module, mi_simplified_core)
import GHC.Unit.Env (ue_homeUnitState)
import GHC.Unit.State (unwireUnit)
import GHC.Unit.Types (Definite (Definite), GenUnit (RealUnit), toUnitId, unitIdString)
import Language.Haskell.Syntax.Module.Name (moduleNameString)
import System.Directory (canonicalizePath, doesDirectoryExist, doesFileExist, findExecutable, listDirectory)
import System.Environment (getArgs, lookupEnv)
import System.Exit (ExitCode (ExitSuccess), exitFailure)
import System.FilePath (dropExtension, makeRelative, takeDirectory, (</>))
import System.IO (hPutStrLn, stderr)
import System.Process (readProcessWithExitCode)

data Mode = Check | Advisory | Probe FilePath

-- An installed unit is not necessarily the owner written into its interfaces:
-- GHC rewrites wired-in units to stable names. Resolve those through UnitState,
-- never by stripping version/hash suffixes from arbitrary package IDs.
data Interface = Interface (Maybe (String, String)) FilePath

main :: IO ()
main = do
  args <- getArgs
  (mode, packages) <- case args of
    "check" : names -> pure (Check, defaults names)
    "advisory" : names -> pure (Advisory, defaults names)
    ["probe-interface", path] -> pure (Probe path, [])
    _ -> die "usage: check-ghc-core.hs (check [PACKAGE ...] | advisory [PACKAGE ...] | probe-interface PATH)"
  selected <- maybe "ghc" id <$> lookupEnv "GHC"
  ghc <- resolveExecutable selected
  libdir <- oneLine ghc ["--print-libdir"]
  ghcDb <- canonicalizePath =<< oneLine ghc ["--print-global-package-db"]
  case mode of
    Probe path -> do
      inspected <- inspect libdir ghcDb [Interface Nothing path]
      case inspected of
        [Right ()] -> putStrLn "interface contains simplified Core"
        [Left reason] -> die reason
        _ -> die "internal probe result mismatch"
    _ -> do
      pkg <- selectPackageManager ghc
      pkgListing <- oneLine pkg ["--global", "--no-user-package-db", "list"]
      pkgDbName <- case lines pkgListing of
        firstLine : _ -> pure firstLine
        [] -> die "selected ghc-pkg did not report a global package database"
      pkgDb <- canonicalizePath pkgDbName
      unless (ghcDb == pkgDb) $
        die ("selected ghc and ghc-pkg use different global package databases: " ++ ghcDb ++ " / " ++ pkgDb)
      roots <- forM packages $ \name -> do
        packageId <- oneLine pkg ["--global", "--no-user-package-db", "field", name, "id", "--simple-output"]
        unless (length (words packageId) == 1) $
          die ("selected ghc-pkg does not identify exactly one installed " ++ name)
        pure packageId
      infos <- packageClosure pkg Set.empty roots
      paths <- concat <$> mapM packageInterfaces infos
      validateReexports infos paths
      inspected <- inspect libdir ghcDb paths
      let failures = [path ++ ": " ++ reason | (Interface _ path, Left reason) <- zip paths inspected]
          scope = intercalate ", " packages ++ " (including package dependencies)"
      case (mode, failures) of
        (Check, []) -> putStrLn (scope ++ ": complete Core present in " ++ show (length paths) ++ " installed interfaces")
        (Check, _) -> reportFailure scope failures
        (Advisory, []) -> putStrLn pkg
        (Advisory, _) -> do
          hPutStrLn stderr ("warning: " ++ scope ++ ": complete Core is unavailable in " ++ show (length failures) ++
                            " of " ++ show (length paths) ++ " installed interfaces; see docs/ghc-core.md")
          mapM_ (hPutStrLn stderr . ("  " ++)) (take 3 failures)
          putStrLn pkg

defaults :: [String] -> [String]
defaults [] = ["ghc-internal", "base"]
defaults names = nub names

validateReexports :: [InstalledPackageInfo] -> [Interface] -> IO ()
validateReexports infos paths = do
  let concrete = Set.fromList [owner | Interface (Just owner) _ <- paths]
  forM_ infos $ \info -> forM_ (exposedModules info) $ \entry ->
    case exposedReexport entry of
      Nothing -> pure ()
      Just (OpenModule (DefiniteUnitId unit) name)
        | Set.member (prettyShow unit, prettyShow name) concrete -> pure ()
      Just provider -> die (prettyShow (installedUnitId info) ++
        " has no concrete interface in its dependency closure for reexport " ++ prettyShow provider)

packageClosure :: FilePath -> Set.Set String -> [String] -> IO [InstalledPackageInfo]
packageClosure _ _ [] = pure []
packageClosure pkg seen (unit : rest)
  | Set.member unit seen = packageClosure pkg seen rest
  | otherwise = do
      description <- oneLine pkg ["--global", "--no-user-package-db", "--expand-pkgroot", "--ipid", "describe", unit]
      info <- case parseInstalledPackageInfo (ByteString.pack description) of
        Left errors -> die ("cannot parse installed " ++ unit ++ " metadata: " ++ show errors)
        Right (_, value) -> pure value
      unless (prettyShow (installedUnitId info) == unit) $
        die ("package description does not match selected unit " ++ unit)
      others <- packageClosure pkg (Set.insert unit seen) (map prettyShow (depends info) ++ rest)
      pure (info : others)

packageInterfaces :: InstalledPackageInfo -> IO [Interface]
packageInterfaces info
  -- Native-only registrations have no Haskell bodies; pure reexports are
  -- checked in their provider's dependency registration, not this facade.
  | null localModules = pure []
  | otherwise = do
      let unit = prettyShow (installedUnitId info)
      installedDirs <- mapM canonicalizePath (importDirs info)
      unless (not (null installedDirs) && length installedDirs == length (nub installedDirs)) $
        die (unit ++ " must have distinct nonempty import directories")
      paths <- concat <$> mapM interfacePaths installedDirs
      let missing = Set.fromList localModules `Set.difference` Set.fromList (map fst paths)
      unless (Set.null missing) $
        die (unit ++ " lacks installed interfaces for " ++ intercalate ", " (Set.toList missing))
      pure [Interface (Just (unit, name)) path | (name, path) <- paths]
  where
    localModules = map prettyShow (hiddenModules info) ++
      [prettyShow (exposedName entry) | entry <- exposedModules info, isNothing (exposedReexport entry)]

reportFailure :: String -> [String] -> IO a
reportFailure scope failures = do
  hPutStrLn stderr (scope ++ ": complete Core is missing or invalid in " ++ show (length failures) ++
                    " installed interfaces; see docs/ghc-core.md")
  mapM_ (hPutStrLn stderr . ("  " ++)) (take 8 failures)
  exitFailure

inspect :: FilePath -> FilePath -> [Interface] -> IO [Either String ()]
inspect libdir ghcDb paths = runGhc (Just libdir) $ do
  flags <- getSessionDynFlags
  -- GHC stores DB flags in reverse application order. Pin this session to the
  -- selected global DB even when GHC_PACKAGE_PATH or a user DB exists.
  _ <- setSessionDynFlags flags { packageDBFlags = [PackageDB (PkgDbPath ghcDb), ClearPackageDBs], packageEnv = Nothing }
  units <- ue_homeUnitState . hsc_unit_env <$> getSession
  liftIO $ do
    cache <- newNameCache
    forM paths $ \(Interface expected path) -> do
      result <- try (readBinIface (targetProfile flags) cache IgnoreHiWay QuietBinIFace path)
      pure $ case result of
        Left (errorValue :: SomeException) -> Left (displayException errorValue)
        Right iface
          | Just (unit, _) <- expected
          , let actual = unitIdString (toUnitId (unwireUnit units (RealUnit (Definite (moduleUnitId (mi_module iface))))))
          , actual /= unit -> Left ("interface belongs to another unit: " ++ actual ++ "; expected " ++ unit)
          | Just (_, name) <- expected, moduleNameString (moduleName (mi_module iface)) /= name ->
              Left "interface module does not match its installed path"
          | otherwise -> case mi_simplified_core iface of
              Nothing -> Left "mi_simplified_core is absent"
              Just _ -> Right ()

interfacePaths :: FilePath -> IO [(String, FilePath)]
interfacePaths root = do
  exists <- doesDirectoryExist root
  unless exists $ die ("import directory does not exist: " ++ root)
  files <- walk Set.empty root
  let concrete = sort [path | path <- files, ".hi" `isSuffixOf` path || ".dyn_hi" `isSuffixOf` path]
  forM concrete $ \path -> do
    real <- canonicalizePath path
    let relative = makeRelative root path
    let modulePath = if ".dyn_hi" `isSuffixOf` relative
                     then take (length relative - length ".dyn_hi") relative
                     else dropExtension relative
        moduleId = map (\c -> if c == '/' || c == '\\' then '.' else c) modulePath
    pure (moduleId, real)

walk :: Set.Set FilePath -> FilePath -> IO [FilePath]
walk visited root = do
  real <- canonicalizePath root
  if Set.member real visited then pure [] else do
    children <- listDirectory root
    let seen = Set.insert real visited
    concat <$> forM children (\child -> do
      let path = root </> child
      directory <- doesDirectoryExist path
      if directory then walk seen path else pure [path])

selectPackageManager :: FilePath -> IO FilePath
selectPackageManager ghc = do
  explicit <- lookupEnv "GHC_PKG"
  case explicit of
    Just value | not (null value) -> resolveExecutable value
    _ -> do
      version <- oneLine ghc ["--numeric-version"]
      let directory = takeDirectory ghc
          candidates = [directory </> ("ghc-pkg-" ++ version), directory </> "ghc-pkg"]
      available <- filterM doesFileExist candidates
      case available of
        sibling : _ -> canonicalizePath sibling
        [] -> die ("no matching ghc-pkg beside selected GHC: " ++ ghc ++ "; set GHC_PKG explicitly")

resolveExecutable :: FilePath -> IO FilePath
resolveExecutable name = do
  found <- findExecutable name
  case found of
    Nothing -> die ("executable not found: " ++ name)
    Just path -> canonicalizePath path

oneLine :: FilePath -> [String] -> IO String
oneLine command args = do
  (status, output, err) <- readProcessWithExitCode command args ""
  unless (status == ExitSuccess) $
    die (command ++ " failed: " ++ take 300 err)
  let value = reverse (dropWhile (`elem` "\r\n") (reverse output))
  when (null value) $ die (command ++ " returned empty output")
  pure value

die :: String -> IO a
die message = hPutStrLn stderr ("check-ghc-core: " ++ message) >> exitFailure
