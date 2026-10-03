-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (043 rubbish-literals)
-- Purpose: Check authentic GHC rubbish literals are handled with their representation
--   semantics.
-- Produces/consumed result: Installed literal inventory, CBDs and native oracle.json.
-- Cost and overlap: Keep a small original-Core boundary check if synthetic models miss it.
--   Native object/disassembly acquisition must serve this contract, not a frozen lowering
--   shape.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 043.
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
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
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
import GHC.Driver.Main (hscSimplify, hscTidy, hscGenHardCode)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Linker.Loader (initLoaderState)
import GHC.Platform (Arch(..), platformArch)
import GHC.Platform.Ways (hostFullWays, hostIsDynamic, hostIsProfiled)
import GHC.Runtime.Interpreter (loadObj, lookupClosure, mkFinalizedHValue, resolveObjs, wormhole)
import GHC.Runtime.Interpreter.Types.SymbolCache (InterpSymbol(..))
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.RepType (primRepToType, runtimeRepPrimRep_maybe)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (proc, readCreateProcessWithExitCode, cwd)
import System.Timeout (timeout)
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import THC.Compact.Module (readModuleValue)
import Text.Read (readMaybe)
import Unsafe.Coerce (unsafeCoerce)

directory, source :: FilePath
directory = "build/rubbish-literals"
source = "t/fixtures/compiler/RubbishLiteralAudit.hs"

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

-- Original literals are extracted unchanged from installed GHC interfaces.
-- The wider matrix uses GHC's own
-- mkLitRubbish at closed scalar, aggregate and vector types. Native GHC compiles every case; its
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
  imports <- execute "imports-ghc-internal" [] pkg ["field","ghc-internal","import-dirs","--simple-output"]
  let installedPath = oneLine imports </> "GHC/Internal/Event/Manager.hi"
  (entries, producers, returns, originals, rows, nativeCommands, nativeLLVM) <- runGhc (Just (oneLine libdir)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "ghc", "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    original <- liftIO $ do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace installedPath
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
        scalar ty = primRepToType ty
        nestedTuple = mkTupleTy Unboxed [intPrimTy, mkTupleTy Unboxed [scalar WordRep,scalar DoubleRep],
          mkSumTy [intPrimTy,scalar FloatRep]]
        nestedSum = mkSumTy [mkTupleTy Unboxed [scalar Int8Rep,scalar DoubleRep],
          mkSumTy [scalar WordRep,mkTupleTy Unboxed [scalar FloatRep,scalar AddrRep]]]
        shapes = [fromGhc "shapeEmptyTuple" (mkTupleTy Unboxed []),
          fromGhc "shapeSingletonTuple" (mkTupleTy Unboxed [intPrimTy]),
          fromGhc "shapeSum" (mkSumTy [intPrimTy,intPrimTy]),
          fromGhc "shapeVector" (scalar (VecRep 4 Int32ElemRep)),
          fromGhc "shapeNestedTuple" nestedTuple, fromGhc "shapeNestedSum" nestedSum]
        samples = [("original" ++ label (representation l), App (Lit l) (Type (primRepToType (representation l)))) | (_,l) <- selected] ++
          [fromGhc ("scalar" ++ label r) (primRepToType r) | r <- scalars] ++
          [fromGhc "boxedData" intTy, fromGhc "boxedClosure" (mkVisFunTyMany intTy intTy)] ++ shapes
        wrap value expression = let (args,body) = collectBinders expression in
          mkLams args (Case value (mkWildValBinder ManyTy (exprType value)) (exprType body) [Alt DEFAULT [] body])
        seeds = [-1000,-17,-1,0,1,42,1000] :: [Int]
        check body = case lintExpr (initLintConfig flags []) body of
          Nothing -> pure ()
          Just errors -> die (showSDoc flags (vcat (bagToList errors)))
        local name ty = do
          unique <- uniqFromSupply <$> mkSplitUniqSupply 'r'
          pure (mkLocalId (mkInternalName unique (mkVarOcc name) noSrcSpan) ManyTy ty)
        namedBinding name body = do
          unique <- uniqFromSupply <$> mkSplitUniqSupply 'r'
          let binder = setIdArity (mkVanillaGlobal (mkExternalName unique (mg_module optimized) (mkVarOcc name) noSrcSpan) (exprType body)) 1
          check body
          pure (binder,body)
        makeBinding (name,value) = namedBinding name (wrap value (template "template"))
        makeProducer (name,value) = do
          argument <- local "rubbishInput" intPrimTy
          (binder,body) <- namedBinding (name ++ "Producer") (Lam argument value)
          pure (setInlinePragma binder neverInlinePragma,body)
        -- Reuse the whole result binder in a second DEFAULT case. In particular,
        -- neither aggregate is flattened into an ad-hoc scalar continuation.
        returnBody producer value = case collectBinders (template "template") of
          ([argument],body) -> do
            result <- local "rubbishResult" (exprType value)
            reused <- local "rubbishReused" (exprType value)
            pure (Lam argument (Case (App (Var producer) (Var argument)) result (exprType body)
              [Alt DEFAULT [] (Case (Var result) reused (exprType body) [Alt DEFAULT [] body])]))
          _ -> die "Unexpected unboxed rubbish continuation template"
    guests <- liftIO $ mapM makeBinding samples
    shapeProducers <- liftIO $ mapM makeProducer shapes
    shapeReturns <- liftIO $ forM (zip shapes shapeProducers) $ \((name,value),(producer,_)) ->
      returnBody producer value >>= namedBinding (name ++ "Return")
    let adapted = optimized { mg_binds = [NonRec v body | (v,body) <- guests ++ shapeProducers ++ shapeReturns], mg_exports = [] }
    liftIO $ serializeOptimizedCoreCBD flags ["unit-qualified"] adapted >>= BS.writeFile (root </> directory </> "pre.cbd")
    (tidied, _) <- liftIO $ hscTidy current adapted
    liftIO $ serializePostTidyCoreCBD flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
      (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> directory </> "post.cbd")
    nativeReturns <- liftIO $ forM (zip shapes shapeProducers) $ \((name,value),(producer,producerBody)) -> do
      binder <- local (name ++ "NativeProducer") (varType producer)
      let nativeProducer = setInlinePragma (setIdArity binder 1) neverInlinePragma
      body <- returnBody nativeProducer value
      -- GHC's unariser splits aggregate rubbish only in argument and case
      -- positions, not a bare function return. Keep the exact literal behind
      -- an identity DEFAULT case for this native oracle; the JVM fixture above
      -- deliberately retains its bare aggregate producer return.
      nativeProducerBody <- case producerBody of
        Lam argument originalValue -> do
          whole <- local "nativeRubbishWhole" (exprType originalValue)
          pure (Lam argument (Case originalValue whole (exprType originalValue) [Alt DEFAULT [] (Var whole)]))
        _ -> die "Unexpected native rubbish producer body"
      pure (name ++ "Return", Let (NonRec nativeProducer nativeProducerBody) (App (template "nativeReturnTemplate") body))
    let nativeEntries = [(name,wrap value (template "nativeTemplate")) | (name,value) <- samples] ++ nativeReturns
    -- The interactive expression compiler always generates bytecode, which
    -- cannot carry zero-register or vector rubbish. Compile the genuine typed
    -- literals together through the native backend and its ordinary unarisation.
    nativeBindings <- liftIO $ forM nativeEntries $ \(name,body) -> namedBinding (name ++ "Native") body
    -- The AArch64 NCG cannot materialise vector literals. Keep the full
    -- matrix and use the selected GHC native LLVM pipeline on that target.
    let llvm = platformArch (targetPlatform flags) == ArchAArch64
        wayFlags = [if hostIsDynamic then "-dynamic" else "-static"] ++ ["-prof" | hostIsProfiled]
    (nativeFlags,_,_) <- parseDynamicFlags (hsc_logger current) (flags {targetWays_ = hostFullWays})
      (map noLoc ([if llvm then "-fllvm" else "-fasm", "-fno-info-table-map"] ++ wayFlags))
    let nativeEnv = hscSetFlags nativeFlags current
        -- Retain only source workers actually referenced by the copied native
        -- templates. exprFreeIds excludes globals, so include all free Ids here.
        helpers needed =
          let required = [bind | bind <- mg_binds optimized, any (`elemVarSet` needed) (bindersOf bind)]
              closed = needed `unionVarSet` exprsSomeFreeVars isId (map snd (flattenBinds required))
          in if sizeVarSet closed == sizeVarSet needed then required else helpers closed
        nativeModule = optimized { mg_binds = helpers (exprsSomeFreeVars isId (map snd nativeBindings)) ++
          [NonRec v body | (v,body) <- nativeBindings], mg_exports = [] }
        nativeOutput = directory </> if llvm then "native.ll" else "native.s"
        assembly = directory </> "native.s"
        objectFile = directory </> "native.o"
    (nativeGuts,_) <- liftIO $ hscTidy nativeEnv nativeModule
    let nativeBinder name = case [v | (v,_) <- flattenBinds (cg_binds nativeGuts), getOccString v == name ++ "Native", isExportedId v, isExternalName (varName v)] of
          [v] -> v
          _ -> error ("Missing exported native rubbish entry: " ++ name)
    (emitted,stub,foreignFiles,_,_) <- liftIO $ hscGenHardCode nativeEnv nativeGuts (ms_location summary) (root </> nativeOutput)
    liftIO $ unless (emitted == root </> nativeOutput && stub == Nothing && null foreignFiles)
      (die "Unexpected native rubbish code-generation outputs")
    llvmCommands <- liftIO $ if llvm then
      (:[]) <$> execute "native-llvm" [] ghc (wayFlags ++ ["-fllvm","-O2","-S",nativeOutput,"-o",assembly])
      else pure []
    llvmHash <- liftIO $ if llvm then Just <$> hashFile (root </> nativeOutput) else pure Nothing
    assemblyHash <- liftIO $ hashFile (root </> assembly)
    liftIO $ writeJson (root </> directory </> "native-codegen.json") $ object
      (["backend" .= (if llvm then "llvm" else "native" :: String), "assembly" .= assembly, "sha256" .= assemblyHash,
       "unit" .= unitString (moduleUnit (cg_module nativeGuts)), "module" .= moduleNameString (moduleName (cg_module nativeGuts)),
       "wayFlags" .= wayFlags, "entries" .= [(name,getOccString (nativeBinder name)) | (name,_) <- nativeEntries]] ++
       ["llvmIR" .= object ["path" .= nativeOutput, "sha256" .= digest] | Just digest <- [llvmHash]])
    assembled <- liftIO $ execute "native-assemble" [] ghc (wayFlags ++ ["-c",assembly,"-o",objectFile])
    let interpreter = hscInterp nativeEnv
    liftIO $ do
      initLoaderState interpreter nativeEnv
      loadObj interpreter (root </> objectFile)
      linked <- resolveObjs interpreter
      case linked of Succeeded -> pure (); Failed -> die "Native rubbish object failed to resolve"
    -- Keep the native object loaded for the producer process lifetime. Releasing
    -- each HValue reference must not unload code still referenced by closures.
    observations <- liftIO $ fmap concat $ forM nativeEntries $ \(name,_) -> do
      reference <- lookupClosure interpreter (IClosureSymbol (varName (nativeBinder name))) >>=
        maybe (die ("Native rubbish closure not found: " ++ name)) pure
      compiled <- mkFinalizedHValue interpreter reference
      native <- wormhole interpreter compiled
      let function = unsafeCoerce native :: Int -> Int
      forM seeds $ \seed -> do
        let result = function seed
        unless (result == seed + 17) (die "GHC rubbish unexpectedly changed the continuation")
        pure (name,seed,result)
    pure (map fst samples,map (getOccString . fst) shapeProducers,map (getOccString . fst) shapeReturns,
      [(owner,show (representation l)) | (owner,l) <- original],observations,llvmCommands ++ [assembled],llvm)
  writeJson (root </> directory </> "oracle.json") $ object ["rows" .= rows]
  writeJson (root </> directory </> "originals.json") $ object ["occurrences" .= originals,
    "scope" .= ("Original installed rubbish literals; native observations execute their unchanged fillers in closed continuations" :: String)]
  audits <- forM ["pre","post"] $ \stage -> do
    let output = directory </> stage ++ ".audit.json"
    result <- auditCBD root 0 (stage ++ "-audit") (directory </> stage ++ ".cbd") output (entries ++ returns)
    pure (output,result)
  compilerFiles <- listDirectory (root </> "src/compiler/THC")
  scriptFiles <- listDirectory (root </> "bin")
  inputHashes <- hashes root $ sort $ [source,"t/haskell-fixtures/RubbishLiteralFixtures.hs",
    "cabal.project",
    "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs","thc.cabal",
    "bin/audit-core.py","bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> name | name <- compilerFiles, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scriptFiles, "core_" `isPrefixOf` name, takeExtension name == ".py"]
  installedHash <- hashFile installedPath
  let installedInterfaces = [object ["path" .= installedPath, "sha256" .= installedHash]]
      commands = [version,info,libdir,imports] ++ nativeCommands ++ map snd audits
  artifactHashes <- hashes root $ map (directory </>) (["pre.cbd","post.cbd","oracle.json","originals.json","native.s","native.o","native-codegen.json"] ++ ["native.ll" | nativeLLVM]) ++
    map fst audits ++ concatMap commandArtifacts commands
  writeJson (root </> directory </> "manifest.json") $ object ["schema" .= (1::Int), "entries" .= entries,
    "producers" .= producers, "returns" .= returns, "literalOccurrences" .= (length entries + length producers),
    "nativeRows" .= length rows, "originalOccurrences" .= length originals, "inputHashes" .= inputHashes,
    "installedInterfaces" .= installedInterfaces, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "rubbish-literals: original installed literals and closed typed GHC-native continuation and return matrix prepared"

-- Inspection supplies the entry prefix; the auditor reads the executable CBD.
auditCBD :: FilePath -> Int -> String -> FilePath -> FilePath -> [String] -> IO CommandResult
auditCBD root expected label input output entries = do
  value <- BS.readFile (root </> input) >>= either fail pure . readModuleValue
  prefix <- case value of
    Object fields | Just (String unit) <- KeyMap.lookup "unit" fields,
                    Just (String owner) <- KeyMap.lookup "module" fields -> pure (Text.unpack unit ++ ":" ++ Text.unpack owner ++ ".")
    _ -> die "Rubbish CBD inspection lacks module identity"
  let args = ["bin/audit-core.py","--output",output] ++ concat [["--entry",prefix ++ name] | name <- entries] ++ [input]
      logs = directory </> "logs"
      artifacts = [logs </> label ++ suffix | suffix <- [".stdout",".stderr",".command.json"]]
  completed <- timeout (180 * 1000000) $ readCreateProcessWithExitCode ((proc "python3" args) {cwd = Just root})
    ""
  (code,out,err) <- maybe (die "Rubbish CBD inspection audit timed out") pure completed
  let actual = case code of ExitSuccess -> 0; ExitFailure n -> n
      record = object ["argv" .= ("python3":args),"cwd" .= root,"exit" .= actual,"expectedExit" .= expected,
        "inputCBD" .= input,"timeoutSeconds" .= (180::Int)]
      stdoutBytes = Text.encodeUtf8 (Text.pack out)
      stderrBytes = Text.encodeUtf8 (Text.pack err)
  BS.writeFile (root </> (artifacts !! 0)) stdoutBytes
  BS.writeFile (root </> (artifacts !! 1)) stderrBytes
  writeJson (root </> (artifacts !! 2)) record
  unless (actual == expected) (die ("Rubbish CBD audit failed: " ++ err))
  pure (CommandResult stdoutBytes stderrBytes record artifacts)
