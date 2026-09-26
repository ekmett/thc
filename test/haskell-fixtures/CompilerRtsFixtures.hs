-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module CompilerRtsFixtures (prepareCompilerRts) where

import Control.Monad (forM, unless)
import qualified Data.ByteString.Char8 as BS
import Data.List (nubBy)
import Data.Aeson (object, (.=))
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
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Interface (loadInterfaceCore, interfaceBindings)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)
import Text.Read (readMaybe)

symbol :: Id -> Maybe String
symbol v = case isFCallId_maybe v of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlayRisky))
    | unitString unit == "ghc-9.14.1-inplace", unpackFS name `elem` targets -> Just (unpackFS name)
  _ -> Nothing
  where targets = ["keepCAFsForGHCi", "getOrSetLibHSghcFastStringTable"]

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

prepareCompilerRts :: FilePath -> IO ()
prepareCompilerRts root = do
  let directory = "build/compiler-rts"
      source = "compiler/test-fixtures/CompilerRtsAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [value] -> value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Compiler RTS fixture requires GHC9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "ghc", "import-dirs", "--simple-output"]
  let interfaces = map (oneLine imports </>) ["GHC.hi", "GHC/Data/FastString.hi"]
      entries = ["originalKeep", "originalFast", "uniqueCells"]
  rows <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "ghc", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == "ghc-9.14.1-inplace")
        (die "Wrong installed compiler interface owner")
      recovered <- loadInterfaceCore env (mi_module raw) path
      actual <- maybe (die "Installed compiler lacks complete Core") pure recovered
      pure [v | (_, body) <- flattenBinds (interfaceBindings actual), v <- variables body, symbol v /= Nothing]
    liftIO $ unless (length originals == 2) (die "Missing genuine compiler RTS declarations")
    file <- guessTarget (root </> source) Nothing Nothing
    setTargets [file]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [ms] -> pure ms; _ -> liftIO (die "Unexpected compiler RTS fixture graph")
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
        select name = case [(v, body) | (v, body) <- bindings, getOccString v == name, isExternalName (varName v)] of
          [pair] -> pair
          _ -> error ("Missing compiler RTS consumer " ++ name)
        specialize name target = case (select name, [v | v <- originals, symbol v == Just target]) of
          ((v, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType v), eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo v vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original compiler FCallId differs from typed consumer " ++ name)
        guests = [specialize "originalKeep" "keepCAFsForGHCi", specialize "originalFast" "getOrSetLibHSghcFastStringTable",
                  select "uniqueCells"] ++ [(v, body) | (v, body) <- bindings, getOccString v `elem` ["counter", "increment"]]
        adapted = optimized { mg_binds = [NonRec v body | (v, body) <- guests],
                              mg_exports = filter (\a -> availName a `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    let native name target = liftIO $ do
          let (_, body) = specialize name target
          (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
          wormhole (hscInterp current) value
    keep <- native "nativeKeep" "keepCAFsForGHCi"
    fast <- native "nativeFast" "getOrSetLibHSghcFastStringTable"
    let functions = [("originalKeep", unsafeCoerce keep :: Int -> Int),
                     ("originalFast", unsafeCoerce fast :: Int -> Int)]
    liftIO $ forM [(name, f, x) | (name, f) <- functions, x <- [-17, 0, 1, 42, 65535]] $ \(name, f, x) -> do
      let result = f x
      unless (result == x + 1) (die "Native original compiler RTS observation differs")
      pure (name ++ "\t" ++ show x ++ "\t" ++ show result)
  writeFile (root </> directory </> "oracle.tsv") (unlines rows)
  -- The address-only consumer is independently native-compiled as ordinary Haskell.
  let oracleSource = directory </> "UniqueOracle.hs"
      nativeBinary = directory </> "unique-oracle"
  writeFile (root </> oracleSource) $ unlines ["module Main where", "import CompilerRtsAudit",
    "main :: IO ()", "main = mapM_ (\\x -> print (x, nativeUnique x)) [-17,0,1,42,65535]"]
  _ <- execute "unique-build" [] ghc ["--make", "-O2", "-dynamic", "-package", "ghc", "-i" ++ (root </> "compiler/test-fixtures"),
    "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc", root </> oracleSource, "-o", root </> nativeBinary]
  unique <- execute "unique-oracle" [] (root </> nativeBinary) []
  unless (lines (BS.unpack (commandStdout unique)) == [show (x, x + 4) | x <- [-17,0,1,42,65535] :: [Int]])
    (die "Native original unique cell observations differ")
  uniqueRows <- forM (lines (BS.unpack (commandStdout unique))) $ \line -> case readMaybe line :: Maybe (Int, Int) of
    Just (input, answer) -> pure ("uniqueCells\t" ++ show input ++ "\t" ++ show answer)
    Nothing -> die "Invalid native unique observation"
  writeFile (root </> directory </> "oracle.tsv") (unlines (rows ++ uniqueRows))
  _ <- forM ["pre", "post"] $ \stage -> forM entries $ \entry ->
    execute (stage ++ "-audit-" ++ entry) [] "python3" ["scripts/audit-core.py", "--entry", entry,
      "--output", directory </> stage ++ "-" ++ entry ++ ".audit.json", directory </> stage ++ ".json"]
  inputHashes <- hashes root [source, "test/haskell-fixtures/CompilerRtsFixtures.hs", "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs",
    "scripts/core_original_foreign.py", "scripts/audit-core.py", "scripts/core-capabilities.json"]
  artifactHashes <- hashes root [directory </> file | file <- ["pre.json", "post.json", "oracle.tsv", "UniqueOracle.hs", "unique-oracle"]]
  interfaceHashes <- hashes root interfaces
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "consumerKind" .= ("typed test consumers specialized with genuine installed compiler FCallIds; original address symbols" :: String),
     "interfaceHashes" .= interfaceHashes, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "compiler-rts: genuine keepCAFs/FastString FCallIds and native unique cells; pre/post strict audits"
