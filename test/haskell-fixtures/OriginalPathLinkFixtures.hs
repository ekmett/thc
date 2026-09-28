-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalPathLinkFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original path link.
module OriginalPathLinkFixtures (prepareOriginalPathLink) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalPathLink :: FilePath -> IO ()
prepareOriginalPathLink _ = die "original-path-link requires Unix"
#else

import Control.Monad (forM, unless, void)
import Data.Aeson (Value, object, (.=))
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Control.Exception (IOException)
import Data.Word (Word64)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, plusPtr)
import Foreign.Storable (peekByteOff)
import qualified Data.ByteString as Raw
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
operations = [("pathSymlink","symlink"),("pathReadlink","readlink")]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) convention F.PlayRisky))
    | unitString unit == owner, isOriginalUnixUnit owner, convention == F.CCallConv,
      unpackFS name `elem` ["symlink", "readlink"] -> Just (unpackFS name)
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

prepareOriginalPathLink :: FilePath -> IO ()
prepareOriginalPathLink root = do
  let directory = "build/original-path-link"
      source = "compiler/test-fixtures/OriginalPathLinkAudit.hs"
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
  unless (isOriginalUnixUnit (oneLine owner)) (die "Path-link proof requires the pinned installed unix owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = [oneLine ghcImports </> "GHC/Internal/System/Posix/Internals.hi",
                    oneLine imports </> "System/Posix/Files.hi"]
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
        (die "Wrong installed pathname link interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original pathname link fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine pathname link FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected pathname link fixture module graph")
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
          _ -> error ("Missing pathname link test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original pathname link FCallId differs from typed consumer " ++ name)
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
            [] -> error "Empty pathname link consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [symlinkValue, readlinkValue] -> observeLinks (root </> directory </> "native-paths")
        (unsafeCoerce symlinkValue :: CString -> CString -> IO Int)
        (unsafeCoerce readlinkValue :: CString -> Ptr () -> Word64 -> IO Int)
      _ -> die "Missing native pathname link consumers"
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["scripts/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalPathLinkFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs", "scripts/core_original_foreign.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed GHC and Unix path-link FCallIds" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-path-link: 2 original FCallIds, native pathname link observations and 4 strict audits"

-- Each effect has fresh paths. Observe errno before inspecting the result;
-- the target text itself is never canonicalized or required to exist.
observeLinks :: FilePath -> (CString -> CString -> IO Int) ->
                (CString -> Ptr () -> Word64 -> IO Int) -> IO Value
observeLinks directory symlinkCall readlinkCall = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True directory
  let bytes = map fromEnum . BS.unpack
      rawLink = BS.pack [toEnum 255, 'l']
      setup scratch = do
        createDirectoryIfMissing True (scratch </> "sub")
        BS.writeFile (scratch </> "target") "unchanged"
        withCurrentDirectory scratch $ PosixBytes.createSymbolicLink "target" "existing-link"
      linkCases =
        [("relative","target","new"),("dot-segments","sub/../target","new"),
         ("parent-relative","../target","new"),("absolute","/thc-original-link-target","new"),
         ("dangling","absent-target","new"),("raw-target",BS.pack [toEnum 255,'t'],"new"),
         ("raw-link","target",rawLink),("empty-target","","new"),("empty-path","target",""),
         ("existing-file","target","target"),("existing-link","target","existing-link"),
         ("missing-parent","target","absent/new"),("not-directory","target","target/new")]
      readCases =
        [("full","link","sub/../target",32),("exact","link","sub/../target",13),
         ("short","link","sub/../target",5),("one","link","sub/../target",1),
         ("zero","link","sub/../target",0),("dangling","link","absent-target",32),
         ("raw-target","link",BS.pack [toEnum 255,'t','/','.','.','/','x'],32),
         ("raw-link",rawLink,"target",32),("missing","absent","target",32),
         ("regular","target","target",32),("not-directory","target/child","target",32),
         ("empty","","target",32)]
  symlinkRows <- forM linkCases $ \(label,target,path) -> do
    let scratch = directory </> "symlink-" ++ label
    setup scratch
    withCurrentDirectory scratch $ do
      void (P.c_close (-1))
      status <- BS.useAsCString target $ \nativeTarget ->
        BS.useAsCString path (symlinkCall nativeTarget)
      Errno errno <- getErrno
      observed <- try (PosixBytes.readSymbolicLink path) :: IO (Either IOException BS.ByteString)
      contents <- BS.readFile "target"
      unless (contents == "unchanged") (die "Native symlink changed existing file contents")
      pure (object ["name" .= label, "target" .= bytes target, "path" .= bytes path,
                    "status" .= status, "errno" .= toInteger errno,
                    "linkTarget" .= either (const Nothing) (Just . bytes) observed])
  readlinkRows <- forM readCases $ \(label,path,target,capacity) -> do
    let scratch = directory </> "readlink-" ++ label
    setup scratch
    withCurrentDirectory scratch $ do
      PosixBytes.createSymbolicLink target "link"
      if path == rawLink then PosixBytes.createSymbolicLink target rawLink else pure ()
      allocaBytes (capacity + 16) $ \buffer -> do
        fillBytes buffer 90 (capacity + 16)
        void (P.c_close (-1))
        status <- BS.useAsCString path $ \nativePath ->
          readlinkCall nativePath (buffer `plusPtr` 8) (fromIntegral capacity)
        Errno errno <- getErrno
        image <- Raw.pack <$> mapM (peekByteOff buffer) [0 .. capacity + 15]
        pure (object ["name" .= label, "path" .= bytes path, "target" .= bytes target,
                      "capacity" .= capacity, "status" .= status, "errno" .= toInteger errno,
                      "bufferHex" .= hexBytes image])
  pure (object ["symlinkRows" .= symlinkRows, "readlinkRows" .= readlinkRows])

#endif
