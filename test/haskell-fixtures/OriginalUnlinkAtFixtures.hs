-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalUnlinkAtFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original unlink at.
module OriginalUnlinkAtFixtures (prepareOriginalUnlinkAt) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalUnlinkAt :: FilePath -> IO ()
prepareOriginalUnlinkAt _ = die "original-unlinkat requires Unix"
#else

import Control.Monad (filterM, forM, unless, void)
import Data.Aeson (Value, eitherDecodeStrict', object, withObject, (.:), (.=))
import Data.Aeson.Types (parseEither)
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Control.Exception (bracket, catch, onException, IOException)
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified System.Posix.IO as PosixIO
import System.Posix.Types (Fd(..))
import System.IO (hClose)
import System.IO.Error (isDoesNotExistError)
import qualified Data.ByteString.Char8 as BS
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
operations = [("pathUnlinkAt","unlinkat")]

isDirectoryUnit :: String -> Bool
isDirectoryUnit value = case stripPrefix "directory-1.3.10.0-" value of
  Just "inplace" -> True
  Just suffix -> not (null suffix) && all (\c -> c `elem` ("0123456789abcdef" :: String)) suffix
  Nothing -> False

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlaySafe))
    | isDirectoryUnit owner, unitString unit == owner, unpackFS name == "unlinkat" -> Just "unlinkat"
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

prepareOriginalUnlinkAt :: FilePath -> IO ()
prepareOriginalUnlinkAt root = do
  let directory = "build/original-unlinkat"
      source = "test/fixtures/compiler/OriginalUnlinkAtAudit.hs"
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
  unless (oneLine version == "9.14.1") (die "Original unlinkat fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "directory", "import-dirs", "--simple-output"]
  owner <- execute "unit" [] pkg ["field", "directory", "id", "--simple-output"]
  unless (isDirectoryUnit (oneLine owner)) (die "Unlinkat proof requires the pinned installed directory owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  compiler <- maybe "cc" id <$> lookupEnv "CC"
  abiCompile <- execute "abi-compile" [] compiler
    ["-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "src/main/c/stdio-abi-probe.c",
     "-o", directory </> "ghc/stdio-abi-probe"]
  abiRun <- execute "abi-run" [] (root </> directory </> "ghc/stdio-abi-probe") []
  abiJSON <- either die pure (eitherDecodeStrict' (commandStdout abiRun) :: Either String Value)
  (atCwd, atRemove, readOnly) <- either die pure $ parseEither (withObject "stdio ABI" $ \value -> do
    at <- value .: "at"
    opening <- value .: "open"
    (,,) <$> at .: "AT_FDCWD" <*> at .: "AT_REMOVEDIR" <*> opening .: "O_RDONLY") abiJSON
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
        (die "Wrong installed unlinkat interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original unlinkat fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine unlinkat FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected unlinkat fixture module graph")
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
          _ -> error ("Missing unlinkat test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original unlinkat FCallId differs from typed consumer " ++ name)
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
            [] -> error "Empty unlinkat consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ observeUnlinkAt (root </> directory </> "native-paths") atCwd atRemove readOnly
      [(name, unsafeCoerce value :: Int -> CString -> Int -> IO Int) | ((name,_),value) <- zip operations natives]
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner, ghcImports, abiCompile, abiRun] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalUnlinkAtFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java", "src/main/c/stdio-abi-probe.c"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "directoryUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumer specialized with the genuine installed directory unlinkat FCallId" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-unlinkat: 1 original FCallId, 34 native unlinkat observations and 2 strict audits"

data UnlinkCase = UnlinkCase
  { unlinkName :: String
  , unlinkFdMode :: String
  , unlinkFdOffset :: Int
  , unlinkPath :: BS.ByteString
  , unlinkAbsolute :: Bool
  , unlinkFlags :: Int
  }

unlinkCases :: Int -> [UnlinkCase]
unlinkCases removeFlag =
  [ row "cwd-file" "cwd" "file" 0
  , row "directory-file" "directory" "file" 0
  , row "duplicate-file" "duplicate" "file" 0
  , row "closed-file" "closed" "file" 0
  , row "regular-file" "regular" "file" 0
  , row "invalid-file" "invalid" "file" 0
  , absolute "absolute-invalid-file" "invalid"
  , absolute "absolute-closed-file" "closed"
  , absolute "absolute-regular-file" "regular"
  , absolute "absolute-cwd-file" "cwd"
  , row "directory-link" "directory" "link" 0
  , row "directory-dangling" "directory" "dangling" 0
  , row "directory-empty-no-flag" "directory" "empty" 0
  , row "directory-empty-remove" "directory" "empty" removeFlag
  , row "directory-nonempty-remove" "directory" "nonempty" removeFlag
  , row "directory-file-remove" "directory" "file" removeFlag
  , row "directory-link-remove" "directory" "link" removeFlag
  , row "directory-dangling-remove" "directory" "dangling" removeFlag
  , row "directory-missing" "directory" "missing" 0
  , row "directory-missing-remove" "directory" "missing" removeFlag
  , row "directory-empty-path" "directory" "" 0
  , row "directory-empty-path-remove" "directory" "" removeFlag
  , row "directory-raw" "directory" (BS.pack [toEnum 255, 'n']) 0
  , row "directory-dot-file" "directory" "sub/../file" 0
  , row "directory-trailing-file" "directory" "file/" 0
  , row "directory-trailing-empty-remove" "directory" "empty/" removeFlag
  , row "directory-invalid-flags" "directory" "file" 1
  , row "directory-negative-flags" "directory" "file" (-1)
  , row "directory-narrow-flags" "directory" "file" 4294967296
  , (row "cwd-narrow-fd" "cwd" "file" 0) { unlinkFdOffset = 4294967296 }
  , (row "directory-narrow-fd" "directory" "file" 0) { unlinkFdOffset = 4294967296 }
  , row "invalid-fd-invalid-flags" "invalid" "file" 1
  , row "invalid-fd-empty" "invalid" "" 0
  , row "invalid-fd-long" "invalid" (BS.replicate 4096 'x') 0
  ]
  where
    row label fdMode path flagsValue = UnlinkCase label fdMode 0 path False flagsValue
    absolute label fdMode = (row label fdMode "file" 0) { unlinkAbsolute = True }

-- Each observation owns fresh paths and real native descriptors. AT_FDCWD uses
-- a relative prefix from the existing process CWD; this producer never chdirs.
observeUnlinkAt :: FilePath -> Int -> Int -> Int -> [(String, Int -> CString -> Int -> IO Int)] -> IO Value
observeUnlinkAt directory atCwd atRemove readOnly operationsNative = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True directory
  native <- case lookup "pathUnlinkAt" operationsNative of
    Just call -> pure call
    Nothing -> die "Missing original unlinkat"
  originalCwd <- getCurrentDirectory
  absoluteDirectory <- makeAbsolute directory
  let rawName = BS.pack [toEnum 255, 'n']
      inventory = [("file","file"),("other","other"),("regular","regular"),
        ("link","link"),("dangling","dangling"),("empty","empty"),
        ("nonempty","nonempty"),("child","nonempty/child"),("sub","sub"),("raw",rawName)]
      regularKeys = ["file","other","regular","child","raw"] :: [String]
      openNative path = BS.useAsCString path $ \pointer -> do
        descriptor <- P.c_open pointer (fromIntegral readOnly) 0
        unless (descriptor >= 0) (die "Native unlinkat setup open failed")
        pure descriptor
      closeNative descriptor = void (P.c_close descriptor)
      readNative path = do
        descriptor <- openNative path
        handle <- PosixIO.fdToHandle (Fd descriptor) `onException` closeNative descriptor
        bracket (pure handle) hClose BS.hGetContents
      present path = (PosixBytes.getSymbolicLinkStatus path >> pure True) `catch` absent
      absent :: IOException -> IO Bool
      absent failure = if isDoesNotExistError failure then pure False else ioError failure
      withDescriptor fdMode base action = case fdMode of
        "cwd" -> action atCwd
        "invalid" -> action (-1)
        "closed" -> do
          descriptor <- openNative base
          status <- P.c_close descriptor
          unless (status == 0) (die "Native unlinkat setup close failed")
          action (fromIntegral descriptor)
        "regular" -> bracket (openNative (base <> "/regular")) closeNative (action . fromIntegral)
        "directory" -> bracket (openNative base) closeNative (action . fromIntegral)
        "duplicate" -> bracket (openNative base) closeNative $ \descriptor ->
          bracket (do duplicateFd <- P.c_dup descriptor
                      unless (duplicateFd >= 0) (die "Native unlinkat setup dup failed")
                      pure duplicateFd) closeNative (action . fromIntegral)
        _ -> die "Unknown native unlinkat descriptor setup"
  rows <- forM (unlinkCases atRemove) $ \scenario -> do
    let base = absoluteDirectory </> unlinkName scenario
        baseBytes = BS.pack base
        relativeBase = BS.pack (makeRelative originalCwd base)
        setupPath bytes = baseBytes <> "/" <> bytes
    createDirectoryIfMissing True (base </> "empty")
    createDirectoryIfMissing True (base </> "nonempty")
    createDirectoryIfMissing True (base </> "sub")
    void $ forM ["file","other","regular","nonempty/child","raw-file"] $ \leaf ->
      BS.writeFile (base </> leaf) "unchanged"
    PosixBytes.rename (setupPath "raw-file") (setupPath rawName)
    PosixBytes.createSymbolicLink "file" (setupPath "link")
    PosixBytes.createSymbolicLink "missing-target" (setupPath "dangling")
    let logical = unlinkPath scenario
        actualPath
          | BS.null logical = logical
          | unlinkAbsolute scenario = setupPath logical
          | unlinkFdMode scenario == "cwd" = relativeBase <> "/" <> logical
          | otherwise = logical
    (status, errorNumber) <- withDescriptor (unlinkFdMode scenario) baseBytes $ \descriptor -> do
      void (P.c_close (-1)) -- Seed sticky errno before the genuine call.
      status <- BS.useAsCString actualPath $ \pointer ->
        native (descriptor + unlinkFdOffset scenario) pointer (unlinkFlags scenario)
      Errno errorNumber <- getErrno
      pure (status, errorNumber)
    remaining <- filterM (present . setupPath . snd) inventory
    void $ forM remaining $ \(key, path) ->
      if key `elem` regularKeys then do
        contents <- readNative (setupPath path)
        unless (contents == "unchanged") (die "Native unlinkat changed retained file contents")
      else pure ()
    pure $ object ["name" .= unlinkName scenario, "fdMode" .= unlinkFdMode scenario,
      "fdOffset" .= toInteger (unlinkFdOffset scenario), "path" .= map fromEnum (BS.unpack logical),
      "absolute" .= unlinkAbsolute scenario, "flags" .= toInteger (unlinkFlags scenario),
      "status" .= status, "errno" .= toInteger errorNumber, "remaining" .= map fst remaining]
  finalCwd <- getCurrentDirectory
  unless (finalCwd == originalCwd) (die "Native unlinkat fixture changed process CWD")
  pure $ object ["at" .= object ["AT_FDCWD" .= atCwd, "AT_REMOVEDIR" .= atRemove], "rows" .= rows]

#endif
