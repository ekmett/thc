-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}
module OriginalPathModeFixtures (prepareOriginalPathMode) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalPathMode :: FilePath -> IO ()
prepareOriginalPathMode _ = die "original-path-mode requires Unix"
#else

import Control.Monad (forM, unless, void)
import Data.Aeson (Value, object, (.=))
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Control.Exception (IOException)
import Data.Word (Word32)
import Data.Bits ((.&.))
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (nubBy)
import Data.IORef (newIORef, writeIORef)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins
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
import System.Directory (createDirectoryIfMissing, doesFileExist, doesDirectoryExist, removeFile, removePathForcibly, withCurrentDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("pathMkdir","mkdir"),("pathChmod","chmod")]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) convention F.PlayRisky))
    | unitString unit == "ghc-internal", convention == F.CCallConv,
      unpackFS name == "chmod" -> Just (unpackFS name)
    | unitString unit == owner, isOriginalUnixUnit owner, convention == F.CCallConv,
      unpackFS name == "mkdir" -> Just (unpackFS name)
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

prepareOriginalPathMode :: FilePath -> IO ()
prepareOriginalPathMode root = do
  let directory = "build/original-path-mode"
      source = "compiler/test-fixtures/OriginalPathModeAudit.hs"
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
  unless (oneLine version == "9.14.1") (die "Unix libc FFI fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "unix", "import-dirs", "--simple-output"]
  owner <- execute "unit" [] pkg ["field", "unix", "id", "--simple-output"]
  unless (isOriginalUnixUnit (oneLine owner)) (die "Path-mode proof requires the pinned installed unix owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = [oneLine ghcImports </> "GHC/Internal/System/Posix/Internals.hi",
                    oneLine imports </> "System/Posix/Directory.hi"]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    let symbol = originalSymbol (oneLine owner)
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "unix", "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (elem (unitString (moduleUnit (mi_module raw))) ["ghc-internal", oneLine owner])
        (die "Wrong installed pathname mode interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original pathname mode fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine pathname mode FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected pathname mode fixture module graph")
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
          _ -> error ("Missing pathname mode test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original pathname mode FCallId differs from typed consumer " ++ name)
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
            [] -> error "Empty pathname mode consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ observeModes (root </> directory </> "native-paths")
      [(name, unsafeCoerce value :: CString -> Word32 -> IO Int) | ((name,_),value) <- zip operations natives]
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["scripts/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalPathModeFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs", "scripts/core_original_foreign.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed GHC and Unix path-mode FCallIds" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-path-mode: 2 original FCallIds, native pathname mode observations and 4 strict audits"

-- Observe the actual inherited umask through native mkdir; never change it.
-- Each row uses fresh paths, so interpreted and installed calls can replay the
-- setup independently without replaying a completed filesystem effect.
observeModes :: FilePath -> [(String, CString -> Word32 -> IO Int)] -> IO Value
observeModes directory operationsNative = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True directory
  let mkdirCall = case lookup "pathMkdir" operationsNative of
        Just value -> value
        Nothing -> error "Missing original mkdir"
      modeOf :: BS.ByteString -> IO Int
      modeOf path = do
        result <- try (PosixBytes.getFileStatus path) :: IO (Either IOException PosixBytes.FileStatus)
        pure $ either (const (-1)) (fromIntegral . (.&. 0o777) . PosixBytes.fileMode) result
      cases entryName
        | entryName == "pathMkdir" =
          [("create","new",0o755),("zero","new",0),("wide","new",0x800001ed),
           ("existing-file","target",0o700),("existing-directory","sub",0o700),
           ("missing-parent","absent/child",0o700),("not-directory","target/child",0o700),
           ("empty","",0o700),("relative","sub/../new",0o751),("raw",BS.pack [toEnum 255,'m'],0o750)]
        | otherwise =
          [("file","target",0o640),("zero","target",0),("wide","target",0x800001a0),
           ("directory","sub",0o751),("link","link",0o600),("dangling","dangling",0o700),
           ("missing","absent",0o700),("not-directory","target/child",0o700),
           ("empty","",0o700),("relative","sub/../target",0o754),("raw",BS.pack [toEnum 255,'m'],0o650)]
  creationMask <- withCurrentDirectory directory $ do
    status <- BS.useAsCString "mask-probe" (\path -> mkdirCall path 0o777)
    unless (status == 0) (die "Native mkdir mask probe failed")
    modeOf "mask-probe"
  rows <- fmap concat $ forM operationsNative $ \(entryName,call) -> forM (cases entryName) $ \(label,path,mode) -> do
    let scratch = directory </> entryName ++ "-" ++ label
    createDirectoryIfMissing True (scratch </> "sub")
    withCurrentDirectory scratch $ do
      BS.writeFile "target" "unchanged"
      BS.useAsCString "target" $ \name -> void (P.c_chmod name 0o644)
      BS.useAsCString "sub" $ \name -> void (P.c_chmod name 0o700)
      PosixBytes.createSymbolicLink "target" "link"
      PosixBytes.createSymbolicLink "absent-target" "dangling"
      if entryName == "pathChmod" then PosixBytes.createSymbolicLink "target" (BS.pack [toEnum 255,'m']) else pure ()
      void (P.c_close (-1))
      status <- BS.useAsCString path (\name -> call name mode)
      Errno errno <- getErrno
      observed <- modeOf path
      targetMode <- modeOf "target"
      -- Restore access only after recording the result, for observation and cleanup.
      if status == 0 then BS.useAsCString path (\name -> void (P.c_chmod name 0o700)) else pure ()
      BS.useAsCString "target" $ \name -> void (P.c_chmod name 0o644)
      contents <- BS.readFile "target"
      unless (contents == "unchanged") (die "Native pathname mode changed file contents")
      pure (object ["entry" .= entryName, "name" .= (label :: String), "path" .= map fromEnum (BS.unpack path),
                    "mode" .= toInteger mode, "status" .= status, "errno" .= toInteger errno,
                    "observedMode" .= (observed :: Int), "targetMode" .= targetMode])
  pure (object ["creationMask" .= creationMask, "rows" .= rows])

#endif
