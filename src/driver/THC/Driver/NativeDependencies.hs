-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.NativeDependencies
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Cabal registrations and native ar
--
-- Select captured C/C++ products from the resolved Cabal registration and
-- exact native archive membership, never from an unresolved symbol spelling.
module THC.Driver.NativeDependencies
  ( NativeProduct, nativeProductProof, nativeProductPieces, readNativeProduct, readNativeProductAvailable
  , NativePieceSelection(..), selectNativePiecesAvailable, selectNativePieces, nativeLinkInputs, nativeSymbolArchives, nativeSymbolArchivesWithProduct, configuredNativeArchive
  , nativeWindowsRtsInputs, configuredSourceBuild
  ) where

import Control.Exception (evaluate)
import Control.Monad (filterM, forM, unless, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', fromJSON, Result(..), object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import Data.Char (isAscii, isAlphaNum)
import Data.List (isPrefixOf, nub, sort, stripPrefix)
import qualified Data.Text.Encoding as T
import qualified Data.Text
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo)
import qualified Distribution.Types.InstalledPackageInfo as Package
import Distribution.Pretty (prettyShow)
import Distribution.Package (pkgName)
import Distribution.PackageDescription (libBuildInfo, cSources, cxxSources)
import qualified Distribution.PackageDescription as Cabal
import Distribution.Simple.Configure (getPersistBuildConfig)
import Distribution.Simple.GHC (componentCcGhcOptions)
import Distribution.Simple.Program (lookupProgram, programPath, ghcProgram)
import Distribution.Simple.Program.GHC (renderGhcOptions, GhcOptions(..), GhcDynLinkMode(..))
import Distribution.Simple.Setup (toFlag)
import qualified Distribution.Simple.Compiler as Compiler
import qualified Distribution.Simple.LocalBuildInfo as Local
import Distribution.Simple.LocalBuildInfo (localPkgDescr, buildDir, allComponentsInBuildOrder,
  componentPackageDeps, componentUnitId)
import Distribution.Utils.Path (getSymbolicPath, makeSymbolicPath)
import Distribution.Verbosity (normal)
import Numeric (showHex)
import System.Directory (doesFileExist, doesDirectoryExist, listDirectory, canonicalizePath,
  createDirectoryIfMissing, removeFile, renameFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory, takeFileName, isAbsolute, takeExtension,
  replaceExtension, splitDirectories)
import System.IO (hClose, openTempFile)
import System.Process (CreateProcess(..), StdStream(..), proc, readProcessWithExitCode,
  waitForProcess, withCreateProcess)
import THC.Driver.Installed (emptyRegistration, boundedInterfaceProcessIn)
import THC.Driver.ScalarBitcode (readDependencies)
import THC.Driver.NativeLibrarySources (nativeLinkOptions, nativePackageOptions,
  nativePackageSelectors, packageNativeLibraries)

-- Capture external native libraries from the actual selected registration
-- closure. Haskell archives (and therefore the native GHC RTS) are not inputs:
-- those bodies execute as Core, while captured C objects execute in Sulong.
-- The caller supplies its selected package tool (including an installed view's
-- wrapper), not a companion inferred from the compiler executable's directory.
nativeLinkInputs :: FilePath -> FilePath -> FilePath -> [FilePath] -> Maybe String -> [String] -> IO [String]
nativeLinkInputs ghcPkg libdir root publishedDatabases owner arguments = do
  let absolute path = if isAbsolute path then path else root </> path
      database option = case stripPrefix "--package-db=" option of
        Nothing -> pure [option]
        Just path -> do
          let selected = absolute path
          exists <- doesDirectoryExist selected
          -- Cabal removes temporary intra-package databases after installing
          -- their units. Publication supplies that build's surviving databases;
          -- retain every live entry and replace the removed entry in place.
          -- Ordinary local/installed callers still require their original stack.
          pure (map ("--package-db=" ++)
            (if exists || null publishedDatabases then [selected] else publishedDatabases))
      -- Cabal may already have removed its unpack directory. Resolve search
      -- paths against that original cwd, without requiring it for absolute
      -- inputs or changing detached -optl argument boundaries.
      linkPaths (flag:path:rest) | flag `elem` ["-L","-F"] = flag : absolute path : linkPaths rest
      linkPaths (('-':kind:path):rest) | kind `elem` ['L','F'], not (null path) =
        (['-',kind] ++ absolute path) : linkPaths rest
      linkPaths (flag:rest) = flag : linkPaths rest
      linkPaths [] = []
  selectedDatabases <- concat <$> mapM database (nativePackageOptions arguments)
  let databaseOptions = ["--global-package-db=" ++ libdir </> "package.conf.d"] ++ selectedDatabases
      visit seen [] = pure (seen,[])
      visit seen (selected@(unitId,name):rest)
        | selected `elem` seen = visit seen rest
        | otherwise = do
            (status,registration,diagnostic) <- readProcessWithExitCode ghcPkg
              (databaseOptions ++ ["--ipid" | unitId] ++ ["describe",name,"--no-expand-pkgroot"]) ""
            check (status == ExitSuccess) ("Cannot read native link dependency: " ++ diagnostic)
            (_,info) <- either (fail . show) pure
              (parseInstalledPackageInfo (T.encodeUtf8 (Data.Text.pack registration)))
            let identity = prettyShow (Package.installedUnitId info)
            (visited,dependencies) <- visit (selected:(True,identity):seen)
              [(True,prettyShow dependency) | dependency <- Package.depends info]
            (finished,following) <- visit visited rest
            pure (finished,dependencies ++ [info] ++ following)
  -- A library's own extra-libraries live in its registration, not necessarily
  -- its compile-only invocation. Executables have no registration. Query this
  -- after Cabal finishes, selecting exact units from the surviving DB stack.
  registered <- case owner of
    Nothing -> pure []
    Just unit -> do
      (status,_,_) <- readProcessWithExitCode ghcPkg (databaseOptions ++ ["--ipid","describe",unit]) ""
      pure [(True,unit) | status == ExitSuccess]
  (_,dependencies) <- visit [] (registered ++ nativePackageSelectors arguments)
  -- Reverse postorder puts every dependent before its dependency, even when
  -- both were explicitly selected. This also preserves static archive lookup.
  let packagePath info path
        | Just suffix <- stripPrefix "${pkgroot}" path, Just pkgRoot <- Package.pkgRoot info = pkgRoot ++ suffix
        | Just suffix <- stripPrefix "$topdir" path = libdir ++ suffix
        | otherwise = path
      libraries info = packageNativeLibraries
        (nub (map (packagePath info) (Package.libraryDirs info ++ Package.libraryDirsStatic info ++ Package.libraryDynDirs info)))
        (if "-static" `elem` arguments && not (null (Package.extraLibrariesStatic info))
          then Package.extraLibrariesStatic info else Package.extraLibraries info)
        (Package.ldOptions info ++ map (("-F" ++) . packagePath info) (Package.frameworkDirs info) ++
          concatMap (\framework -> ["-framework",framework]) (Package.frameworks info))
  pure (linkPaths (nativeLinkOptions arguments) ++ concatMap libraries (reverse dependencies))

-- | Record the selected compiler's vanilla RTS inputs for the private Windows
-- C dependency link. GHC supplies their ordering and transitive system libraries.
-- These are not Core providers: the companion exports only the original package
-- roots, never native Haskell, scheduler or callback entry points.
nativeWindowsRtsInputs :: FilePath -> FilePath -> IO (String, [Value])
nativeWindowsRtsInputs ghcPkg libdir = do
  (status,registration,diagnostic) <- readProcessWithExitCode ghcPkg
    ["--global-package-db=" ++ libdir </> "package.conf.d","--no-user-package-db",
     "describe","rts","--no-expand-pkgroot"] ""
  check (status == ExitSuccess) ("Cannot read Windows C dependency RTS registration: " ++ diagnostic)
  (_,info) <- either (fail . show) pure
    (parseInstalledPackageInfo (T.encodeUtf8 (Data.Text.pack registration)))
  check (prettyShow (pkgName (Package.sourcePackageId info)) == "rts")
    "Windows C dependency registration is not the selected RTS"
  let expand path
        | Just suffix <- stripPrefix "${pkgroot}" path, Just pkgRoot <- Package.pkgRoot info = pkgRoot ++ suffix
        | otherwise = path
  archives <- forM (Package.hsLibraries info) $ \library -> do
    candidates <- filterM doesFileExist
      [expand directory </> "lib" ++ library ++ ".a" | directory <- Package.libraryDirsStatic info]
    actual <- nub <$> mapM canonicalizePath candidates
    case actual of
      [path] -> do
        hash <- digest <$> BS.readFile path
        pure (object ["path" .= path,"sha256" .= hash])
      _ -> fail ("Windows C dependency has no unique registered vanilla archive: " ++ library)
  check (not (null archives)) "Windows C dependency RTS has no registered archives"
  pure (prettyShow (Package.installedUnitId info), archives)

-- Native call and address roots can live in a package's ordinary C archive.
-- Select the actual declaring registration, never an inlining consumer or the
-- RTS. The native linker extracts only the rooted archive members; no whole
-- Haskell component is loaded alongside its Core implementation.
nativeSymbolArchives :: FilePath -> FilePath -> FilePath -> String -> [String] -> [(String,Bool)] -> IO [(FilePath,[(String,Bool)])]
nativeSymbolArchives ghcPkg libdir root owner arguments symbols =
  nativeSymbolArchivesWithProduct ghcPkg libdir root owner arguments Nothing symbols

-- A complete configured C product already supplies the declaring unit's C
-- inventory. Its mixed installed archive also contains native Haskell bodies;
-- those cannot supply managed closures or foreign-export ownership.
nativeSymbolArchivesWithProduct :: FilePath -> FilePath -> FilePath -> String -> [String] ->
  Maybe NativeProduct -> [(String,Bool)] -> IO [(FilePath,[(String,Bool)])]
nativeSymbolArchivesWithProduct ghcPkg libdir root owner arguments capturedProduct symbols
  | null symbols = pure []
  | otherwise = do
      let absolute path = if isAbsolute path then path else root </> path
          database option = if "--package-db=" `isPrefixOf` option
            then "--package-db=" ++ absolute (drop 13 option) else option
          options = ["--global-package-db=" ++ libdir </> "package.conf.d"] ++ map database (nativePackageOptions arguments)
      capturedUnit <- case capturedProduct of
        Nothing -> pure Nothing
        Just captured -> do
          check (member (nativeProductProof captured) "unit" == Just (String (Data.Text.pack owner)))
            "configured native archive owner differs"
          identity <- get (nativeProductProof captured) "sourceIdentity"
          fmap Just (get identity "id" :: IO String)
      registrations <- fmap concat $ forM (nub ((True,owner):nativePackageSelectors arguments)) $ \(unitId,name) -> do
        (status,registration,_) <- readProcessWithExitCode ghcPkg (options ++ ["--ipid" | unitId] ++ ["describe",name,"--no-expand-pkgroot"]) ""
        if status /= ExitSuccess then pure [] else do
          (_,info) <- either (fail . show) pure (parseInstalledPackageInfo (T.encodeUtf8 (Data.Text.pack registration)))
          let package = prettyShow (pkgName (Package.sourcePackageId info))
          -- This compiler's three C products (cutils, genSym and
          -- keepCAFsForGHCi) operate exclusively on native RTS/process state.
          -- In particular keepCAFsForGHCi has a native constructor: extracting
          -- its member would mutate a foreign RTS before any managed override
          -- could run. Preserve the original calls/ABI, like other RTS calls;
          -- context-owned services dispatch in the runtime, while unsupported
          -- services remain unresolved rather than acquiring native state.
          pure [info | Just (prettyShow (Package.installedUnitId info)) /= capturedUnit, package /= "rts",
            prettyShow (Package.installedUnitId info) /= "ghc-9.14.1-inplace",
            prettyShow (Package.installedUnitId info) == owner || package == owner]
      nm <- maybe "llvm-nm" id <$> lookupEnv "THC_LLVM_NM"
      registered <- fmap concat $ forM registrations $ \info -> do
        let packagePath path
              | Just suffix <- stripPrefix "${pkgroot}" path, Just pkgRoot <- Package.pkgRoot info = pkgRoot ++ suffix
              | Just suffix <- stripPrefix "$topdir" path = libdir ++ suffix
              | otherwise = path
        filterM doesFileExist [packagePath directory </> "lib" ++ library ++ ".a" |
          directory <- nub (Package.libraryDirsStatic info ++ Package.libraryDirs info), library <- Package.hsLibraries info]
      -- An explicitly supplied native archive (including a configured C-only
      -- PIC product) precedes the Haskell archive fallback. Do not select a
      -- non-PIC vanilla member for a root already supplied by that input.
      explicit <- filterM doesFileExist [absolute path | path <- nativeLinkOptions arguments,
        takeExtension path == ".a", not ("-" `isPrefixOf` path)]
      archives <- nub <$> mapM canonicalizePath (explicit ++ registered)
      let select _ [] = pure []
          select pending (archive:rest) = do
            (status,output,diagnostic) <- readProcessWithExitCode nm ["--defined-only","--extern-only","--format=posix",archive] ""
            check (status == ExitSuccess) ("Cannot inspect registered native archive: " ++ diagnostic)
            let defined = nub [(symbol,function) | line <- lines output, name:kind:_ <- [words line],
                  (symbol,function) <- pending,
                  kind `elem` (if function then ["T","W"] else ["B","C","D","R","S","V"]),
                  name == symbol || name == '_' : symbol]
            following <- select (filter (`notElem` defined) pending) rest
            pure ([(archive,defined) | not (null defined)] ++ following)
      select symbols archives

-- | Resolve retained source configuration for this exact registered unit.
-- An explicit Cabal owner survives a copied acquisition view; other units
-- retain their own import-directory or Hadrian configuration.
configuredSourceBuild :: FilePath -> Package.InstalledPackageInfo -> IO (Maybe (FilePath, Bool))
configuredSourceBuild source info = do
  root <- canonicalizePath source
  let package = prettyShow (pkgName (Package.sourcePackageId info))
      hadrian = root </> "_build/stage1/libraries" </> package
  builtByHadrian <- doesFileExist (hadrian </> "setup-config")
  let direct = root </> "dist"
  directExists <- doesFileExist (direct </> "setup-config")
  owned <- if not directExists then pure [] else do
    lbi <- getPersistBuildConfig Nothing (makeSymbolicPath direct)
    component <- case allComponentsInBuildOrder lbi of
      [value] -> pure value
      _ -> fail "configured source owner has multiple components"
    pure [direct | componentUnitId component == Package.installedUnitId info]
  pinned <- filterM (doesFileExist . (</> "setup-config"))
    [takeDirectory (takeDirectory path) </> "dist" | path <- Package.importDirs info,
      takeFileName path == "interfaces", takeFileName (takeDirectory path) == "view"]
  paths <- nub <$> mapM canonicalizePath (if builtByHadrian then [hadrian] else owned ++ pinned)
  case paths of
    [] -> pure Nothing
    [path] -> pure (Just (path, builtByHadrian))
    _ -> fail "configured native provider has multiple source configurations"

-- | Capture Cabal's complete declared C/C++ inventory from the selected source
-- owner. Hadrian retains its dynamic objects; pinned producers compile PIC.
-- Never load mixed Haskell shared libraries, Cmm or native RTS objects.
-- The caller records these products and inputs through ordinary publication.
configuredNativeArchive :: (FilePath -> FilePath -> [String] -> IO Value) ->
  FilePath -> FilePath -> Value -> String -> String -> IO (Maybe (FilePath,[Value],Maybe NativeProduct))
configuredNativeArchive capture source destination compilerIdentity owner registration = do
  (_,info) <- either (fail . show) pure (parseInstalledPackageInfo (T.encodeUtf8 (Data.Text.pack registration)))
  selected <- configuredSourceBuild source info
  let package = prettyShow (pkgName (Package.sourcePackageId info))
  -- This producing compiler's C inventory is native RTS state: cutils mutates
  -- RtsFlags, genSym's unique cells live in the RTS, and keepCAFsForGHCi has a native
  -- constructor. Apply the same context-ownership boundary as nativeSymbolArchives.
  if package == "rts" || prettyShow (Package.installedUnitId info) == "ghc-9.14.1-inplace"
    then pure Nothing else case selected of
    Nothing -> pure Nothing
    Just (configured, builtByHadrian) -> do
      configuredArchive info configured builtByHadrian
  where
  configuredArchive info configured builtByHadrian = do
    let configuration = configured </> "setup-config"
        built = configured </> "build"
    -- The pinned provider's private view identifies its genuine Cabal
    -- configuration without changing any native registration fields.
    when (not builtByHadrian) $ do
      inputs <- readJson (takeDirectory configured </> "inputs.json")
      check (member inputs "compiler" == Just compilerIdentity)
        "pinned native provider differs from selected compiler identity"
    lbi <- getPersistBuildConfig Nothing (makeSymbolicPath configured)
    selectedCompiler <- get compilerIdentity "id"
    selectedPlatform <- get compilerIdentity "platform"
    check (prettyShow (Compiler.compilerId (Local.compiler lbi)) == selectedCompiler &&
      prettyShow (Local.hostPlatform lbi) == selectedPlatform)
      "configured native provider differs from selected compiler/platform"
    component <- case allComponentsInBuildOrder lbi of
      [value] -> pure value
      _ -> fail "configured native provider has multiple components"
    check (componentUnitId component == Package.installedUnitId info &&
      sort (map fst (componentPackageDeps component)) == sort (Package.depends info))
      "configured native provider differs from installed unit/dependencies"
    actual <- canonicalizePath (getSymbolicPath (buildDir lbi))
    expected <- canonicalizePath built
    check (actual == expected) "configured native provider belongs to another build tree"
    bi <- maybe (fail "configured native provider is not a library") (pure . libBuildInfo)
      (Cabal.library (localPkgDescr lbi))
    let declarations = [(path, False) | path <- cSources bi] ++
          [(path, True) | path <- cxxSources bi]
    if null declarations then pure Nothing else do
      let packagePath path
            | Just suffix <- stripPrefix "${pkgroot}" path, Just pkgRoot <- Package.pkgRoot info = pkgRoot ++ suffix
            | otherwise = path
      archives <- forM (Package.hsLibraries info) $ \name -> do
        matches <- filterM doesFileExist [packagePath directory </> "lib" ++ name ++ ".a" |
          directory <- nub (Package.libraryDirsStatic info ++ Package.libraryDirs info)]
        paths <- nub <$> mapM canonicalizePath matches
        installed <- case paths of
          [path] -> pure path
          _ -> fail "configured native provider lacks a unique installed archive"
        -- Installation may strip/reindex archive members. The configured
        -- component identity selects the products, not byte equivalence of
        -- those containers. Observe the actual selected archive for caching.
        pure installed
      createDirectoryIfMissing True destination
      directory <- canonicalizePath destination
      let pic = directory </> "pic-objects"
          packageDirectory = takeDirectory configured </> "source"
      captured <- forM declarations $ \(sourcePath, cxx) -> do
        let path = getSymbolicPath sourcePath
        check (not (isAbsolute path) && ".." `notElem` splitDirectories path)
          "configured native source is outside its package"
        -- Hadrian's Context.objectPath places nongenerated foreign objects
        -- under their source extension, independently of Haskell objects.
        if builtByHadrian then pure (built </> drop 1 (takeExtension path) </> replaceExtension path "dyn_o", Nothing)
        else do
          ghc <- maybe (fail "pinned native provider has no configured GHC") (pure . programPath)
            (lookupProgram ghcProgram (Local.withPrograms lbi))
          let buildInfo = if cxx then bi {Cabal.ccOptions = Cabal.cxxOptions bi} else bi
              base = componentCcGhcOptions normal lbi buildInfo component (makeSymbolicPath built) sourcePath
              dependency = pic </> replaceExtension path "d"
              dependencyFlags = ["-MD", "-MF", dependency, "-MT", "thc_scalar_input"]
              options = if cxx then base
                {ghcOptCxxOptions = ghcOptCcOptions base ++ dependencyFlags, ghcOptCcOptions = []}
                else base {ghcOptCcOptions = ghcOptCcOptions base ++ dependencyFlags}
              arguments = renderGhcOptions (Local.compiler lbi) (Local.hostPlatform lbi)
                (options {ghcOptFPic = toFlag True, ghcOptDynLinkMode = toFlag GhcDynamicOnly,
                  ghcOptObjDir = toFlag (makeSymbolicPath pic), ghcOptObjSuffix = toFlag "dyn_o",
                  ghcOptOutputFile = toFlag (makeSymbolicPath (pic </> replaceExtension path "dyn_o"))})
          createDirectoryIfMissing True (pic </> takeDirectory path)
          (status,_,diagnostic) <- boundedInterfaceProcessIn packageDirectory ghc arguments
          check (status == ExitSuccess) ("Cannot compile pinned native PIC source: " ++
            Data.Text.unpack (T.decodeUtf8 diagnostic))
          piece <- capture packageDirectory ghc arguments
          pure (pic </> replaceExtension path "dyn_o", Just piece)
      let objects = map fst captured
          pieces = [piece | (_,Just piece) <- captured]
      check (length (nub (map takeFileName objects)) == length objects)
        "configured native PIC archive has duplicate member names"
      present <- filterM doesFileExist objects
      check (builtByHadrian || present == objects) "pinned native PIC compiler did not produce every declared object"
      if present /= objects then pure Nothing else do
        dependencies <- if builtByHadrian then pure [] else fmap concat $ forM objects $ \path -> do
          dependencyPaths <- readDependencies (replaceExtension path "d")
          mapM (canonicalizePath . (packageDirectory </>)) dependencyPaths
        let sources = [packageDirectory </> getSymbolicPath path | (path,_) <- declarations, not builtByHadrian]
            products = zip sources objects
            -- The pinned objects die with installed-native staging. Keep their
            -- digests with durable source/header inputs, not deleted file paths
            -- that would invalidate every subsequent installed-probe cache hit.
            observe = forM (nub (configuration : dependencies ++ sources ++ archives ++
              [path | path <- objects, builtByHadrian])) $ \path -> do
              hash <- digest <$> BS.readFile path
              productHash <- case lookup path products of
                Nothing -> pure []
                Just compiled -> do
                  compiledHash <- digest <$> BS.readFile compiled
                  pure ["objectSha256" .= compiledHash]
              pure (object (["path" .= path,"sha256" .= hash] ++ productHash))
        before <- observe
        let output = directory </> "libthc-configured-cbits.a"
        (temporary,handle) <- openTempFile directory "cbits-"
        hClose handle
        removeFile temporary
        ar <- maybe "ar" id <$> lookupEnv "THC_AR"
        (status,_,diagnostic) <- readProcessWithExitCode ar (["rcs",temporary] ++ objects) ""
        check (status == ExitSuccess) ("Cannot archive configured native PIC objects: " ++ diagnostic)
        after <- observe
        check (before == after) "configured native products changed during archiving"
        renameFile temporary output
        ownedProduct <- if null pieces then pure Nothing else do
          (listed,names,errors) <- readProcessWithExitCode ar ["t",output] ""
          check (listed == ExitSuccess) ("Cannot read configured native archive: " ++ errors)
          check (sort (archiveObjectNames names) == sort (map takeFileName objects))
            "configured native archive differs from declared C/C++ objects"
          members <- forM (archiveObjectNames names) $ \name -> do
            contents <- withCreateProcess (proc ar ["p",output,name]) {std_out=CreatePipe} $ \_ stream _ process -> do
              archiveStream <- maybe (fail "Missing configured archive output pipe") pure stream
              bytes <- BS.hGetContents archiveStream
              _ <- evaluate (BS.length bytes)
              memberStatus <- waitForProcess process
              check (memberStatus == ExitSuccess) "Cannot read configured native archive member"
              pure bytes
            pure (name,digest contents)
          selectedPieces <- either fail pure (selectNativePieces True members pieces)
          check (length selectedPieces == length declarations) "configured native archive lacks captured C/C++ products"
          translationUnits <- forM selectedPieces $ \piece -> do
            path <- get piece "bitcode"
            hash <- digest <$> BS.readFile path
            pure (object ["receipt" .= piece,"bitcodeSha256" .= hash])
          archiveHash <- digest <$> BS.readFile output
          let proof = object ["profile" .= ("resolved-native-archive-products-v1" :: String),
                "unit" .= owner,"sourceIdentity" .= object
                  ["id" .= prettyShow (Package.installedUnitId info),
                   "depends" .= map prettyShow (Package.depends info),
                   "pkg-src" .= object ["type" .= ("local" :: String),"path" .= packageDirectory]],
                "registration" .= registration,"registrationSha256" .= digest (T.encodeUtf8 (Data.Text.pack registration)),
                "archives" .= [object ["path" .= output,"sha256" .= archiveHash,
                  "members" .= [object ["name" .= name,"sha256" .= hash] | (name,hash) <- members]]],
                "translationUnits" .= translationUnits]
          pure (Just (NativeProduct proof selectedPieces))
        pure (Just (output,before,ownedProduct))

-- The constructor stays private: only captured C/C++ products with exact
-- membership in the resolved package archive may become native providers.
data NativeProduct = NativeProduct
  { nativeProductProof :: Value, nativeProductPieces :: [Value] }

-- | Complete captured membership, or required members which have no recorded
-- compiler product. Invalid or ambiguous declared evidence is still an error.
data NativePieceSelection = NativePieceSelection
  { missingNativeMembers :: [FilePath], matchedNativePieces :: [Value] }
  deriving (Eq, Show)

-- | Match captured C/C++ members by content, with the basename as an additional
-- check. Moduleless C-only registrations require complete archive coverage;
-- mixed archives omit uncaptured native Haskell objects. Ambiguity fails closed.
selectNativePieces :: Bool -> [(FilePath, String)] -> [Value] -> Either String [Value]
selectNativePieces complete members pieces = do
  selected <- selectNativePiecesAvailable complete members pieces
  if null (missingNativeMembers selected) then Right (matchedNativePieces selected)
    else Left (missingNativePieces (missingNativeMembers selected))

-- | Distinguish absent required products from invalid evidence for capture
-- reuse. Every member is checked even after an absent product is found, so an
-- unrelated malformed or ambiguous declaration cannot become a cache miss.
selectNativePiecesAvailable :: Bool -> [(FilePath, String)] -> [Value] -> Either String NativePieceSelection
selectNativePiecesAvailable complete members pieces = do
  require (not (null members) && length members == length (nub (map fst members)))
    "native archive has empty or duplicate member inventory"
  recorded <- forM pieces $ \piece -> do
    path <- field piece "object"
    hash <- field piece "objectSha256"
    pure (takeFileName path, hash, piece)
  selected <- forM members $ \(name,expected) -> do
    require (archiveMember name) "native archive member is not a basename"
    let named = [(hash,piece) | (memberName,hash,piece) <- recorded, memberName == name]
        matches = [piece | (hash,piece) <- named, hash == expected]
    case nub matches of
      [piece] -> Right (Right [piece])
      [] | not complete -> Right (Right [])
         | null named -> Right (Left name)
         | otherwise -> Left ("native archive member differs from captured compiler products: " ++ name)
      _ -> Left ("native archive member has ambiguous compiler products: " ++ name)
  let missing = [name | Left name <- selected]
  pure (NativePieceSelection missing (concat [values | Right values <- selected]))

missingNativePieces :: [FilePath] -> String
missingNativePieces names = "C-only archive members have no captured compiler products: " ++ unwords names

-- | Read one exact resolved Cabal plan row and its matching registration.
-- Dependencies are the caller's resolved library closure: Custom Setup plans
-- nest them under components.lib rather than the row's top-level depends.
-- Haskell-bearing archives contribute only their captured C/C++ members;
-- never replay their native Haskell objects or native RTS registration.
readNativeProduct :: Value -> [String] -> FilePath -> FilePath -> IO (Maybe NativeProduct)
readNativeProduct unit dependencies registration pieces =
  readNativeProductAvailable unit dependencies registration pieces >>= either fail pure

-- | Read actual native products for original-build reuse. 'Left' describes
-- required products not recorded in this native build; the caller may perform
-- its normal isolated acquisition. Invalid registrations, archive inventories,
-- mismatched or ambiguous recorded products still fail. 'Right Nothing' is a
-- valid registration with no selected C/C++ provider.
readNativeProductAvailable :: Value -> [String] -> FilePath -> FilePath -> IO (Either String (Maybe NativeProduct))
readNativeProductAvailable unit dependencies registration pieces = do
  identifier <- get unit "id"
  bytes <- BS.readFile registration
  (_, info) <- either (fail . show) pure (parseInstalledPackageInfo bytes)
  check (prettyShow (Package.installedUnitId info) == identifier &&
    sort (map prettyShow (Package.depends info)) == sort dependencies)
    "native archive registration differs from resolved unit/dependencies"
  do
    let libraries = Package.hsLibraries info
    if null libraries then pure (Right Nothing) else do
      kind <- get unit "type" :: IO String
      style <- get unit "style" :: IO String
      -- Cabal's local source rows have a path, not a repository tarball hash.
      -- Actual compiled inputs and registered archive membership below still
      -- identify the selected code; retain the declared source without inventing
      -- a Hackage identity for it.
      let source = case member unit "pkg-src-sha256" of
            Just (String hash) -> validHash (Data.Text.unpack hash)
            Nothing | style `elem` ["local","inplace"], Just location <- member unit "pkg-src",
                member location "type" == Just "local", Just (String path) <- member location "path" ->
                  not (Data.Text.null path)
            _ -> False
      check (kind == "configured" && style `elem` ["global","inplace","local"] && source)
        "native dependency lacks a resolved source identity"
      paths <- filter ((== "piece.json") . takeFileName) <$> files pieces
      candidates <- mapM readJson paths
      candidateNames <- mapM (fmap takeFileName . (`get` "object")) candidates
      let complete = emptyRegistration identifier dependencies bytes
      archiveProducts <- forM libraries $ \library -> do
        check (takeFileName library == library && library `notElem` [".",".."])
          "native registration library is not a basename"
        let directories = nub (Package.libraryDirsStatic info ++ Package.libraryDirs info)
        found <- filterM doesFileExist [directory </> "lib" ++ library ++ ".a" | directory <- directories]
        archive <- case nub found of
          [path] -> pure path
          _ -> fail ("native registration lacks a unique native archive: " ++ identifier)
        before <- digest <$> BS.readFile archive
        ar <- maybe "ar" id <$> lookupEnv "THC_AR"
        (status, listing, diagnostic) <- readProcessWithExitCode ar ["t",archive] ""
        check (status == ExitSuccess) ("Cannot read native archive: " ++ diagnostic)
        -- Do not extract native Haskell members. Repeated Haskell basenames
        -- are legal in a mixed archive; only selected C membership must be
        -- unambiguous. C-only registrations still require every member.
        let names = [name | name <- archiveObjectNames listing, complete || name `elem` candidateNames]
        check ((not complete || not (null names)) && length names == length (nub names) &&
          all archiveMember names) "Unsupported native archive inventory"
        members <- forM names $ \name -> do
          contents <- withCreateProcess (proc ar ["p",archive,name]) {std_out=CreatePipe} $ \_ output _ handle -> do
            stream <- maybe (fail "Missing archive output pipe") pure output
            payload <- BS.hGetContents stream
            _ <- evaluate (BS.length payload)
            result <- waitForProcess handle
            check (result == ExitSuccess) "Cannot read native archive member"
            pure payload
          pure (name,digest contents)
        after <- digest <$> BS.readFile archive
        check (before == after) "native archive changed during selection"
        pure (archive,before,members)
      let members = concat [entries | (_,_,entries) <- archiveProducts]
      selection <- if null members && not complete then pure (NativePieceSelection [] []) else
        either fail pure (selectNativePiecesAvailable complete members candidates)
      let selected = matchedNativePieces selection
      -- One dependency cannot silently collect same-named sibling components.
      roots <- mapM (`get` "root") selected :: IO [String]
      check (length (nub roots) <= 1) "native archive combines different captured source roots"
      products <- forM selected $ \piece -> do
        -- These fields are also consumed by the package publisher's bare
        -- native-product path. Validate them before an acquisition miss can
        -- hide an invalid matched declaration.
        _ <- get piece "target" :: IO String
        inputs <- get piece "inputs" :: IO Value
        _ <- get inputs "compiler" :: IO String
        _ <- get inputs "arguments" :: IO [String]
        path <- get piece "bitcode"
        hash <- digest <$> BS.readFile path
        pure (object ["receipt" .= piece,"bitcodeSha256" .= hash])
      let identity = object ("depends" .= dependencies :
            [Key.fromString key .= maybe Null id (member unit key) |
            key <- ["id","type","style","pkg-name","pkg-version","flags",
                    "component-name","pkg-src","pkg-src-sha256","pkg-cabal-sha256"]])
          proof = object ["profile" .= ("resolved-native-archive-products-v1" :: String),
            "unit" .= identifier,"sourceIdentity" .= identity,
            "registration" .= T.decodeUtf8 bytes,"registrationSha256" .= digest bytes,
            "archives" .= [object ["path" .= path,"sha256" .= hash,
                "members" .= [object ["name" .= name,"sha256" .= value] | (name,value) <- entries]] |
              (path,hash,entries) <- archiveProducts],"translationUnits" .= products]
      pure (if null (missingNativeMembers selection)
        then Right (if null selected then Nothing else Just (NativeProduct proof selected))
        else Left (missingNativePieces (missingNativeMembers selection)))

member :: Value -> String -> Maybe Value
member (Object fields) key = KM.lookup (Key.fromString key) fields
member _ _ = Nothing
field :: FromJSON a => Value -> String -> Either String a
field value key = case member value key of
  Just item -> case fromJSON item of Success result -> Right result; Error message -> Left message
  Nothing -> Left ("Missing native dependency field: " ++ key)
get :: FromJSON a => Value -> String -> IO a
get value key = either fail pure (field value key)
require :: Bool -> String -> Either String ()
require condition message = if condition then Right () else Left message
check :: Bool -> String -> IO ()
check condition = unless condition . fail
readJson :: FilePath -> IO Value
readJson path = either fail pure . eitherDecodeStrict' =<< BS.readFile path
files :: FilePath -> IO [FilePath]
files directory = do
  exists <- doesDirectoryExist directory
  if not exists then pure [] else do
    names <- sort <$> listDirectory directory
    concat <$> forM names (\name -> do
      let path = directory </> name
      isDirectory <- doesDirectoryExist path
      if isDirectory then files path else pure [path])
validHash :: String -> Bool
validHash value = length value == 64 && all (`elem` ("0123456789abcdef" :: String)) value
-- BSD archive symbol indexes are container metadata, not compiler objects.
-- Keep every other member for exact membership and duplicate checks.
archiveObjectNames :: String -> [String]
archiveObjectNames listing = [name | name <- lines listing,
  name `notElem` ["__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED"]]

archiveMember :: String -> Bool
archiveMember [] = False
archiveMember name@(first:_) = first `notElem` ['-','@'] &&
  name `notElem` [".",".."] && all (\c -> isAscii c && (isAlphaNum c || c `elem` ("._-" :: String))) name
digest :: BS.ByteString -> String
digest = concatMap (\byte -> let value = showHex byte "" in if length value == 1 then '0':value else value) . BS.unpack . SHA.hash
