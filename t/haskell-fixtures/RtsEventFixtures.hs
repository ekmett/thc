-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : RtsEventFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for rts event.
module RtsEventFixtures (prepareRtsEvent) where

import Control.Monad (forM, unless)
import Control.Exception (finally)
import qualified GHC.Conc as Conc
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (nub, nubBy)
import qualified Data.Text as T
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
import System.Environment (lookupEnv, getExecutablePath)
import System.Exit (die, exitSuccess)
import System.FilePath ((</>))
import Text.Read (readMaybe)
import THC.Interface (loadInterfaceCore, interfaceBindings)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

calls :: [(String,String,String)]
calls = [("processors","getNumberOfProcessors","Processors"), ("capabilities","setNumCapabilities","Capabilities"),
         ("siginfo","__hscore_sizeof_siginfo_t","Siginfo"), ("setfd","__hscore_f_setfd","Setfd"),
         ("cloexec","__hscore_fd_cloexec","Cloexec"),
         ("eventThread","getOrSetSystemEventThreadIOManagerThreadStore","Store"),
         ("timerManager","getOrSetSystemTimerThreadEventManagerStore","Store"),
         ("timerThread","getOrSetSystemTimerThreadIOManagerThreadStore","Store")]

descriptorCalls :: [(String,[String],String)]
descriptorCalls =
  [("eventfdCycle", ["eventfd","eventfd_write",readCall,"close"], "EventfdCycle"),
   ("pipeCycle", ["pipe",readCall,writeCall,"close",fcntlCall,"__hscore_f_setfd","__hscore_fd_cloexec"], "PipeCycle"),
   ("epollCycle", ["epoll_create","epoll_ctl","epoll_wait","eventfd","close"], "EpollCycle"),
   ("epollSafeCycle", ["epoll_create","epoll_ctl","epoll_wait/safe","eventfd","close"], "EpollCycle"),
   ("pollCycle", ["poll","eventfd","close"], "PollCycle"),
   ("pollSafeCycle", ["poll/safe","eventfd","close"], "PollCycle"),
   ("controlCycle", ["setIOManagerWakeupFd","setIOManagerControlFd","setTimerManagerControlFd",
      "eventfd","pipe",fcntlCall,"__hscore_f_setfl","__hscore_o_nonblock","close"], "ControlCycle")]
  where
    readCall = "ghczuwrapperZC23ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread"
    writeCall = "ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite"
    fcntlCall = "ghczuwrapperZC16ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl"

targets :: [String]
targets = nub ([s | (_,s,_) <- calls] ++ concat [ss | (_,ss,_) <- descriptorCalls])

symbol :: Id -> Maybe String
symbol v = case isFCallId_maybe v of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) convention safety))
    | let key = unpackFS name ++ if unpackFS name `elem` ["poll","epoll_wait"] && safety == F.PlaySafe then "/safe" else "",
      unitString unit == "ghc-internal", key `elem` targets,
      convention == (if "ghczuwrapper" `T.isPrefixOf` T.pack (unpackFS name) then F.CApiConv else F.CCallConv),
      safety == if unpackFS name `elem` ["setNumCapabilities","__hscore_sizeof_siginfo_t"] || "/safe" `T.isSuffixOf` T.pack key
        then F.PlaySafe else F.PlayRisky -> Just key
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

prepareRtsEvent :: FilePath -> IO ()
prepareRtsEvent root = do
  let directory = "build/rts-event"
      source = "t/fixtures/compiler/RtsEventAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [value] -> value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  executable <- getExecutablePath
  nativeChild <- lookupEnv "THC_RTS_EVENT_NATIVE_CONTROL"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "RTS event fixtures require GHC9.14.1")
  info <- execute "info" [] ghc ["--info"]
  case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8" -> pure ()
    _ -> die "RTS event fixtures require native64 GHC"
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field","ghc-internal","import-dirs","--simple-output"]
  let interfaces = map (oneLine imports </>)
        ["GHC/Internal/Conc/Sync.hi","GHC/Internal/Event/Thread.hi",
         "GHC/Internal/Event/Control.hi","GHC/Internal/Event/EPoll.hi","GHC/Internal/Event/Poll.hi",
         "GHC/Internal/System/Posix/Internals.hi"]
  (rows, descriptorRows, signatures) <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured,_,_) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2","-fno-worker-wrapper","-package","ghc-internal","-fno-external-interpreter","-dcore-lint",
       "-odir",root </> directory </> "ghc","-hidir",root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == "ghc-internal") (die "Wrong original RTS event interface")
      recovered <- loadInterfaceCore env (mi_module raw) path
      actual <- maybe (die "RTS event fixture requires complete installed Core") pure recovered
      pure [v | (_,body) <- flattenBinds (interfaceBindings actual), v <- variables body, symbol v /= Nothing]
    liftIO $ unless (length originals == length targets) (die ("Missing original RTS event declarations: " ++ show (map symbol originals)))
    file <- guessTarget (root </> source) Nothing Nothing
    setTargets [file]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [ms] -> pure ms; _ -> liftIO (die "Unexpected RTS event fixture graph")
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
        specializeMany prefix consumer selected = case
            [(v,body) | (v,body) <- bindings, getOccString v == prefix ++ consumer, isExternalName (varName v)] of
          [(v,body)] ->
            let apply expression target = case ([original | original <- originals, symbol original == Just target],
                                               splitFunTy_maybe (exprType expression)) of
                  ([original],Just (_,_,formal,_)) | eqType formal (idType original) ->
                    simpleOptExpr (initSimpleOpts flags) (App expression (Var original))
                  _ -> error ("Original RTS event FCallId differs from typed consumer " ++ prefix ++ consumer ++ "/" ++ target)
                applied = foldl apply (resolve body) selected
            in (setIdArity (setIdType (setIdInfo v vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Missing original RTS event consumer " ++ prefix ++ consumer)
        specialize prefix (_,target,consumer) = specializeMany prefix consumer [target]
    -- These original setters alter process-global RTS slots. Execute their
    -- native consumer in a disposable producer subprocess, never in this RTS.
    liftIO $ case nativeChild of
      Nothing -> pure ()
      Just inputText -> case readMaybe inputText :: Maybe Int of
        Nothing -> die "Invalid isolated native control input"
        Just input -> do
          let (_, selected, consumer) = last descriptorCalls
              (_, body) = specializeMany "native" consumer selected
          (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
          function <- wormhole (hscInterp current) value
          result <- (unsafeCoerce function :: Int -> IO Int) input
          print result
          exitSuccess
    -- Separate exports avoid assigning a shared consumer binder to three RTS slots.
    _ <- liftIO $ forM ([(entry,[target],consumer) | (entry,target,consumer) <- calls] ++ descriptorCalls) $ \(entry,selected,consumer) -> do
      let (v,body) = specializeMany "original" consumer selected
          adapted = optimized { mg_binds = [NonRec v body], mg_exports = filter ((== varName v) . availName) (mg_exports optimized) }
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> entry ++ "-pre.json")
      (tidied,_) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> entry ++ "-post.json")
    observations <- forM calls $ \call@(entry,_,_) -> liftIO $ do
      let (_,body) = specialize "native" call
      (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      function <- wormhole (hscInterp current) value
      let invoke = unsafeCoerce function :: Int -> IO Int
      forM [1,2,4] $ \input -> do
        -- Observe real native capability updates, restoring the producer RTS.
        previous <- Conc.getNumCapabilities
        result <- (do
          answer <- invoke input
          actual <- Conc.getNumCapabilities
          unless (entry /= "capabilities" || actual == input) (die "Native RTS did not change its capability count")
          pure answer) `finally` Conc.setNumCapabilities previous
        unless (if entry == "capabilities" then result == input else result > 0)
          (die ("Unexpected native RTS event result: " ++ show (entry,input,result)))
        pure (entry,input,result)
    descriptorObservations <- forM descriptorCalls $ \(entry,selected,consumer) -> liftIO $ do
      let (_,body) = specializeMany "native" consumer selected
      (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      function <- wormhole (hscInterp current) value
      let invoke = unsafeCoerce function :: Int -> IO Int
      forM [1,2,4] $ \input -> do
        result <- if entry == "controlCycle" then do
          observed <- execute (entry ++ "-native-" ++ show input)
            [("THC_RTS_EVENT_NATIVE_CONTROL",show input)] executable ["rts-event"]
          maybe (die "Invalid isolated native control result") pure (readMaybe (oneLine observed))
          else invoke input
        let expected = case consumer of
              "EventfdCycle" -> 2 * input + 5
              "PipeCycle" -> input + 13
              "EpollCycle" -> input + 31
              "PollCycle" -> input + 17
              "ControlCycle" -> input
              _ -> error "Unknown descriptor consumer"
        unless (result == expected)
          (die ("Unexpected native descriptor lifecycle result: " ++ show (entry,input,result)))
        pure (entry,input,result)
    pure (concat observations, concat descriptorObservations,
      [(target,showSDoc flags (ppr (idType v))) | v <- originals, Just target <- [symbol v]])
  writeJson (root </> directory </> "oracle.json") $ object ["rows" .= rows,"descriptorRows" .= descriptorRows]
  let entries = [(entry,"original" ++ consumer) | (entry,_,consumer) <- calls]
      descriptorEntries = [(entry,"original" ++ consumer) | (entry,_,consumer) <- descriptorCalls]
      allEntries = entries ++ descriptorEntries
  _ <- forM allEntries $ \(entry,binder) -> forM ["pre","post"] $ \stage ->
    execute (entry ++ "-" ++ stage) [] "python3" ["bin/audit-core.py","--entry",binder,
      "--output",directory </> entry ++ "-" ++ stage ++ ".audit.json",directory </> entry ++ "-" ++ stage ++ ".json"]
  inputHashes <- hashes root [source,"t/haskell-fixtures/RtsEventFixtures.hs","src/compiler/THC/Plugin.hs",
    "src/compiler/THC/Interface.hs","bin/core_original_foreign.py","bin/audit-core.py","bin/core-capabilities.json"]
  artifactHashes <- hashes root ([directory </> "oracle.json"] ++
    [directory </> entry ++ "-" ++ stage ++ extension | (entry,_) <- allEntries, stage <- ["pre","post"],
      extension <- [".json",".audit.json"]])
  interfaceHashes <- hashes root interfaces
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"entries" .= entries,"descriptorEntries" .= descriptorEntries,
     "signatures" .= signatures,"interfaceHashes" .= interfaceHashes,"inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes]
  putStrLn "rts-event: eight RTS prerequisites and seven descriptor/control lifecycles, 45 native rows, 30 pre/post strict audits"
