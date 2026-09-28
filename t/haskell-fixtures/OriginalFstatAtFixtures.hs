-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalFstatAtFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original fstat at.
module OriginalFstatAtFixtures (prepareOriginalFstatAt) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalFstatAt :: FilePath -> IO ()
prepareOriginalFstatAt _ = die "original-fstatat requires Unix"
#else

import Control.Monad (forM, unless, void)
import Data.Aeson (Value, eitherDecodeStrict', object, withObject, (.:), (.=))
import Data.Aeson.Types (parseEither)
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, castPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import Control.Exception (bracket)
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified Data.ByteString.Char8 as BS
import Data.Bits ((.&.), (.|.))
import Data.Word (Word8)
import Data.Char (toUpper)
import Data.List (nubBy, stripPrefix)
import Data.IORef (newIORef, writeIORef)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins hiding ((<>))
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Runtime.Interpreter (wormhole)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing, doesFileExist, doesDirectoryExist, removeFile, removePathForcibly, getCurrentDirectory, makeAbsolute)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("pathFstatAt","fstatat")]

isDirectoryUnit :: String -> Bool
isDirectoryUnit value = case stripPrefix "directory-1.3.10.0-" value of
  Just "inplace" -> True
  Just suffix -> not (null suffix) && all (\c -> c `elem` ("0123456789abcdef" :: String)) suffix
  Nothing -> False

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CApiConv F.PlaySafe))
    | isDirectoryUnit owner, unitString unit == owner,
      unpackFS name == "ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zm" ++
        drop (length ("directory-1.3.10.0-" :: String)) owner ++
        "ZCSystemziDirectoryziInternalziPosixZCfstatat" -> Just "fstatat"
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

prepareOriginalFstatAt :: FilePath -> IO ()
prepareOriginalFstatAt root = do
  let directory = "build/original-fstatat"
      source = "t/fixtures/compiler/OriginalFstatAtAudit.hs"
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
  unless (oneLine version == "9.14.1") (die "Original fstatat fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "directory", "import-dirs", "--simple-output"]
  owner <- execute "unit" [] pkg ["field", "directory", "id", "--simple-output"]
  unless (isDirectoryUnit (oneLine owner)) (die "Fstatat proof requires the pinned installed directory owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  compiler <- maybe "cc" id <$> lookupEnv "CC"
  abiCompile <- execute "abi-compile" [] compiler
    ["-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "src/main/c/stdio-abi-probe.c",
     "-o", directory </> "ghc/stdio-abi-probe"]
  abiRun <- execute "abi-run" [] (root </> directory </> "ghc/stdio-abi-probe") []
  abiJSON <- either die pure (eitherDecodeStrict' (commandStdout abiRun) :: Either String Value)
  (atCwd, atNoFollow, atEmpty, readOnly) <- either die pure $ parseEither (withObject "stdio ABI" $ \value -> do
    at <- value .: "at"
    opening <- value .: "open"
    (,,,) <$> at .: "AT_FDCWD" <*> at .: "AT_SYMLINK_NOFOLLOW" <*> at .: "AT_EMPTY_PATH" <*> opening .: "O_RDONLY") abiJSON
  let interfaces = [oneLine imports </> "System/Directory/Internal/Posix.hi"]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    let symbol = originalSymbol (oneLine owner)
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "directory", "-package", "unix", "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == oneLine owner)
        (die "Wrong installed fstatat interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original fstatat fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine fstatat FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected fstatat fixture module graph")
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
          _ -> error ("Missing fstatat test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original fstatat FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    natives <- forM operations $ \(name, targetName) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty fstatat consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ observeFstatAt (root </> directory </> "native-paths") atCwd atNoFollow atEmpty readOnly
      [(name, unsafeCoerce value :: Int -> CString -> Ptr () -> Int -> IO Int) | ((name,_),value) <- zip operations natives]
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner, ghcImports, abiCompile, abiRun] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/OriginalFstatAtFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java", "src/main/c/stdio-abi-probe.c"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "directoryUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumer specialized with the genuine installed directory fstatat FCallId" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-fstatat: 1 original FCallId, 47 native fstatat observations and 2 strict audits"

data FstatCase = FstatCase
  { statName :: String
  , statFdMode :: String
  , statFdOffset :: Int
  , statPath :: BS.ByteString
  , statAbsolute :: Bool
  , statFlags :: Int
  }

statCases :: Int -> Int -> [FstatCase]
statCases noFollow emptyPath =
  [ row "cwd-target" "cwd" "target" 0
  , row "directory-target" "directory" "target" 0
  , row "duplicate-target" "duplicate" "target" 0
  , row "closed-target" "closed" "target" 0
  , row "regular-target" "regular" "target" 0
  , row "invalid-target" "invalid" "target" 0
  , absolute "absolute-invalid-target" "invalid"
  , absolute "absolute-closed-target" "closed"
  , absolute "absolute-regular-target" "regular"
  , absolute "absolute-cwd-target" "cwd"
  , row "directory-sub" "directory" "sub" 0
  , row "directory-link-follow" "directory" "link" 0
  , row "directory-link-nofollow" "directory" "link" noFollow
  , row "directory-dangling-follow" "directory" "dangling" 0
  , row "directory-dangling-nofollow" "directory" "dangling" noFollow
  , row "directory-missing" "directory" "absent" 0
  , row "directory-not-directory" "directory" "target/child" 0
  , row "directory-dot-target" "directory" "sub/../target" 0
  , row "directory-raw-follow" "directory" (BS.pack [toEnum 255, 'n']) 0
  , row "directory-raw-nofollow" "directory" (BS.pack [toEnum 255, 'n']) noFollow
  , row "directory-invalid-flags" "directory" "target" 1
  , row "directory-negative-flags" "directory" "target" (-1)
  , row "directory-narrow-flags" "directory" "target" 4294967296
  , (row "cwd-narrow-fd" "cwd" "target" 0) { statFdOffset = 4294967296 }
  , (row "directory-narrow-fd" "directory" "target" 0) { statFdOffset = 4294967296 }
  ] ++
  [ row (fdMode ++ "-empty-" ++ suffix) fdMode "" flagsValue
  | fdMode <- ["cwd", "directory", "duplicate", "regular", "closed", "invalid"]
  , (suffix, flagsValue) <- [("plain", 0), ("allowed", emptyPath), ("nofollow", emptyPath .|. noFollow)]
  ] ++
  [ row "invalid-fd-long" "invalid" (BS.replicate 4096 'x') 0
  , row "invalid-fd-invalid-flags" "invalid" "target" 1
  , row "directory-target-empty-flag" "directory" "target" emptyPath
  , row "directory-link-empty-nofollow" "directory" "link" (emptyPath .|. noFollow)
  ]
  where
    row label fdMode path flagsValue = FstatCase label fdMode 0 path False flagsValue
    absolute label fdMode = (row label fdMode "target" 0) { statAbsolute = True }

-- Each observation owns fresh paths and genuine native descriptors. An empty
-- AT_FDCWD call inspects the actual process CWD: never replace the empty path
-- with a prefix or chdir the producer. Other cwd paths use a relative prefix.
observeFstatAt :: FilePath -> Int -> Int -> Int -> Int -> [(String, Int -> CString -> Ptr () -> Int -> IO Int)] -> IO Value
observeFstatAt directory atCwd atNoFollow atEmpty readOnly operationsNative = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True directory
  native <- case lookup "pathFstatAt" operationsNative of
    Just call -> pure call
    Nothing -> die "Missing original fstatat"
  originalCwd <- getCurrentDirectory
  absoluteDirectory <- makeAbsolute directory
  cwdMode <- allocaBytes P.sizeof_stat $ \image -> BS.useAsCString "." $ \pointer -> do
    result <- P.c_stat pointer image
    unless (result == 0) (die "Native fstatat CWD observation failed")
    mode <- P.st_mode image
    pure (fromIntegral mode .&. 0o177777 :: Int)
  let rawName = BS.pack [toEnum 255, 'n']
      openNative path = BS.useAsCString path $ \pointer -> do
        descriptor <- P.c_open pointer (fromIntegral readOnly) 0
        unless (descriptor >= 0) (die "Native fstatat setup open failed")
        pure descriptor
      closeNative descriptor = void (P.c_close descriptor)
      withDescriptor fdMode base action = case fdMode of
        "cwd" -> action atCwd
        "invalid" -> action (-1)
        "closed" -> do
          descriptor <- openNative base
          result <- P.c_close descriptor
          unless (result == 0) (die "Native fstatat setup close failed")
          action (fromIntegral descriptor)
        "regular" -> bracket (openNative (base <> "/regular")) closeNative (action . fromIntegral)
        "directory" -> bracket (openNative base) closeNative (action . fromIntegral)
        "duplicate" -> bracket (openNative base) closeNative $ \descriptor ->
          bracket (do duplicateFd <- P.c_dup descriptor
                      unless (duplicateFd >= 0) (die "Native fstatat setup dup failed")
                      pure duplicateFd) closeNative (action . fromIntegral)
        _ -> die "Unknown native fstatat descriptor setup"
  rows <- forM (statCases atNoFollow atEmpty) $ \scenario -> do
    let base = absoluteDirectory </> statName scenario
        baseBytes = BS.pack base
        relativeBase = BS.pack (makeRelative originalCwd base)
        setupPath bytes = baseBytes <> "/" <> bytes
    createDirectoryIfMissing True (base </> "sub")
    PosixBytes.setFileMode baseBytes 0o700
    PosixBytes.setFileMode (setupPath "sub") 0o750
    BS.writeFile (base </> "target") (BS.pack [toEnum n | n <- [0..31]])
    BS.writeFile (base </> "regular") "unchanged"
    PosixBytes.setFileMode (setupPath "target") 0o640
    PosixBytes.setFileMode (setupPath "regular") 0o640
    PosixBytes.createSymbolicLink "target" (setupPath "link")
    PosixBytes.createSymbolicLink "absent-target" (setupPath "dangling")
    PosixBytes.createSymbolicLink "target" (setupPath rawName)
    let logical = statPath scenario
        actualPath
          | BS.null logical = logical
          | statAbsolute scenario = setupPath logical
          | statFdMode scenario == "cwd" = relativeBase <> "/" <> logical
          | otherwise = logical
    allocaBytes P.sizeof_stat $ \baseline -> do
      BS.useAsCString (setupPath "target") $ \pointer -> do
        result <- P.c_stat pointer baseline
        unless (result == 0) (die "Native fstatat target baseline failed")
      device <- P.st_dev baseline
      inode <- P.st_ino baseline
      withDescriptor (statFdMode scenario) baseBytes $ \descriptor ->
        BS.useAsCString actualPath $ \pointer -> allocaBytes (P.sizeof_stat + 16) $ \storage -> do
          fillBytes storage 90 (P.sizeof_stat + 16)
          let destination = plusPtr storage 8
          void (P.c_close (-1)) -- Seed sticky errno before the unchanged safe call.
          status <- native (descriptor + statFdOffset scenario) pointer destination (statFlags scenario)
          Errno errorNumber <- getErrno
          bytes <- mapM (peekByteOff storage) [0..P.sizeof_stat+15] :: IO [Word8]
          values <- if status == 0 then do
            let image = castPtr destination
            size <- P.st_size image
            mode <- P.st_mode image
            actualDevice <- P.st_dev image
            actualInode <- P.st_ino image
            pure [if P.c_s_isdir mode /= 0 then -1 else fromIntegral size,
              fromIntegral mode .&. 0o177777, fromEnum (actualDevice == device), fromEnum (actualInode == inode)]
            else pure []
          let unchanged = if status == 0 then all (== 90) (take 8 bytes ++ drop (P.sizeof_stat + 8) bytes)
                            else all (== 90) bytes
          unless unchanged (die "Native fstatat changed destination guards or failed image")
          pure $ object ["name" .= statName scenario, "fdMode" .= statFdMode scenario,
            "fdOffset" .= toInteger (statFdOffset scenario), "path" .= map fromEnum (BS.unpack logical),
            "absolute" .= statAbsolute scenario, "flags" .= toInteger (statFlags scenario),
            "status" .= status, "errno" .= toInteger errorNumber, "values" .= (values :: [Int]),
            "guardsIntact" .= unchanged]
  finalCwd <- getCurrentDirectory
  unless (finalCwd == originalCwd) (die "Native fstatat fixture changed process CWD")
  pure $ object ["size" .= P.sizeof_stat, "cwdMode" .= cwdMode,
    "at" .= object ["AT_FDCWD" .= atCwd, "AT_SYMLINK_NOFOLLOW" .= atNoFollow, "AT_EMPTY_PATH" .= atEmpty],
    "rows" .= rows]

#endif
