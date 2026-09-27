-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalDirectoryPathsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original directory paths.
module OriginalDirectoryPathsFixtures (prepareOriginalDirectoryPaths) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalDirectoryPaths :: FilePath -> IO ()
prepareOriginalDirectoryPaths _ = die "original-directory-paths requires Unix"
#else

import Control.Monad (forM, forM_, unless, void)
import Data.Aeson (Value, object, (.=))
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Control.Exception (IOException)
import Data.Word (Word64, Word8)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, plusPtr, nullPtr)
import Foreign.Storable (peekByteOff)
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (nubBy)
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
import qualified System.Posix.Directory.ByteString as PosixDirectoryBytes
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("pathRemoveDirectory","rmdir"),("executableReadlink","readlink")]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) convention F.PlayRisky))
    | convention == F.CCallConv,
      (unpackFS name == "rmdir" && unitString unit == owner && isOriginalUnixUnit owner) ||
      (unpackFS name == "readlink" && unitString unit == "ghc-internal") -> Just (unpackFS name)
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

prepareOriginalDirectoryPaths :: FilePath -> IO ()
prepareOriginalDirectoryPaths root = do
  let directory = "build/original-directory-paths"
      source = "compiler/test-fixtures/OriginalDirectoryPathsAudit.hs"
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
  let interfaces = [oneLine ghcImports </> "GHC/Internal/System/Environment/ExecutablePath.hi",
                    oneLine imports </> "System/Posix/Directory/PosixPath.hi"]
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
        (die "Wrong installed directory pathname interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original directory pathname fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine directory pathname FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected directory pathname fixture module graph")
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
          _ -> error ("Missing directory pathname test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original directory pathname FCallId differs from typed consumer " ++ name)
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
            [] -> error "Empty directory pathname consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [removeValue, readlinkValue] -> observePaths (root </> directory </> "native-paths")
        (unsafeCoerce removeValue :: CString -> IO Int)
        (unsafeCoerce readlinkValue :: CString -> Ptr () -> Word64 -> IO Int)
      _ -> die "Missing native directory pathname consumers"
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["scripts/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalDirectoryPathsFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs", "scripts/core_original_foreign.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed GHC and Unix directory-path FCallIds" :: String),
     "readlinkUnit" .= ("ghc-internal" :: String), "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-directory-paths: 2 original FCallIds, native directory pathname observations and 4 strict audits"

removeCases :: [(String, BS.ByteString, Bool)]
removeCases = [("empty-directory","empty",False), ("raw",BS.pack [toEnum 255,'n'],False),
  ("absolute","empty",True), ("trailing-slash","empty/",False),
  ("nonempty","nonempty",False), ("regular","file",False), ("symlink","link",False),
  ("symlink-slash","link/",False), ("dangling","dangling",False), ("missing","missing",False),
  ("not-directory","file/child",False), ("empty-path","",False), ("dot",".",False),
  ("dot-parent","nonempty/..",False), ("permission","denied/child",False)]

readCases :: [(String, BS.ByteString, Bool, Int)]
readCases = [("link","link",False,64), ("short","link",False,1), ("exact","link",False,5),
  ("zero","link",False,0), ("raw-target","raw-link",False,64),
  ("absolute-target","absolute-link",False,64), ("dangling","dangling",False,64),
  ("regular","file",False,64), ("missing","missing",False,64), ("empty-path","",False,64),
  ("absolute-path","link",True,64), ("dot-parent","nonempty/../link",False,64)]

observePaths :: FilePath -> (CString -> IO Int) -> (CString -> Ptr () -> Word64 -> IO Int) -> IO Value
observePaths directory removeCall readCall = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True directory
  cwd <- getCurrentDirectory
  absoluteDirectory <- makeAbsolute directory
  let asBytes = map fromEnum . BS.unpack
      errnoValue = do Errno value <- getErrno; pure (fromIntegral value :: Int)
      seed = void (P.c_write (-1) nullPtr 0)
      setup name = do
        let base = absoluteDirectory </> name
            raw = BS.pack base
        createDirectoryIfMissing True base
        PosixBytes.setFileMode raw 0o700
        BS.writeFile (base </> "file") "unchanged"
        forM_ ["empty", "nonempty", "denied", BS.pack [toEnum 255,'n']] $ \child ->
          PosixDirectoryBytes.createDirectory (raw <> "/" <> child) 0o700
        BS.writeFile (base </> "nonempty/file") "unchanged"
        PosixDirectoryBytes.createDirectory (raw <> "/denied/child") 0o700
        PosixBytes.createSymbolicLink "empty" (raw <> "/link")
        PosixBytes.createSymbolicLink "missing" (raw <> "/dangling")
        PosixBytes.createSymbolicLink (BS.pack [toEnum 255,'n']) (raw <> "/raw-link")
        PosixBytes.createSymbolicLink "/thc-native-readlink" (raw <> "/absolute-link")
        PosixBytes.setFileMode (raw <> "/denied") 0
        pure base
      nativePath base suffix absolute = if BS.null suffix then BS.empty
        else BS.pack (if absolute then base else makeRelative cwd base) <> "/" <> suffix
      remaining base = fmap concat $ forM ["empty", "nonempty", "file", "link", "dangling",
          "raw-link", "absolute-link", "denied", "denied/child", BS.pack [toEnum 255,'n']] $ \name -> do
        result <- try (PosixBytes.getSymbolicLinkStatus (BS.pack base <> "/" <> name)) :: IO (Either IOException PosixBytes.FileStatus)
        pure [asBytes name | Right _ <- [result]]
  removeRows <- forM removeCases $ \(name, suffix, absolute) -> do
    base <- setup ("remove-" ++ name)
    seed
    status <- BS.useAsCString (nativePath base suffix absolute) removeCall
    errorCode <- errnoValue
    -- Observe results before cleanup, but restore search permission before
    -- lstat so the inventory reports namespace changes rather than privilege.
    PosixBytes.setFileMode (BS.pack base <> "/denied") 0o700
    retained <- remaining base
    pure $ object ["name" .= name, "path" .= asBytes suffix, "absolute" .= absolute,
      "status" .= status, "errno" .= errorCode, "remaining" .= retained]
  readRows <- forM readCases $ \(name, suffix, absolute, capacity) -> do
    base <- setup ("read-" ++ name)
    row <- allocaBytes (capacity + 16) $ \image -> do
      fillBytes image 90 (capacity + 16)
      seed
      status <- BS.useAsCString (nativePath base suffix absolute) $ \path ->
        readCall path (image `plusPtr` 8) (fromIntegral capacity)
      errorCode <- errnoValue
      actual <- mapM (peekByteOff image) [0..capacity+15] :: IO [Word8]
      pure $ object ["name" .= name, "path" .= asBytes suffix, "absolute" .= absolute,
        "capacity" .= capacity, "status" .= status, "errno" .= errorCode, "image" .= actual]
    PosixBytes.setFileMode (BS.pack base <> "/denied") 0o700
    pure row
  unchanged <- (==cwd) <$> getCurrentDirectory
  unless unchanged (die "Directory pathname oracle changed process CWD")
  pure $ object ["removeRows" .= removeRows, "readRows" .= readRows, "processCwdUnchanged" .= unchanged]
#endif
