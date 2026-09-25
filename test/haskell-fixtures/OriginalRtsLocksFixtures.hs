-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalRtsLocksFixtures (prepareOriginalRtsLocks) where

import Control.Monad (forM, unless)
import Data.Aeson (Value, eitherDecode, object, (.=))
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as BL
import Data.IORef (newIORef, writeIORef)
import Data.Int (Int64)
import Data.List (isPrefixOf, nubBy, sort, sortOn)
import Data.Word (Word64)
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.Types (CInt)
import qualified System.Posix.Internals as Posix
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins hiding (line)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Runtime.Interpreter (wormhole)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds, typeEnvTyCons)
import GHC.Types.Avail (availName)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)
import Text.Read (readMaybe)

directory, source :: FilePath
directory = "build/original-rts-locks"
source = "compiler/test-fixtures/OriginalRtsLocksAudit.hs"

target :: Id -> Maybe String
target v = case isFCallId_maybe v of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlayRisky))
    | unitString unit == "ghc-internal", unpackFS name `elem` ["lockFile", "unlockFile"] -> Just (unpackFS name)
  _ -> Nothing

variables :: CoreExpr -> [Id]
variables expr = case expr of
  Var v | isId v -> [v]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let binds body -> concatMap (variables . snd) (flattenBinds [binds]) ++ variables body
  Case value _ _ alts -> variables value ++ concat [variables body | Alt _ _ body <- alts]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

-- Native GHC compiles these specialized Core expressions and invokes the actual
-- RTS symbols. The original Id, not a reconstructed ForeignCall, reaches codegen.
prepareOriginalRtsLocks :: FilePath -> Bool -> IO ()
prepareOriginalRtsLocks root requireSupported = do
  createDirectoryIfMissing True (root </> directory)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  let execute = runLogged 180 root (directory </> "logs")
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [line] -> line
        _ -> error "Expected one selected compiler line"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Original RTS locks require GHC9.14.1")
  info <- execute "info" [] ghc ["--info"]
  case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | Just host <- lookup "Host platform" fields, lookup "Target platform" fields == Just host,
                  lookup "target word size" fields == Just "8" -> pure ()
    _ -> die "Original RTS lock proof requires native64 GHC"
  libdir <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let installed = oneLine imports </> "GHC/Internal/IO/FD.hi"
  rows <- runGhc (Just (oneLine libdir)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace installed
      unless (moduleNameString (moduleName (mi_module raw)) == "GHC.Internal.IO.FD" &&
        unitString (moduleUnit (mi_module raw)) == "ghc-internal") (die "Wrong original FD interface owner")
      types <- newIORef emptyTypeEnv
      let owner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (owner : filter (/= owner) domain) $ \other ->
            if other == owner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original RTS lock fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      let declarations = [(v, body) | v <- sortOn getOccString (typeEnvIds (md_types details)),
            nameModule_maybe (varName v) == Just owner,
            Just body <- [maybeUnfoldingTemplate (realIdUnfolding v)],
            any ((/= Nothing) . target) (variables body)]
          calls = nubBy (\a b -> target a == target b) [v | (_,body) <- declarations, v <- variables body, target v /= Nothing]
      unless (length calls == 2 && all (not . isExternalName . varName) calls)
        (die "Expected the two genuine private original lock Ids")
      projection <- serializePostTidyCore flags ["unit-qualified"] owner (typeEnvTyCons (md_types details))
        [NonRec v body | (v,body) <- declarations] emptyIfaceForeign
      value <- either die pure (eitherDecode (BL.fromStrict (BS.pack projection)) :: Either String Value)
      writeJson (root </> directory </> "declarations.json") $ object
        ["originalInterface" .= installed, "completeModule" .= False, "installedArtifactsHashed" .= False,
         "projection" .= value]
      pure calls
    file <- guessTarget (root </> source) Nothing Nothing
    setTargets [file]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [ms] -> pure ms; _ -> liftIO (die "Unexpected fixture graph")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    liftIO $ serializeOptimizedCore flags ["unit-qualified"] optimized >>= writeFile (root </> directory </> "template-pre.json")
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var v | Just body <- lookup v bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        specialize name symbol = case [(v, body) | (v,body) <- bindings,
            getOccString v == name, isExternalName (varName v)] of
          [(v, body)] -> case ([o | o <- originals, target o == Just symbol], splitFunTy_maybe (idType v)) of
            ([original], Just (_,_,formal,_)) | eqType formal (idType original) ->
              let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
                  binder = setIdArity (setIdType (setIdInfo v vanillaIdInfo) (exprType applied)) (exprArity applied)
              in (binder, applied)
            _ -> error ("Original Id does not match consumer's first formal: " ++ name)
          _ -> error ("Missing exact higher-order consumer: " ++ name ++ "; available=" ++ show (map (getOccString . fst) bindings))
        guests = [specialize "originalLock" "lockFile", specialize "originalUnlock" "unlockFile"]
        adapted = optimized { mg_binds = [NonRec v body | (v,body) <- guests],
                              mg_exports = filter (\a -> availName a `elem` map (varName . fst) guests) (mg_exports optimized) }
        actualCalls = [v | (_,body) <- guests, v <- variables body, isFCallId v]
    liftIO $ unless (length actualCalls == 2 && all (`elem` originals) actualCalls)
      (die "Specialized consumer lost original Id membership")
    liftIO $ serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
    (tidied, _) <- liftIO $ hscTidy current adapted
    liftIO $ serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
      (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    let native name symbol = do
          let (_,body) = specialize name symbol
          (value,_,_) <- hscCompileCoreExpr current noSrcSpan body
          wormhole (hscInterp current) value
    liftIO $ do
      lockValue <- native "nativeLock" "lockFile"
      unlockValue <- native "nativeUnlock" "unlockFile"
      let lock = unsafeCoerce lockValue :: Word64 -> Word64 -> Word64 -> CInt -> IO CInt
          unlock = unsafeCoerce unlockValue :: Word64 -> IO CInt
          key = maxBound :: Word64
          other = key - 1
          device = 0x8000000000000000
          inode = maxBound
          operations :: [(String,Bool,Word64,Word64,Word64,CInt)]
          operations = [("unknown",False,key,0,0,0), ("reader",True,key,device,inode,0),
            ("repeat-reader",True,key,device,inode,0), ("second-reader",True,other,device,inode,0),
            ("writer-conflict",True,other-1,device,inode,-1), ("release-one",False,key,0,0,0),
            ("still-locked",True,other-1,device,inode,2), ("release-repeat",False,key,0,0,0),
            ("release-other",False,other,0,0,0), ("writer",True,key,device,inode,-2147483648),
            ("reader-conflict",True,other,device,inode,0), ("writer-repeat",True,key,device,inode,1),
            ("release-writer",False,key,0,0,0), ("release-missing",False,key,0,0,0)]
      forM operations $ \(label,isLock,k,d,i,w) -> do
        _ <- Posix.c_close (-1)
        Errno before <- getErrno
        result <- if isLock then lock k d i w else unlock k
        Errno after <- getErrno
        pure (label, isLock, map (fromIntegral :: Word64 -> Int64) [k,d,i], fromIntegral w :: Int,
          fromIntegral result :: Int, fromIntegral before :: Int, fromIntegral after :: Int)
  writeJson (root </> directory </> "oracle.json") $ object ["rows" .= rows,
    "execution" .= ("GHC-compiled specialized Core invoking original native RTS FCallIds" :: String)]
  audits <- if not requireSupported then pure [] else forM ["pre","post"] $ \stage ->
    forM ["originalLock","originalUnlock"] $ \entry -> do
      let output = directory </> stage ++ "-" ++ entry ++ ".audit.json"
      command <- execute (stage ++ "-" ++ entry) [] "python3"
        ["scripts/audit-core.py", "--entry", entry, "--output", output, directory </> stage ++ ".json"]
      pure (output,command)
  let commands = [version,info,libdir,imports] ++ map snd (concat audits)
      artifacts = map (directory </>) ["oracle.json","declarations.json","template-pre.json","pre.json","post.json"] ++
        map fst (concat audits) ++ concatMap commandArtifacts commands
  compilerFiles <- listDirectory (root </> "compiler/THC")
  scriptFiles <- listDirectory (root </> "scripts")
  inputHashes <- hashes root $ sort $ [source,"test/haskell-fixtures/OriginalRtsLocksFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal",
    "scripts/audit-core.py","scripts/core-capabilities.json"] ++
    ["compiler/THC" </> name | name <- compilerFiles, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scriptFiles, "core_" `isPrefixOf` name, takeExtension name == ".py"]
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object ["schema" .= (1::Int),
    "strictAccepted" .= requireSupported, "originalIdsChecked" .= True, "typeEqualityChecked" .= True,
    "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
    "commands" .= map commandRecord commands]
  putStrLn "original-rts-locks: genuine native RTS observations and original-Id pre/post consumers prepared"
