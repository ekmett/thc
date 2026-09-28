-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : UnixLibcFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for unix libc.
module UnixLibcFixtures (prepareUnixLibc) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareUnixLibc :: FilePath -> IO ()
prepareUnixLibc _ = die "unix-libc requires Unix"
#else

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString, withCString, peekCString)
import Foreign.Ptr (nullPtr)
import qualified System.IO as IO
import qualified System.Posix.IO as Posix
import qualified System.Posix.IO.ByteString as PosixBytes
import qualified System.Posix.Env as Env
import System.Posix.Types (Fd(..))
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
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("unixClose","close"),("unixDup","dup"),("unixIsatty","isatty"),("unixGetenv","getenv")]

symbol :: Id -> Maybe String
symbol value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlayRisky))
    | isOriginalUnixUnit (unitString unit), unpackFS name `elem` map snd operations ->
        Just (unpackFS name)
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

prepareUnixLibc :: FilePath -> IO ()
prepareUnixLibc root = do
  let directory = "build/unix-libc"
      source = "t/fixtures/compiler/UnixLibcAudit.hs"
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
  unless (isOriginalUnixUnit (oneLine owner)) (die "Expected the pinned installed unix 2.8.8.0 owner")
  let interfaces = map (oneLine imports </>)
        ["System/Posix/IO/Common.hi", "System/Posix/Terminal/Common.hi", "System/Posix/Env/PosixString.hi"]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "unix", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == oneLine owner)
        (die "Wrong installed unix libc interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original unix libc fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine unix libc FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected unix libc fixture module graph")
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
          _ -> error ("Missing unix libc test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original unix libc FCallId differs from typed consumer " ++ name)
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
            [] -> error "Empty unix libc consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [closeValue, dupValue, isattyValue, getenvValue] -> do
        let close = unsafeCoerce closeValue :: Int -> IO Int
            duplicate = unsafeCoerce dupValue :: Int -> IO Int
            isatty = unsafeCoerce isattyValue :: Int -> IO Int
            getenv = unsafeCoerce getenvValue :: CString -> IO CString
            private = root </> directory </> "native-file"
        writeFile private "Z"
        handle <- IO.openBinaryFile private IO.ReadWriteMode
        fd <- Posix.handleToFd handle
        alias <- duplicate (fromIntegral fd)
        unless (alias >= 0) (die "Native unix dup failed")
        sourceClosed <- close (fromIntegral fd)
        bytes <- PosixBytes.fdRead (Fd (fromIntegral alias)) 1
        terminal <- isatty alias
        aliasClosed <- close alias
        closedAgain <- close alias
        removeFile private
        invalid <- fmap concat $ forM [("unixClose",close),("unixDup",duplicate),("unixIsatty",isatty)] $ \(name,call) ->
          forM ([-1,-2147483648,2147483647,2147483648,4294967295] :: [Int]) $ \input -> do
            result <- call input
            Errno errno <- getErrno
            pure (object ["entry" .= (name :: String),"input" .= input,"result" .= result,"errno" .= toInteger errno])
        Env.setEnv "THC_UNIX_FFI_VALUE" "present-value" True
        Env.setEnv "THC_UNIX_FFI_EMPTY" "" True
        Env.unsetEnv "THC_UNIX_FFI_ABSENT"
        environment <- forM ["THC_UNIX_FFI_VALUE","THC_UNIX_FFI_EMPTY","THC_UNIX_FFI_ABSENT"] $ \name ->
          withCString name $ \address -> do
            result <- getenv address
            contents <- if result == nullPtr then pure Nothing else Just <$> peekCString result
            pure (name,contents)
        pure (object ["lifecycle" .= object ["dupSucceeded" .= (alias >= 0),"closeSource" .= sourceClosed,
          "bytes" .= BS.unpack bytes,"count" .= BS.length bytes,"isatty" .= terminal,"closeAlias" .= aliasClosed,
          "closeAgain" .= closedAgain],"invalid" .= invalid,"environment" .= environment])
      _ -> die "Missing compiled original unix consumers"
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "src/test/resources/core/original-unix-libc-descriptors.json", "t/haskell-fixtures/UnixLibcFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java", "src/main/java/thc/runtime/CoreEnvironmentForeign.java", "src/main/java/thc/runtime/EnvironmentOp.java", "src/main/java/thc/runtime/EnvironmentExpression.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed unix 2.8.8.0 FCallIds" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "unix-libc: 4 original FCallIds, native file/environment observations and 8 strict audits"

#endif
