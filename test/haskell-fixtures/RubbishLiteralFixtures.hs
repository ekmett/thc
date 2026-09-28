-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : RubbishLiteralFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for rubbish literal.
module RubbishLiteralFixtures (prepareRubbishLiterals) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.IORef (newIORef, writeIORef)
import Data.List (isPrefixOf, nub, sort, sortOn)
import FixtureSupport
import GHC hiding (exprType)
import GHC.Plugins hiding (line)
import GHC.Builtin.Types.Prim (intPrimTy)
import GHC.Core.Lint (lintExpr)
import GHC.Data.Bag (bagToList)
import GHC.Driver.Config.Core.Lint (initLintConfig)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Runtime.Interpreter (wormhole)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.RepType (primRepToType, runtimeRepPrimRep_maybe)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Text.Read (readMaybe)
import Unsafe.Coerce (unsafeCoerce)

directory, source :: FilePath
directory = "build/rubbish-literals"
source = "test/fixtures/compiler/RubbishLiteralAudit.hs"

rubbish :: CoreExpr -> [Literal]
rubbish expression = case expression of
  Lit l@LitRubbish{} -> [l]
  App f x -> rubbish f ++ rubbish x
  Lam _ body -> rubbish body
  Let binds body -> concatMap (rubbish . snd) (flattenBinds [binds]) ++ rubbish body
  Case value _ _ alts -> rubbish value ++ concat [rubbish body | Alt _ _ body <- alts]
  Cast body _ -> rubbish body
  Tick _ body -> rubbish body
  _ -> []

representation :: Literal -> PrimRep
representation (LitRubbish _ rep) | Just [r] <- runtimeRepPrimRep_maybe rep = r
representation _ = error "Expected one original scalar rubbish representation"

-- This is not an invented JSON proof: the first four literals are extracted
-- unchanged from installed GHC interfaces. The wider matrix uses GHC's own
-- mkLitRubbish at closed scalar types. Native GHC compiles every case; its
-- observable oracle is the continuation, never an unspecified filler payload.
prepareRubbishLiterals :: FilePath -> IO ()
prepareRubbishLiterals root = do
  createDirectoryIfMissing True (root </> directory)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  let execute = runLogged 180 root (directory </> "logs")
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [line] -> line
        _ -> error "Expected one selected compiler line"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Rubbish literal fixtures require GHC9.14.1")
  info <- execute "info" [] ghc ["--info"]
  case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | Just host <- lookup "Host platform" fields, lookup "Target platform" fields == Just host,
                  lookup "target word size" fields == Just "8" -> pure ()
    _ -> die "Rubbish literal native oracle requires native64 GHC"
  libdir <- execute "libdir" [] ghc ["--print-libdir"]
  locations <- forM [("ghc-internal","GHC/Internal/Event/Manager.hi")] $ \(package,path) -> do
    result <- execute ("imports-" ++ package) [] pkg ["field",package,"import-dirs","--simple-output"]
    pure (oneLine result </> path, result)
  (entries, originals, rows) <- runGhc (Just (oneLine libdir)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "ghc", "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    original <- liftIO $ fmap concat $ forM (map fst locations) $ \installed -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace installed
      let owner = mi_module raw
          expected = [("GHC.Internal.Event.Manager","ghc-internal")]
      unless ((moduleNameString (moduleName owner),unitString (moduleUnit owner)) `elem` expected)
        (die "Wrong original rubbish interface owner")
      types <- newIORef emptyTypeEnv
      let old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (owner : filter (/= owner) domain) $ \other ->
            if other == owner then Just types else lookupKnotVars old other
      details <- initIfaceCheck (text "Original rubbish literals") (env { hsc_type_env_vars = knots }) (typecheckIface raw)
      writeIORef types (md_types details)
      pure [(moduleNameString (moduleName owner) ++ "." ++ getOccString v,l) |
        v <- sortOn getOccString (typeEnvIds (md_types details)), nameModule_maybe (varName v) == Just owner,
        Just body <- [maybeUnfoldingTemplate (realIdUnfolding v)], l <- rubbish body]
    liftIO $ unless (sort (nub (map (show . representation . snd) original)) ==
      sort ["BoxedRep (Just Lifted)","BoxedRep (Just Unlifted)","IntRep","Int32Rep"])
      (die ("Original rubbish representation inventory changed: " ++ show (map (show . representation . snd) original)))
    file <- guessTarget (root </> source) Nothing Nothing
    setTargets [file]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [ms] -> pure ms; _ -> liftIO (die "Unexpected rubbish fixture graph")
    checked <- parseModule summary >>= typecheckModule >>= desugarModule
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule checked)
    let template name = case [body | (v,body) <- flattenBinds (mg_binds optimized), getOccString v == name] of
          [body] -> body
          _ -> error ("Missing rubbish template: " ++ name)
        scalars = [IntRep,Int8Rep,Int16Rep,Int32Rep,Int64Rep,WordRep,Word8Rep,Word16Rep,Word32Rep,Word64Rep,
          FloatRep,DoubleRep,AddrRep,BoxedRep (Just Lifted),BoxedRep (Just Unlifted)]
        label r = case r of BoxedRep (Just Lifted) -> "Lifted"; BoxedRep (Just Unlifted) -> "Unlifted"; _ -> show r
        fromGhc name ty = case mkLitRubbish ty of Just value -> (name,value); _ -> error "GHC rejected closed rubbish type"
        firstOriginal rep = case [(owner,l) | (owner,l) <- original, representation l == rep] of
          first:_ -> first
          [] -> error "Missing already-checked original representation"
        selected = [firstOriginal rep |
          rep <- [BoxedRep (Just Lifted),BoxedRep (Just Unlifted),IntRep,Int32Rep]]
        samples = [("original" ++ label (representation l), App (Lit l) (Type (primRepToType (representation l)))) | (_,l) <- selected] ++
          [fromGhc ("scalar" ++ label r) (primRepToType r) | r <- scalars] ++
          [fromGhc "boxedData" intTy, fromGhc "boxedClosure" (mkVisFunTyMany intTy intTy)]
        wrap value expression = let (args,body) = collectBinders expression in
          mkLams args (Case value (mkWildValBinder ManyTy (exprType value)) (exprType body) [Alt DEFAULT [] body])
        seeds = [-1000,-17,-1,0,1,42,1000] :: [Int]
        makeBinding (name,value) = do
          unique <- uniqFromSupply <$> mkSplitUniqSupply 'r'
          let body = wrap value (template "template")
              binder = setIdArity (mkVanillaGlobal (mkExternalName unique (mg_module optimized) (mkVarOcc name) noSrcSpan) (exprType body)) 1
          case lintExpr (initLintConfig flags []) body of
            Nothing -> pure ()
            Just errors -> die (showSDoc flags (vcat (bagToList errors)))
          pure (binder,body)
    guests <- liftIO $ mapM makeBinding samples
    frontiers <- liftIO $ mapM makeBinding
      [fromGhc "emptyTuple" (mkTupleTy Unboxed []), fromGhc "singletonTuple" (mkTupleTy Unboxed [intPrimTy]),
       fromGhc "sum" (mkSumTy [intPrimTy,intPrimTy]), fromGhc "vector" (primRepToType (VecRep 4 Int32ElemRep))]
    liftIO $ serializeOptimizedCore flags ["unit-qualified"]
      (optimized { mg_binds = [NonRec v body | (v,body) <- frontiers], mg_exports = [] }) >>=
      writeFile (root </> directory </> "frontiers.json")
    let adapted = optimized { mg_binds = [NonRec v body | (v,body) <- guests], mg_exports = [] }
    liftIO $ serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
    (tidied, _) <- liftIO $ hscTidy current adapted
    liftIO $ serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
      (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    observations <- liftIO $ fmap concat $ forM samples $ \(name,value) -> do
      let body = wrap value (template "nativeTemplate")
      case lintExpr (initLintConfig flags []) body of
        Nothing -> pure ()
        Just errors -> die (showSDoc flags (vcat (bagToList errors)))
      (compiled,_,_) <- hscCompileCoreExpr current noSrcSpan body
      native <- wormhole (hscInterp current) compiled
      let function = unsafeCoerce native :: Int -> Int
      forM seeds $ \seed -> do
        let result = function seed
        unless (result == seed + 17) (die "GHC rubbish unexpectedly changed the continuation")
        pure (name,seed,result)
    pure (map fst samples,[(owner,show (representation l)) | (owner,l) <- original],observations)
  writeJson (root </> directory </> "oracle.json") $ object ["rows" .= rows]
  writeJson (root </> directory </> "originals.json") $ object ["occurrences" .= originals,
    "scope" .= ("Rubbish literals in retained unfoldings of installed Event.Manager, not a full-module execution" :: String)]
  audits <- forM ["pre","post"] $ \stage -> do
    let output = directory </> stage ++ ".audit.json"
    result <- execute (stage ++ "-audit") [] "python3" $ ["bin/audit-core.py", "--output", output] ++
      concat [["--entry",name] | name <- entries] ++ [directory </> stage ++ ".json"]
    pure (output,result)
  frontierAudit <- runLoggedExpect 1 180 root (directory </> "logs") "frontiers-audit" [] "python3"
    (["bin/audit-core.py", "--output", directory </> "frontiers.audit.json"] ++
      concat [["--entry",name] | name <- ["emptyTuple","singletonTuple","sum","vector"]] ++ [directory </> "frontiers.json"])
  compilerFiles <- listDirectory (root </> "src/compiler/THC")
  scriptFiles <- listDirectory (root </> "bin")
  inputHashes <- hashes root $ sort $ [source,"test/haskell-fixtures/RubbishLiteralFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal",
    "bin/audit-core.py","bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> name | name <- compilerFiles, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scriptFiles, "core_" `isPrefixOf` name, takeExtension name == ".py"]
  installedInterfaces <- forM (map fst locations) $ \path -> do
    digest <- hashFile path
    pure (object ["path" .= path, "sha256" .= digest])
  let commands = [version,info,libdir] ++ map snd locations ++ map snd audits ++ [frontierAudit]
  artifactHashes <- hashes root $ map (directory </>) ["pre.json","post.json","oracle.json","originals.json","frontiers.json","frontiers.audit.json"] ++
    map fst audits ++ concatMap commandArtifacts commands
  writeJson (root </> directory </> "manifest.json") $ object ["schema" .= (1::Int), "entries" .= entries,
    "nativeRows" .= length rows, "originalOccurrences" .= length originals, "inputHashes" .= inputHashes,
    "installedInterfaces" .= installedInterfaces, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "rubbish-literals: original installed literals and closed scalar GHC-native continuation matrix prepared"
