-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module GcStatsFixtures (prepareGcStats) where

import Control.Monad (forM, forM_, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (nubBy)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins hiding (line)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.Runtime.Interpreter (wormhole)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)
import THC.Interface (loadInterfaceCore, interfaceBindings)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

calls :: [(String,String,String)]
calls = [("enabled","getRTSStatsEnabled","Enabled"), ("stats","getRTSStats","Stats"),
         ("minor","performGC","Collect"), ("major","performMajorGC","Collect"),
         ("blocking","performBlockingMajorGC","Collect"), ("clock","getMonotonicNSec","Clock")]

symbol :: Id -> Maybe String
symbol v = case isFCallId_maybe v of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv safety))
    | unitString unit == "ghc-internal", unpackFS name `elem` [s | (_,s,_) <- calls],
      safety == if unpackFS name == "getMonotonicNSec" then F.PlayRisky else F.PlaySafe -> Just (unpackFS name)
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

prepareGcStats :: FilePath -> IO ()
prepareGcStats root = do
  let directory = "build/gc-stats"
      source = "compiler/test-fixtures/GcStatsAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [value] -> value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "GC/stats fixtures require GHC9.14.1")
  info <- execute "info" [] ghc ["--info"]
  case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8" -> pure ()
    _ -> die "GC/stats fixtures require native64 GHC"
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field","ghc-internal","import-dirs","--simple-output"]
  let interfaces = map (oneLine imports </>)
        ["GHC/Internal/Stats.hi","GHC/Internal/System/Mem.hi","GHC/Internal/Clock.hi"]
  (rows, signatures) <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured,_,_) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2","-package","ghc-internal","-fno-external-interpreter","-dcore-lint",
       "-odir",root </> directory </> "ghc","-hidir",root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == "ghc-internal") (die "Wrong original GC/stats interface")
      recovered <- loadInterfaceCore env (mi_module raw) path
      actual <- maybe (die "GC/stats fixture requires complete installed Core") pure recovered
      pure [v | (_,body) <- flattenBinds (interfaceBindings actual), v <- variables body, symbol v /= Nothing]
    liftIO $ unless (length originals == length calls) (die ("Missing original GC/stats declarations: " ++ show (map symbol originals)))
    file <- guessTarget (root </> source) Nothing Nothing
    setTargets [file]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [ms] -> pure ms; _ -> liftIO (die "Unexpected GC/stats fixture graph")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var v | Just body <- lookup v bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        specialize prefix (_,target,consumer) = case
            ([(v,body) | (v,body) <- bindings, getOccString v == prefix ++ consumer, isExternalName (varName v)],
             [v | v <- originals, symbol v == Just target]) of
          ([(v,body)],[original]) | Just (_,_,formal,_) <- splitFunTy_maybe (idType v), eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo v vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original GC/stats FCallId differs from typed consumer " ++ prefix ++ consumer ++ "/" ++ target)
    -- Separate exports avoid assigning a shared consumer binder to three GC bodies.
    liftIO $ forM_ calls $ \call@(entry,_,_) -> do
      let (v,body) = specialize "original" call
          adapted = optimized { mg_binds = [NonRec v body], mg_exports = filter ((== varName v) . availName) (mg_exports optimized) }
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> entry ++ "-pre.json")
      (tidied,_) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> entry ++ "-post.json")
    observations <- forM [c | c@(_,_,consumer) <- calls, consumer /= "Stats"] $ \call@(entry,_,_) -> liftIO $ do
      let (_,body) = specialize "native" call
      (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      function <- wormhole (hscInterp current) value
      let invoke = unsafeCoerce function :: Int -> IO Int
      forM [-7,0,42] $ \input -> do
        result <- invoke input
        unless (result == input + if entry == "enabled" then 0 else 1)
          (die ("Unexpected native GC/stats result (stats must be disabled): " ++ show (entry,input,result)))
        pure (entry,input,result)
    pure (concat observations, [(target,showSDoc flags (ppr (idType v))) | v <- originals, Just target <- [symbol v]])
  writeJson (root </> directory </> "oracle.json") $ object ["rows" .= rows]
  let entries = [(entry,"original" ++ consumer) | (entry,_,consumer) <- calls]
  _ <- forM entries $ \(entry,binder) -> forM ["pre","post"] $ \stage ->
    execute (entry ++ "-" ++ stage) [] "python3" ["scripts/audit-core.py","--entry",binder,
      "--output",directory </> entry ++ "-" ++ stage ++ ".audit.json",directory </> entry ++ "-" ++ stage ++ ".json"]
  inputHashes <- hashes root [source,"test/haskell-fixtures/GcStatsFixtures.hs","compiler/THC/Plugin.hs",
    "compiler/THC/Interface.hs","scripts/core_original_foreign.py","scripts/audit-core.py","scripts/core-capabilities.json"]
  artifactHashes <- hashes root ([directory </> "oracle.json"] ++
    [directory </> entry ++ "-" ++ stage ++ ".json" | (entry,_) <- entries, stage <- ["pre","post"]])
  interfaceHashes <- hashes root interfaces
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"entries" .= entries,
     "signatures" .= signatures,"interfaceHashes" .= interfaceHashes,"inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes,"statsEnabled" .= False]
  putStrLn "gc-stats: six genuine original declarations, 15 native rows, pre/post strict audits"
