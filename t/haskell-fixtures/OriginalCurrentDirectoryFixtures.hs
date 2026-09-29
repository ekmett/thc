-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalCurrentDirectoryFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original current directory.
module OriginalCurrentDirectoryFixtures (prepareOriginalCurrentDirectory) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalCurrentDirectory :: FilePath -> IO ()
prepareOriginalCurrentDirectory _ = die "original-current-directory requires Unix"
#else

import Control.Monad (forM, forM_, unless, void, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, withObject, (.:), (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.Map.Strict as Map
import Data.Aeson.Types (parseEither)
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (plusPtr, nullPtr)
import Foreign.Storable (peekByteOff)
import Control.Exception (bracket, finally)
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified System.Posix.Directory as PosixDirectory
import qualified System.Posix.Directory.ByteString as PosixDirectoryBytes
import qualified System.Posix.IO as PosixIO
import qualified System.Posix.User as PosixUser
import qualified Data.ByteString.Char8 as BS
import Data.Word (Word8)
import Data.Char (toUpper)
import Data.List (nubBy, sort)
import Data.Time.Clock (getCurrentTime)
import Data.Time.Format (defaultTimeLocale, formatTime)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins hiding ((<>))
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.Runtime.Interpreter (wormhole)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing, doesFileExist, doesDirectoryExist, removeFile, removePathForcibly, getCurrentDirectory, makeAbsolute, createDirectory, listDirectory, pathIsSymbolicLink)
import System.Environment (getExecutablePath, lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory)
import qualified THC.Interface as Interface
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("pathChdir", "chdir"), ("pathGetCwd", "getcwd")]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ symbolName (Just unit) True) F.CCallConv F.PlayRisky))
    | isOriginalUnixUnit owner, unitString unit == owner,
      unpackFS symbolName `elem` map snd operations -> Just (unpackFS symbolName)
  _ -> Nothing

variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var value | isId value -> [value]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let bindings body -> concatMap (variables . snd) (flattenBinds [bindings]) ++ variables body
  Case value _ _ alternatives -> variables value ++ concat [variables body | Alt _ _ body <- alternatives]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

-- The installed Unix interface omits the recursive worker containing getcwd.
-- Retain complete Core from the exact unmodified upstream package instead of
-- constructing a foreign descriptor or relabelling a locally declared call.
unixArchive, unixArchiveHash, unixArchiveUrl :: String
unixArchive = "vendor/archives/unix-2.8.8.0.tar.gz"
unixArchiveHash = "a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e"
unixArchiveUrl = "https://hackage.haskell.org/package/unix-2.8.8.0/unix-2.8.8.0.tar.gz"

sourceFilesUnder :: FilePath -> FilePath -> IO [FilePath]
sourceFilesUnder root relative = do
  names <- sort <$> listDirectory (root </> relative)
  fmap concat $ forM names $ \name -> do
    let path = relative </> name
    symbolic <- pathIsSymbolicLink (root </> path)
    when symbolic (die ("Unexpected symbolic link in pinned Unix source: " ++ path))
    directory <- doesDirectoryExist (root </> path)
    if directory then sourceFilesUnder root path else pure [path]

prepareCurrentDirectoryChild :: FilePath -> IO ()
prepareCurrentDirectoryChild root = do
  let directory = "build/original-current-directory"
      source = "t/fixtures/compiler/OriginalCurrentDirectoryAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      manifest = root </> directory </> "manifest.json"
      oneLine result = case BS.lines (commandStdout result) of
        [value] -> BS.unpack value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  stale <- doesFileExist manifest
  if stale then removeFile manifest else pure ()
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Original current-directory fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  createDirectoryIfMissing True (root </> takeDirectory unixArchive)
  cached <- doesFileExist (root </> unixArchive)
  unless cached $ void $ execute "unix-download" [] "curl"
    ["--fail", "--location", "--retry", "2", unixArchiveUrl, "-o", unixArchive]
  digest <- hashFile (root </> unixArchive)
  unless (digest == unixArchiveHash) (die "Pinned Unix archive SHA256 mismatch")
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let sourceRun = directory </> "unix-source-" ++ stamp
      packageSource = sourceRun </> "unix-2.8.8.0"
      project = directory </> "unix.project"
      dist = directory </> "unix-dist"
      packageDb = root </> dist </> "packagedb/ghc-9.14.1"
  createDirectory (root </> sourceRun)
  extracted <- execute "unix-extract" [] "tar" ["-xzf", unixArchive, "-C", sourceRun]
  sourceFiles <- sourceFilesUnder root packageSource
  unless (all (\path -> packageSource </> path `elem` sourceFiles)
    ["unix.cabal", "LICENSE", "configure", "System/Posix/Directory/PosixPath.hsc"])
    (die "Incomplete pinned Unix source inventory")
  sourceHashes <- hashes root sourceFiles
  writeFile (root </> project) $ unlines
    ["packages: " ++ show (root </> packageSource),
     "package unix", "  shared: True", "  flags: +os-string",
     "  ghc-options: -fwrite-if-simplified-core -fexpose-all-unfoldings"]
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  built <- runLogged 600 root (directory </> "logs") "unix-build" [] cabal
    ["build", "lib:unix", "--offline", "-j4", "--project-file=" ++ project,
     "--builddir=" ++ root </> dist, "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ pkg]
  afterHashes <- hashes root sourceFiles
  unless (sourceHashes == afterHashes) (die "Pinned Unix build changed original source files")
  let packageArgs = ["--package-db", packageDb, "--no-user-package-db"]
  imports <- execute "imports" [] pkg (packageArgs ++ ["field", "unix", "import-dirs", "--simple-output"])
  owner <- execute "unit" [] pkg (packageArgs ++ ["field", "unix", "id", "--simple-output"])
  unless (isOriginalUnixUnit (oneLine owner)) (die "Current-directory proof requires the pinned rebuilt Unix owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = [oneLine imports </> "System/Posix/Directory/PosixPath.hi"]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    let symbol = originalSymbol (oneLine owner)
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package-db", packageDb, "-package-id", oneLine owner, "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == oneLine owner)
        (die "Wrong installed current-directory interface owner")
      complete <- Interface.loadInterfaceCore env (mi_module raw) path >>= maybe
        (die "Rebuilt Unix interface lacks complete Core") pure
      pure [value | (_, body) <- flattenBinds (Interface.interfaceBindings complete),
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine current-directory FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected current-directory fixture module graph")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var value | Just body <- lookup value bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        select name = case [(value, body) | (value, body) <- bindings,
                            getOccString value == name, isExternalName (varName value)] of
          [pair] -> pair
          _ -> error ("Missing current-directory test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original current-directory FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCoreCBD flags ["unit-qualified"] adapted >>= BS.writeFile (root </> directory </> "pre.cbd")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCoreCBD flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> directory </> "post.cbd")
    natives <- forM operations $ \(name, targetName) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty current-directory consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [change, currentName] -> observeCurrentDirectory (root </> directory </> "native-paths")
        (unsafeCoerce change :: CString -> IO Int)
        (unsafeCoerce currentName :: CString -> Word -> IO CString)
      _ -> die "Expected two original current-directory native functions"
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", "main:OriginalCurrentDirectoryAudit." ++ name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".cbd"]
  interfaceHashes <- hashes root interfaces
  registrationHash <- hashFile (packageDb </> oneLine owner ++ ".conf")
  writeJson (root </> directory </> "unix-source.json") $ object
    ["archive" .= unixArchive, "archiveSha256" .= unixArchiveHash, "archiveUrl" .= unixArchiveUrl,
     "sourceHashes" .= sourceHashes, "sourcesUnchangedAfterBuild" .= True,
     "unixUnit" .= oneLine owner, "packageDatabase" .= packageDb,
     "interfaceHashes" .= interfaceHashes, "registrationSha256" .= registrationHash]
  let commands = [version, library, extracted, built, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/OriginalCurrentDirectoryFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.cbd", "post.cbd", "oracle.json", "unix.project", "unix-source.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner, "nativeIsolatedChild" .= True,
     "consumerKind" .= ("typed consumers specialized with genuine source-built Unix chdir/getcwd FCallIds" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False,
     "unixSourceReceipt" .= (directory </> "unix-source.json"), "unixArchiveSha256" .= unixArchiveHash,
     "privateRebuiltUnix" .= True, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-current-directory: 2 original FCallIds, 11 chdir and 12 getcwd native observations, 4 strict audits"

-- Only the logged child may run genuine libc CWD effects. The coordinator
-- neither forks an initialized GHC runtime nor changes its working directory.
prepareOriginalCurrentDirectory :: FilePath -> IO ()
prepareOriginalCurrentDirectory root = do
  child <- lookupEnv "THC_CURRENT_DIRECTORY_FIXTURE_CHILD"
  if child == Just "1" then prepareCurrentDirectoryChild root else do
    originalCwd <- getCurrentDirectory
    let directory = "build/original-current-directory"
        manifest = root </> directory </> "manifest.json"
    stale <- doesFileExist manifest
    if stale then removeFile manifest else pure ()
    executable <- getExecutablePath
    result <- runLogged 900 root (directory </> "logs") "native-child"
      [("THC_CURRENT_DIRECTORY_FIXTURE_CHILD", "1")] executable ["original-current-directory"]
    finalCwd <- getCurrentDirectory
    unless (finalCwd == originalCwd) (die "Current-directory fixture coordinator changed process CWD")
    document <- BS.readFile manifest >>= either die pure . eitherDecodeStrict'
    oldHashes <- either die pure (parseEither (withObject "child manifest" (.: "artifactHashes")) document)
    commands <- either die pure (parseEither (withObject "child manifest" (.: "commands")) document)
    childHashes <- hashes root (commandArtifacts result)
    case document of
      Object fields -> writeJson manifest $ Object $
        KeyMap.insert "coordinatorCwdUnchanged" (toJSON True) $
        KeyMap.insert "artifactHashes" (toJSON (Map.union childHashes oldHashes)) $
        KeyMap.insert "commands" (toJSON (commands ++ [commandRecord result] :: [Value])) fields
      _ -> die "Expected child manifest object"
    putStrLn "original-current-directory: isolated native child and complete receipt accepted"

data ChdirCase = ChdirCase
  { chdirName :: String
  , chdirPath :: BS.ByteString
  , chdirAbsolute :: Bool
  }

chdirCases :: [ChdirCase]
chdirCases =
  [ row "sub" "sub"
  , row "dot" "."
  , row "dot-parent" "sub/.."
  , row "physical-link" "link"
  , row "link-parent" "link/.."
  , ChdirCase "absolute-sub" "sub" True
  , row "missing" "absent"
  , row "regular" "regular"
  , row "empty" ""
  , row "raw" (BS.pack [toEnum 255, 'n'])
  , row "search-denied" "search-denied"
  ]
  where row label path = ChdirCase label path False

getcwdCases :: [(String, String, String, Int)]
getcwdCases =
  [ ("base-ample", "base", "ample", 8)
  , ("base-exact", "base", "exact", 16)
  , ("base-short", "base", "short", 8)
  , ("base-one", "base", "one", 16)
  , ("base-zero", "base", "zero", 8)
  , ("sub", "sub", "ample", 16)
  , ("raw", "raw", "ample", 8)
  , ("physical-link", "physical-link", "ample", 16)
  , ("renamed", "renamed", "ample", 8)
  , ("renamed-ancestor", "renamed-ancestor", "ample", 16)
  , ("deleted", "deleted", "ample", 8)
  , ("long", "long", "ample", 16)
  ]

observeCurrentDirectory :: FilePath -> (CString -> IO Int) -> (CString -> Word -> IO CString) -> IO Value
observeCurrentDirectory directory nativeChdir nativeGetCwd = do
  originalCwd <- getCurrentDirectory
  absoluteDirectory <- makeAbsolute directory
  exists <- doesDirectoryExist absoluteDirectory
  if exists then removePathForcibly absoluteDirectory else pure ()
  createDirectoryIfMissing True absoluteDirectory
  realUid <- PosixUser.getRealUserID
  effectiveUid <- PosixUser.getEffectiveUserID
  realGid <- PosixUser.getRealGroupID
  effectiveGid <- PosixUser.getEffectiveGroupID
  let rawName = BS.pack [toEnum 255, 'n']
      longComponent = BS.replicate 96 'x'
      longDepth = 44 :: Int
      bytesValue = map fromEnum . BS.unpack
      requireChdir bytes = BS.useAsCString bytes $ \pointer -> do
        status <- nativeChdir pointer
        unless (status == 0) (die "Native current-directory setup chdir failed")
      observeName = allocaBytes 65536 $ \destination -> do
        pointer <- nativeGetCwd destination 65536
        if pointer == nullPtr then pure Nothing else do
          unless (pointer == destination) (die "Original getcwd changed caller-provided address")
          Just <$> BS.packCString pointer
      suffix base name = case BS.stripPrefix base name of
        Just rest | BS.null rest || BS.head rest == '/' -> pure rest
        _ -> die "Native physical CWD escaped its exact fixture root"
      setup label = do
        let base = absoluteDirectory </> label
            baseBytes = BS.pack base
        createDirectoryIfMissing True (base </> "parent/child")
        createDirectoryIfMissing True (base </> "sub")
        createDirectoryIfMissing True (base </> "search-denied")
        PosixBytes.setFileMode baseBytes 0o700
        BS.writeFile (base </> "regular") "unchanged"
        PosixDirectoryBytes.createDirectory (baseBytes <> "/" <> rawName) 0o700
        PosixBytes.createSymbolicLink "sub" (baseBytes <> "/link")
        PosixBytes.setFileMode (baseBytes <> "/search-denied") 0
        pure baseBytes
      seedError = void (P.c_close (-1))
  bracket (PosixIO.openFd originalCwd PosixIO.ReadOnly PosixIO.defaultFileFlags) PosixIO.closeFd $ \originalFd -> do
    let restore = PosixDirectory.changeWorkingDirectoryFd originalFd
        withBase label action = do
          base <- setup label
          finally (requireChdir base >> action base)
            (restore >> PosixBytes.setFileMode (base <> "/search-denied") 0o700)
    chdirRows <- forM chdirCases $ \scenario -> withBase ("chdir-" ++ chdirName scenario) $ \base -> do
      let actualPath = if chdirAbsolute scenario then base <> "/" <> chdirPath scenario else chdirPath scenario
      seedError
      status <- BS.useAsCString actualPath nativeChdir
      Errno errorNumber <- getErrno
      currentName <- observeName >>= maybe (die "Native chdir observation lost CWD name") pure
      relativeName <- suffix base currentName
      pure $ object ["name" .= chdirName scenario, "path" .= bytesValue (chdirPath scenario),
        "absolute" .= chdirAbsolute scenario, "status" .= status, "errno" .= toInteger errorNumber,
        "cwdSuffix" .= bytesValue relativeName]
    getcwdRows <- forM getcwdCases $ \(label, scenario, capacityKind, offset) -> withBase ("getcwd-" ++ label) $ \base -> do
      case scenario of
        "base" -> pure ()
        "sub" -> requireChdir "sub"
        "raw" -> requireChdir rawName
        "physical-link" -> requireChdir "link"
        "renamed" -> do
          requireChdir "sub"
          PosixBytes.rename (base <> "/sub") (base <> "/renamed")
        "renamed-ancestor" -> do
          requireChdir "parent/child"
          PosixBytes.rename (base <> "/parent") (base <> "/moved")
        "deleted" -> do
          requireChdir "sub"
          PosixDirectoryBytes.removeDirectory (base <> "/sub")
        "long" -> forM_ [1..longDepth] $ \_ -> do
          PosixDirectoryBytes.createDirectory longComponent 0o700
          requireChdir longComponent
        _ -> die "Unknown native getcwd setup"
      currentName <- observeName
      let requiredBytes = (+ 1) . BS.length <$> currentName
          capacity = case capacityKind of
            "ample" -> 16384
            "exact" -> maybe (error "No name for exact getcwd capacity") id requiredBytes
            "short" -> maybe (error "No name for short getcwd capacity") (subtract 1) requiredBytes
            "one" -> 1
            "zero" -> 0
            _ -> error "Unknown native getcwd capacity"
      allocaBytes (offset + capacity + 8) $ \storage -> do
        fillBytes storage 90 (offset + capacity + 8)
        let destination = plusPtr storage offset
        seedError
        returned <- nativeGetCwd destination (fromIntegral capacity)
        Errno errorNumber <- getErrno
        image <- mapM (peekByteOff storage) [0..offset+capacity+7] :: IO [Word8]
        let prefixGuard = take offset image
            payload = take capacity (drop offset image)
            suffixGuard = drop (offset + capacity) image
            guardsIntact = all (== 90) (prefixGuard ++ suffixGuard)
            failedImageUnchanged = all (== 90) image
        unless guardsIntact (die "Original native getcwd overwrote destination guards")
        (relativeName, tailUnchanged, nulTerminated) <- if returned == nullPtr
          then pure (Nothing, True, False)
          else do
            unless (returned == destination) (die "Original native getcwd did not return the supplied destination")
            let nameBytes = takeWhile (/= 0) payload
                hasNul = length nameBytes < length payload
            unless hasNul (die "Original native getcwd omitted a bounded NUL terminator")
            actualName <- BS.packCString returned
            normalized <- suffix base actualName
            unless (Just actualName == currentName) (die "Independent native getcwd observations disagree")
            pure (Just (bytesValue normalized), all (== 90) (drop (length nameBytes + 1) payload), True)
        pure $ object ["name" .= label, "setup" .= scenario, "capacityKind" .= capacityKind,
          "capacity" .= capacity, "requiredBytes" .= requiredBytes, "offset" .= offset,
          "returnedNull" .= (returned == nullPtr), "returnedSameBuffer" .= (returned == destination),
          "errno" .= toInteger errorNumber, "cwdSuffix" .= relativeName,
          "guardsIntact" .= guardsIntact, "failedImageUnchanged" .= failedImageUnchanged,
          "tailUnchanged" .= tailUnchanged, "nulTerminated" .= nulTerminated]
    restore
    finalCwd <- getCurrentDirectory
    unless (finalCwd == originalCwd) (die "Native child failed to restore its held original CWD")
    pure $ object ["chdirRows" .= chdirRows, "getcwdRows" .= getcwdRows,
      "longComponent" .= bytesValue longComponent, "longDepth" .= longDepth,
      "identity" .= object ["realUid" .= toInteger realUid, "effectiveUid" .= toInteger effectiveUid,
        "realGid" .= toInteger realGid, "effectiveGid" .= toInteger effectiveGid]]

#endif
