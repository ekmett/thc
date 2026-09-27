-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module ProcessLifecycleFixtures (prepareProcessLifecycle, main) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (nubBy, stripPrefix)
import FixtureSupport
import GHC hiding (exprType)
import GHC.Plugins hiding ((<>))
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy)
import GHC.Iface.Binary
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import System.Directory (createDirectoryIfMissing, getCurrentDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import qualified THC.Interface as Interface
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)

operations :: [(String, String, F.Safety)]
operations = [("processCreate", "runInteractiveProcess", F.PlayRisky),
  ("processPoll", "getProcessExitCode", F.PlayRisky),
  ("processWait", "waitForProcess", F.PlayInterruptible),
  ("processTerminate", "terminateProcess", F.PlayRisky)]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ symbolName (Just unit) True) F.CCallConv safety))
    | unitString unit == owner,
      any (\(_, symbol, expected) -> unpackFS symbolName == symbol && safety == expected) operations ->
        Just (unpackFS symbolName)
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

prepareProcessLifecycle :: FilePath -> IO ()
prepareProcessLifecycle root = do
  let directory = "build/process-lifecycle/core"
      source = "compiler/test-fixtures/ProcessLifecycleAudit.hs"
      execute = runLogged 120 root (directory </> "logs")
      oneLine result = case BS.lines (commandStdout result) of
        [value] -> BS.unpack value
        _ -> error "Expected one compiler configuration line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Process fixtures require GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  owner <- execute "unit" [] pkg ["field", "process", "id", "--simple-output"]
  case stripPrefix "process-1.6.26.1-" (oneLine owner) of
    Just suffix | suffix == "inplace" || not (null suffix) && all (`elem` ("0123456789abcdef" :: String)) suffix -> pure ()
    _ -> die "Wrong process package owner"
  imports <- execute "imports" [] pkg ["field", "process", "import-dirs", "--simple-output"]
  let interfaces = [oneLine imports </> name | name <- ["System/Process.hi", "System/Process/Posix.hi"]]
      entries = [name | (name, _, _) <- operations]
  runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package-id", oneLine owner, "-package", "ghc-internal", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    let symbol = originalSymbol (oneLine owner)
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == oneLine owner) (die "Wrong process interface owner")
      complete <- Interface.loadInterfaceCore env (mi_module raw) path >>= maybe
        (die "Process fixture requires genuine retained installed Core") pure
      pure [value | (_, body) <- flattenBinds (Interface.interfaceBindings complete),
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing original process FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected process consumer module graph")
    desugared <- parseModule summary >>= typecheckModule >>= desugarModule
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var value | Just body <- lookup value bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        specialize name targetName = case ([(value, body) | (value, body) <- bindings,
            getOccString value == name, isExternalName (varName value)], [value | value <- originals, symbol value == Just targetName]) of
          ([(value, body)], [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                       eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original process FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName, _) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
  inputHashes <- hashes root [source, "test/haskell-fixtures/ProcessLifecycleFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs"]
  interfaceHashes <- hashes root interfaces
  let commands = [version, library, owner, imports]
  artifactHashes <- hashes root ([directory </> stage ++ ".json" | stage <- ["pre", "post"]] ++ concatMap commandArtifacts commands)
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "processUnit" .= oneLine owner,
     "entries" .= entries, "consumerKind" .= ("typed consumers of genuine installed process FCallIds" :: String),
     "inputHashes" .= inputHashes, "interfaceHashes" .= interfaceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]

-- Also permits focused standalone preparation before shared dispatcher registration.
main :: IO ()
main = getCurrentDirectory >>= prepareProcessLifecycle
