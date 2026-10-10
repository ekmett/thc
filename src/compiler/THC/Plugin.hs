-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE LambdaCase #-}

-- |
-- Module      : THC.Plugin
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- GHC 9.14.1 plugin and direct serializers for THC's executable Core format.
--
-- Use @-fplugin=THC.Plugin@ with the output directory as the first plugin
-- option. Package exports require @post-tidy@ and @unit-qualified@; add
-- @source-notes@ to retain source-location metadata. The direct serializers
-- consume genuine GHC Core and do not establish runtime support or link native
-- foreign products. This library is tied to the selected GHC API version.
module THC.Plugin (plugin, serializeOptimizedCore, serializePostTidyCore, serializePostTidyCoreWithAnnotations,
                   serializePostTidyCoreWithAnnotationsBytes, serializeOptimizedCoreCBD,
                   serializePostTidyCoreCBD, serializePostTidyCoreWithAnnotationsCBD) where

import GHC.Plugins
import Control.Monad (when)
import Control.Monad.Trans.State.Strict (State,runState,get,put,modify')
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Word (Word64)
import Numeric (showHex)
import GHC.Float (castFloatToWord32,castDoubleToWord64)
import GHC.Iface.Env (lookupOrig)
import GHC.Tc.Utils.Env (lookupGlobal, TyThing(AnId))
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Unit.Types (ghcInternalUnit)
import GHC.Hs (HsParsedModule(..), HsModule(..), HsDecl(..), GhcPs)
import GHC.Hs.Decls (ForeignDecl(..), ForeignImport(..), CImportSpec(..))
import GHC.Hs.Type (LHsSigType, HsSigType(..), HsType(..))
import GHC.Parser.Annotation (getLocA)
import GHC.Types.Error (mkPlainError, mkSimpleUnknownDiagnostic, singleMessage)
import GHC.Utils.Error (mkPlainErrorMsgEnvelope)
import GHC.Types.SourceError (throwErrors)
import GHC.Driver.Errors.Types (GhcMessage(..))
import GHC.Types.SourceText (SourceText(..))
import GHC.Types.Name.Reader (RdrName(..))
import GHC.Types.Name.Occurrence (occNameMangledFS)
import GHC.Utils.Encoding.UTF8 (utf8DecodeByteString)
import GHC.Builtin.Names (ioTyConName)
import GHC.Builtin.Types (intTy, doubleTy, unitTy, anyTyCon)
import GHC.Builtin.Types.Prim (byteArrayPrimTyCon, mutableByteArrayPrimTyCon)
import GHC.Tc.Types (TcGblEnv(..))
import qualified THC.Sources as Sources
import qualified THC.CBV as CBV
import qualified THC.BackendAnnotations as Backend
import qualified THC.Demands as Demands
import qualified THC.ForeignExports as Exports
import qualified THC.ForeignExportProvenance as ExportProvenance
import qualified THC.ForeignImportProvenance as ImportProvenance
import THC.Wired (wiredApplication, wiredCase, wiredRhs, preservesWiredTypes, isWiredVoid)
import THC.JSON (J(..), moduleValue)
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Lazy as BL
import THC.Compact.Module (writeModuleWithDebug, encodeModuleWithDebug, readModuleValue)
import qualified THC.Compact.Core as C
import qualified THC.Compact.Annotations as Display
import qualified THC.Compact.Debug as Debug
import qualified THC.Compact.Facts as Facts
import THC.Compact.JSON (parseModuleFacts)
import GHC.Types.Tickish (CoreTickish, tickishFloatable)
import GHC.Types.Literal
import qualified GHC.Types.ForeignCall as Foreign
import GHC.Types.RepType (typePrimRep_maybe, unwrapType, ubxSumRepType, layoutUbxSum, primRepSlot, slotPrimRep)
import GHC.Builtin.Types (tupleRepDataConTyCon, sumRepDataConTyCon)
import GHC.Core.TyCo.Rep (scaledThing)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.Utils (exprIsHNF, exprOkForSpecEval)
import GHC.Core.Opt.Arity (etaExpand)
import GHC.Builtin.PrimOps (PrimOp(TagToEnumOp, MaskAsyncExceptionsOp, MaskUninterruptibleOp, UnmaskAsyncExceptionsOp), primOpOcc)
import GHC.StgToCmm.Closure (isSmallFamily)
import GHC.Cmm.Utils (mAX_PTR_TAG)
import GHC.Cmm.CLabel (CStubLabel(..))
import qualified GHC.Unit.Module.WholeCoreBindings as ForeignCore
import qualified Data.ByteString as BS
import Data.Char (ord)
import Data.List (isPrefixOf, nubBy, stripPrefix)
import qualified Data.List.NonEmpty as NE
import Data.IORef (IORef, newIORef, readIORef, modifyIORef')
import Data.Maybe (mapMaybe)
import System.IO.Unsafe (unsafePerformIO)
import System.Directory (createDirectoryIfMissing)
import System.FilePath ((</>), takeDirectory, replaceExtension)

-- | Export executable trees from GHC's Core pipeline. Without @post-tidy@ the
-- pass runs last before Tidy; that option selects the late plugin boundary
-- after Tidy and before CorePrep, where package identities agree with emitted
-- interfaces. The first option is the destination directory (default
-- @build/core@). GHC compilations using the plugin are forced to recompile so
-- the export is not silently skipped by native recompilation checks.
-- @pretty-diagnostics@ adds readable original Core to CBD inspection; it is off by
-- default and independent of @source-notes@ and executable representation facts.
--
-- For example, after making the plugin package visible to the selected GHC:
--
-- @
-- ghc -O2 -fplugin=THC.Plugin -fplugin-opt=THC.Plugin:build/core \\
--   -fplugin-opt=THC.Plugin:post-tidy -fplugin-opt=THC.Plugin:unit-qualified \\
--   -fplugin-opt=THC.Plugin:source-notes -c Example.hs
-- @
plugin :: Plugin
plugin = defaultPlugin
  { driverPlugin = Backend.installBackendHook (\options owner ->
      coreOutputPath options (case options of [] -> "build/core"; directory:_ -> directory)
        (unitString (moduleUnit owner)) (moduleNameString (moduleName owner)))
  , parsedResultAction = rewriteJavaScriptImports
  , typeCheckResultAction = \options summary environment ->
      validateJavaScriptTypes options summary environment >>= Exports.recordStaticExports options
        >>= ExportProvenance.recordProvenance options >>= ImportProvenance.recordImports options
  , installCoreToDos = \opts passes -> pure (if "post-tidy" `elem` opts then passes else passes ++ [CoreDoPluginPass "THC rich Core export" (exportModule opts)])
  , latePlugin = exportLate
  , pluginRecompile = \_ -> pure ForceRecompile
  }

-- GHC parses the JavaScript calling convention on native hosts, but rejects
-- it during typechecking. Rewriting only this small IO/scalar subset here lets
-- GHC generate its ordinary, typed FFI wrappers and state-token sequencing.
-- The marker carries the original source as UTF-8 bytes; no process-local
-- registry or native address masquerades as a JavaScript value.
javascriptPrefix :: String
javascriptPrefix = "thc_javascript_v1_"

javascriptSymbol :: String -> String
javascriptSymbol source = javascriptPrefix ++ concatMap hexByte (BS.unpack (bytesFS (mkFastString source)))
  where
    hexByte byte = let digits = "0123456789abcdef"
                   in [digits !! fromIntegral (byte `div` 16), digits !! fromIntegral (byte `mod` 16)]

javascriptSource :: String -> Maybe String
javascriptSource symbol = do
  encoded <- stripPrefix javascriptPrefix symbol
  if odd (length encoded) then Nothing else do
    bytes <- traverse hexPair (pairs encoded)
    let encodedBytes = BS.pack bytes
        source = utf8DecodeByteString encodedBytes
    if bytesFS (mkFastString source) == encodedBytes then Just source else Nothing
  where
    pairs [] = []
    pairs (a:b:rest) = [a,b] : pairs rest
    pairs _ = []
    digit c | c >= '0' && c <= '9' = Just (ord c - ord '0')
            | c >= 'a' && c <= 'f' = Just (ord c - ord 'a' + 10)
            | otherwise = Nothing
    hexPair [a,b] = fromIntegral <$> ((+) <$> ((16 *) <$> digit a) <*> digit b)
    hexPair _ = Nothing

rewriteJavaScriptImports _ _ result = do
  let parsed = parsedResultModule result
      L moduleLoc hsModule = hpm_module parsed
  decls <- traverse rewriteDecl (hsmodDecls hsModule)
  pure result { parsedResultModule = parsed { hpm_module = L moduleLoc hsModule { hsmodDecls = decls } } }
  where
    rewriteDecl located@(L declLoc (ForD ext foreignDecl@ForeignImport { fd_fi = CImport importLoc conv safety header spec }))
      | unLoc conv == Foreign.JavaScriptCallConv = do
          let reject reason = throwErrors (singleMessage (mkPlainErrorMsgEnvelope
                (getLocA located) (GhcUnknownMessage (mkSimpleUnknownDiagnostic
                  (mkPlainError [] (text ("THC foreign import javascript: " ++ reason)))))))
          if unLoc safety == Foreign.PlayInterruptible
            then reject "interruptible imports and callbacks are unsupported"
            else pure ()
          if not (supportedJavaScriptType (fd_sig_ty foreignDecl))
            then reject "expected Int/Double arguments and an IO Int, IO Double, or IO () result"
            else pure ()
          source <- case spec of
            CFunction (Foreign.StaticTarget _ label Nothing True)
              | let value = unpackFS label
              , value /= "dynamic" && value /= "wrapper" -> pure value
            _ -> reject "only a static JavaScript function source is supported (no dynamic/wrapper import)"
          let symbol = mkFastString (javascriptSymbol source)
              rewritten = CImport (L (getLoc importLoc) (SourceText symbol))
                (fmap (const Foreign.CCallConv) conv) safety header
                (CFunction (Foreign.StaticTarget NoSourceText symbol Nothing True))
          pure (L declLoc (ForD ext foreignDecl { fd_fi = rewritten }))
    rewriteDecl located@(L _ (ForD _ ForeignImport { fd_fi = CImport _ _ _ _ spec }))
      | reservedTarget spec = throwErrors (singleMessage (mkPlainErrorMsgEnvelope
          (getLocA located) (GhcUnknownMessage (mkSimpleUnknownDiagnostic
            (mkPlainError [] (text "THC foreign import javascript: the thc_javascript_v1_ target prefix is reserved; use the javascript calling convention"))))))
    rewriteDecl decl = pure decl
    reservedTarget (CFunction (Foreign.StaticTarget _ label _ _)) = javascriptPrefix `isPrefixOf` unpackFS label
    reservedTarget (CLabel label) = javascriptPrefix `isPrefixOf` unpackFS label
    reservedTarget _ = False

-- Deliberately narrow: the frontend does not guess a JavaScript conversion
-- for Bool, Char, pointers, newtypes, callbacks, or a pure FFI declaration.
supportedJavaScriptType :: LHsSigType GhcPs -> Bool
supportedJavaScriptType (L _ HsSig { sig_body = body }) = go (unLoc body)
  where
    go (HsParTy _ inner) = go (unLoc inner)
    go (HsFunTy _ _ argument result) = scalar (unLoc argument) && go (unLoc result)
    go (HsAppTy _ io result) = named "IO" (unLoc io) && resultType (unLoc result)
    go _ = False
    scalar ty = named "Int" ty || named "Double" ty
    resultType ty = scalar ty || case ty of
      HsParTy _ inner -> resultType (unLoc inner)
      HsTupleTy _ _ [] -> True
      _ -> False
    named expected (HsParTy _ inner) = named expected (unLoc inner)
    named expected (HsTyVar _ _ name) = case unLoc name of
      Unqual occ -> occNameString occ == expected
      _ -> False
    named _ _ = False

-- The parsed spelling check above is only a useful early diagnostic. Here the
-- resolved types must be the actual Prelude IO and scalar types: a local type
-- synonym named IO must never turn an effectful JavaScript call into pure Core.
validateJavaScriptTypes _ _ environment = do
  mapM_ validate (tcg_fords environment)
  pure environment
  where
    validate located@(L _ ForeignImport { fd_name = name, fd_fi = CImport _ _ _ _ spec })
      | CFunction (Foreign.StaticTarget _ symbol _ True) <- spec
      , Just _ <- javascriptSource (unpackFS symbol)
      , not (resolvedJavaScriptType (varType (unLoc name))) =
          throwErrors (singleMessage (mkPlainErrorMsgEnvelope
            (getLocA located) (GhcUnknownMessage (mkSimpleUnknownDiagnostic
              (mkPlainError [] (text "THC foreign import javascript: resolved type must use the canonical IO, Int, Double, and () types"))))))
    validate _ = pure ()

resolvedJavaScriptType :: Type -> Bool
resolvedJavaScriptType ty =
  let (arguments, result) = splitFunTys (expandTypeSynonyms ty)
  in all (scalar . scaledThing) arguments && case splitTyConApp_maybe result of
    Just (constructor, [value]) ->
      tyConName constructor == ioTyConName && (scalar value || eqType value unitTy)
    _ -> False
  where
    scalar value = eqType value intTy || eqType value doubleTy

num :: Integral a => a -> J
num = N . toInteger

data Ctx = Ctx
  { dynFlags :: DynFlags
  , modulePrefix :: String
  -- Package artifacts qualify opaque source IDs by their GHC unit. Source
  -- paths remain unchanged for display, but two packages may both have src/A.hs.
  , sourceUnit :: Maybe String
  -- As in CorePrep, exclude every enclosing recursive group while traversing
  -- its RHSs: speculative calls can otherwise destroy guarded recursion.
  , recursiveIds :: VarSet
  -- Lexical WHNF facts from successful cases and strict worker fields. These
  -- are facts about already evaluated values, never demand predictions.
  , evaluatedIds :: VarSet
  , canCertify :: Bool
  -- Current-module CBV requirements are selected only during native Tidy.
  , deriveCBVContracts :: Bool
  -- Optional human-readable dumps, never executable representation evidence.
  , prettyDiagnostics :: Bool
  , sourceTable :: Maybe Sources.SourceTable
  , activeSources :: [String]
  }

underTick :: Ctx -> CoreTickish -> Ctx
underTick d tick = case (sourceTable d, Sources.tickNote tick) of
  (Just table, Just note) | Sources.hasNote table note ->
    d { activeSources = activeSources d ++ [sourceKey d (Sources.noteKey note)] }
  _ -> d

sourceKey :: Ctx -> String -> String
sourceKey d key = maybe key (\unit -> show (unit,key)) (sourceUnit d)

loadSources :: Bool -> Bool -> [(Id,CoreExpr)] -> IO (Maybe Sources.SourceTable)
loadSources enabled includeText bindings = if enabled then Just <$> Sources.buildSourceTable includeText bindings else pure Nothing

pretty :: Outputable a => Ctx -> a -> String
pretty d = showSDoc (dynFlags d) . ppr

nameKey :: Name -> String
nameKey n = case nameModule_maybe n of
  Just m -> unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m) ++ "." ++ occurrence
  Nothing -> occurrence ++ "_" ++ showSDocUnsafe (ppr (nameUnique n))
  where
    -- GHC 9.14 gives selectors a constructor-qualified field namespace. An
    -- ordinary exported alias can have the same spelling as its selector;
    -- discarding the namespace merges the two IDs and creates a self-loop.
    -- Use GHC's own symbol spelling, also shared by cross-module references.
    occurrence = unpackFS (occNameMangledFS (nameOccName n))

-- Direct binary publication through the shared typed encoder. CBD owns its
-- fingerprints and source maps. Explicit diagnostics retain the producer's
-- rich model rather than reconstructing it from the compact runtime payload.
-- Module/native metadata may use Aeson. Executable records and their original
-- debug observations never enter that tree. The lazy binding stream is consumed
-- by the existing writer one top-level binding at a time.
data CoreOutput = CoreOutput J [C.Constructor] [(C.Binding,[Display.Annotation])] Display.ModuleAnnotations [C.ForeignCall]

moduleOutput :: Ctx -> J -> [DataCon] -> [(C.Binding,[Display.Annotation])] -> [C.ForeignCall] -> CoreOutput
moduleOutput d metadata cons bindings calls = CoreOutput metadata (map (constructor d) cons) bindings
  (Display.ModuleAnnotations (sourceCatalog d)
    [(index,bytes (occNameString (nameOccName (dataConName con)))) | (index,con) <- zip [0..] cons]) calls

sourceCatalog :: Ctx -> Display.SourceCatalog
sourceCatalog d = case sourceTable d of
  Nothing -> Map.empty
  Just table ->
    let files = Map.fromList [(Sources.sourceFileId file,
          Debug.SourceFile (bytes (sourceKey d (Sources.sourceFileId file))) (bytes (Sources.sourceFilePath file))
            (maybe C.Unknown (C.Known . bytes) (Sources.sourceFileContent file))) | file <- Sources.sourceFiles table]
        position record = let Sources.Note span label = Sources.sourceSpanNote record in
          Debug.SourcePosition (bytes (sourceKey d (Sources.sourceSpanId record))) (C.Known (bytes label))
            (fromIntegral (srcSpanStartLine span)) (fromIntegral (srcSpanStartCol span))
            (fromIntegral (srcSpanEndLine span)) (fromIntegral (srcSpanEndCol span))
            (maybe C.Unknown (C.Known . fromIntegral) (Sources.sourceCharIndex record))
            (maybe C.Unknown (C.Known . fromIntegral) (Sources.sourceCharLength record))
        entry record = case Map.lookup (Sources.sourceSpanFile record) files of
          Just file -> (bytes (sourceKey d (Sources.sourceSpanId record)),(file,position record))
          Nothing -> error "Original source span references missing file"
    in Map.fromList (map entry (Sources.sourceSpans table))

outputFacts :: CoreOutput -> IO Facts.Facts
outputFacts (CoreOutput metadata constructors _ _ calls) = do
  facts <- either fail pure (parseModuleFacts (moduleValue metadata))
  let imports (C.Known (Facts.ImportsRecord (Facts.ImportProof schema profile scope execution unit owner status))) =
        C.Known (Facts.ImportsRecord (Facts.ImportProof schema profile scope execution unit owner (case status of
          Facts.ImportsVerified wordBits original associations _ addresses wrappers partition ->
            Facts.ImportsVerified wordBits original associations calls addresses wrappers partition
          other -> other)))
      imports other = other
  pure (facts { Facts.factsConstructors = constructors,
    Facts.factsPendingProvenance = map imports (Facts.factsPendingProvenance facts) })

-- Header provenance needs call descriptors before DATA publication. Traverse
-- original Core independently, so preparing metadata never forces or retains
-- the lazy stream of executable typed bindings.
coreCalls :: Ctx -> CoreExpr -> [C.ForeignCall]
coreCalls d original
  | Just lowered <- wiredCase original = coreCalls d lowered
  | Just lowered <- wiredApplication original = coreCalls d lowered
  | Var v <- original, isWiredVoid v = []
  | Var v <- original, Just lowered <- wiredRhs v =
      coreCalls (d { canCertify = canCertify d && preservesWiredTypes original lowered }) lowered
  -- Inventory needs no eta-expansion: revisiting a mask application head
  -- would repeatedly expand the same primitive. Its operands contain the calls.
  | otherwise = case original of
      App{} -> let (function,args) = collectArgs original
                   values = filter (\case Type{} -> False; _ -> True) args
                   nested = coreCalls d function ++ concatMap (coreCalls d) values
               in nested ++ if null values then [] else case foreignCall d function args of C.Known call -> [call]; _ -> []
      Lam _ body -> coreCalls d body
      Let bindings body -> concatMap (coreCalls d . snd) (flattenBind bindings) ++ coreCalls d body
      Case scrut _ _ alternatives -> coreCalls d scrut ++ concat [coreCalls d body | Alt _ _ body <- alternatives]
      Cast body _ -> coreCalls d body
      Tick tick body -> coreCalls (underTick d tick) body
      _ -> []

programCalls :: Ctx -> CoreProgram -> [C.ForeignCall]
programCalls d = concatMap (concatMap (coreCalls d . snd) . flattenBind)

writeCoreOutput :: [CommandLineOption] -> FilePath -> CoreOutput -> IO ()
writeCoreOutput options path output@(CoreOutput _ _ bindings annotations _) = do
  facts <- outputFacts output
  _ <- writeModuleWithDebug path facts bindings annotations
  when ("pretty-diagnostics" `elem` options) $ do
    bytes <- BS.readFile path
    inspection <- inspectCore output bytes
    BS.writeFile (replaceExtension path "json") inspection

encodeCoreOutput :: CoreOutput -> IO BS.ByteString
encodeCoreOutput output@(CoreOutput _ _ bindings annotations _) = do
  facts <- outputFacts output
  encodeModuleWithDebug facts bindings annotations

coreOutputPath :: [CommandLineOption] -> FilePath -> String -> String -> FilePath
coreOutputPath opts dir unit modName
  | "unit-qualified" `elem` opts = dir </> "units" </> ("u-" ++ concatMap escapeUnit unit) </> modName ++ ".cbd"
  | otherwise = dir </> modName ++ ".cbd"
  where
    escapeUnit c
      | asciiAlphaNum c || c `elem` ("-._" :: String) = [c]
      | otherwise = '%' : showHex (ord c) ";"
    asciiAlphaNum c = ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9')

varKey :: Ctx -> Var -> String
varKey d v = case nameModule_maybe (varName v) of
  Just _ -> nameKey (varName v)
  Nothing -> modulePrefix d ++ "." ++ nameKey (varName v)

-- Runtime categories are direct GHC evidence. Shapes and evaluatedness stay
-- separate, including the states of nested logical aggregate components.
lifted :: Var -> C.Presence Bool
lifted v | isCoVar v = C.Known False
         | otherwise = liftedType (varType v)

liftedType :: Type -> C.Presence Bool
liftedType ty = case typeLevity_maybe ty of
  Just Lifted -> C.Known True
  Just Unlifted -> C.Known False
  Nothing -> C.Unknown

unknownRep :: C.Rep
unknownRep = unknownRepWithState False

unknownRepWithState :: Bool -> C.Rep
unknownRepWithState evaluated = C.Rep
  (C.Shape C.UnknownKind C.Unknown C.Missing C.Missing C.Missing C.Missing C.Missing C.Missing)
  (C.Evaluation (C.Known evaluated) [])

voidRep :: C.Rep
voidRep = C.Rep
  (C.Shape C.VoidKind (C.Known []) C.Missing C.Missing C.Missing C.Missing C.Missing C.Missing)
  (C.Evaluation (C.Known True) [])

-- Tuple storage concatenates its components, including known boxed pointers
-- with unknown levity. GHC's aggregate RuntimeRep callbacks are partial for
-- genuinely unknown representations and levity-polymorphic sum layouts.
typePrimReps :: Type -> Maybe [PrimRep]
typePrimReps ty
  | Just (tc,args) <- splitTyConApp_maybe (unwrapType ty)
  , isUnboxedTupleTyCon tc
  = concat <$> traverse typePrimReps (dropRuntimeRepArgs args)
  | Just (tc,args) <- splitTyConApp_maybe (unwrapType ty)
  , isUnboxedSumTyCon tc
  = do
      alternatives <- traverse typePrimReps (dropRuntimeRepArgs args)
      if all knownSumSlot (concat alternatives)
        then Just (map slotPrimRep (NE.toList (ubxSumRepType alternatives)))
        else Nothing
  | Just _ <- aggregateRuntimeKind ty
  , not (typeHasFixedRuntimeRep ty) = Nothing
  | otherwise = typePrimRep_maybe ty

knownSumSlot :: PrimRep -> Bool
knownSumSlot (BoxedRep Nothing) = False
knownSumSlot _ = True

-- A type variable or opaque family can expose an aggregate RuntimeRep without
-- exposing logical payload types. This is aggregate evidence, even for zero
-- or one physical registers, but it is not evidence for a component layout.
aggregateRuntimeKind :: Type -> Maybe (String,String)
aggregateRuntimeKind ty = case splitTyConApp_maybe (getRuntimeRep ty) of
  Just (tc,_)
    | tc == tupleRepDataConTyCon -> Just ("unboxed-tuple","components")
    | tc == sumRepDataConTyCon -> Just ("unboxed-sum","alternatives")
  _ -> Nothing

typeRep :: Type -> Bool -> C.Rep
typeRep ty evaluated = C.Rep
  (C.Shape kind (maybe C.Unknown (C.Known . map compactPrimRep) reps)
    vector aggregate components alternatives tagSlot alternativeSlots)
  (C.Evaluation (C.Known evaluated) (map repState children))
  where
    reps = typePrimReps ty
    vector = case reps of
      Just [VecRep lanes element] -> C.Known (C.Vector (fromIntegral lanes) (compactElement element))
      _ -> C.Missing
    (_,rho) = splitForAllTyVars ty
    logical = case splitTyConApp_maybe (unwrapType ty) of
      Just (tc,args)
        | isUnboxedTupleTyCon tc -> Just (C.TupleAggregate,Just (dropRuntimeRepArgs args))
        | isUnboxedSumTyCon tc -> Just (C.SumAggregate,Just (dropRuntimeRepArgs args))
        | isPrimTyCon tc -> Nothing
      _ -> case aggregateRuntimeKind ty of
        Just ("unboxed-tuple",_) -> Just (C.TupleAggregate,Nothing)
        Just ("unboxed-sum",_) -> Just (C.SumAggregate,Nothing)
        _ -> Nothing
    aggregate = maybe C.Missing (C.Known . fst) logical
    children = case logical of
      Just (_,Just types) -> map (\t -> typeRep t (typeLevity_maybe t == Just Unlifted)) types
      _ -> []
    childShapes = case logical of
      Just (_,Just _) -> C.Known (map repShape children)
      Just (_,Nothing) -> C.Unknown
      Nothing -> C.Missing
    components = case logical of Just (C.TupleAggregate,_) -> childShapes; _ -> C.Missing
    alternatives = case logical of Just (C.SumAggregate,_) -> childShapes; _ -> C.Missing
    tagSlot = case logical of Just (C.SumAggregate,_) -> C.Known 0; _ -> C.Missing
    alternativeSlots = case logical of
      Just (C.SumAggregate,Just types) -> maybe C.Unknown (C.Known . map (map fromIntegral)) (sumLayout types)
      Just (C.SumAggregate,Nothing) -> C.Unknown
      _ -> C.Missing
    sumLayout types = do
      choices <- traverse typePrimReps types
      physical <- reps
      if all knownSumSlot (concat choices) then do
        let slots = ubxSumRepType choices
        if physical == map slotPrimRep (NE.toList slots)
          then Just [map (+1) (layoutUbxSum (NE.tail slots) (map primRepSlot alternative)) | alternative <- choices]
          else Nothing
        else Nothing
    kind | Just _ <- logical = C.UnknownKind
         | otherwise = case reps of
      Just [] -> C.VoidKind
      Just [r] | longRep r -> C.LongKind
      Just [VecRep _ _] -> C.VectorKind
      Just [FloatRep] -> C.FloatKind
      Just [DoubleRep] -> C.DoubleKind
      Just [AddrRep] -> C.AddressKind
      Just [BoxedRep _]
        | isFunTy rho -> C.ClosureKind
        | Just (tc,_) <- splitTyConApp_maybe rho, isBoxedDataTyCon tc -> C.DataKind
        | otherwise -> C.ObjectKind
      _ -> C.UnknownKind
    longRep = \case
      IntRep -> True; Int8Rep -> True; Int16Rep -> True; Int32Rep -> True; Int64Rep -> True
      WordRep -> True; Word8Rep -> True; Word16Rep -> True; Word32Rep -> True; Word64Rep -> True
      _ -> False

repShape :: C.Rep -> C.Shape
repShape (C.Rep shape _) = shape
repState :: C.Rep -> C.Evaluation
repState (C.Rep _ state) = state

compactPrimRep :: PrimRep -> C.PrimRep
compactPrimRep = \case
  IntRep -> C.IntRep; WordRep -> C.WordRep
  Int8Rep -> C.Int8Rep; Int16Rep -> C.Int16Rep; Int32Rep -> C.Int32Rep; Int64Rep -> C.Int64Rep
  Word8Rep -> C.Word8Rep; Word16Rep -> C.Word16Rep; Word32Rep -> C.Word32Rep; Word64Rep -> C.Word64Rep
  FloatRep -> C.FloatRep; DoubleRep -> C.DoubleRep; AddrRep -> C.AddrRep
  BoxedRep Nothing -> C.BoxedUnknown
  BoxedRep (Just Lifted) -> C.BoxedLifted
  BoxedRep (Just Unlifted) -> C.BoxedUnlifted
  VecRep lanes element -> C.VecRep (C.Vector (fromIntegral lanes) (compactElement element))

compactElement :: PrimElemRep -> C.Element
compactElement = \case
  Int8ElemRep -> C.Int8Element; Int16ElemRep -> C.Int16Element; Int32ElemRep -> C.Int32Element; Int64ElemRep -> C.Int64Element
  Word8ElemRep -> C.Word8Element; Word16ElemRep -> C.Word16Element; Word32ElemRep -> C.Word32Element; Word64ElemRep -> C.Word64Element
  FloatElemRep -> C.FloatElement; DoubleElemRep -> C.DoubleElement

exprRep :: Ctx -> CoreExpr -> C.Rep
exprRep d (Tick _ e) = exprRep d e
exprRep d e
  | not (canCertify d) = unknownRep
  | Coercion{} <- e = voidRep
  | otherwise = typeRep (exprType e) (rubbishValue e || exprIsHNF e || case e of
      Var v -> v `elemVarSet` evaluatedIds d
      _ -> False)

-- GHC's generic HNF predicate is conservative for a type-applied literal.
-- LitRubbish is an evaluated absent filler, independent of its runtime shape.
-- Do not extend this fact to value applications headed by rubbish.
rubbishValue :: CoreExpr -> Bool
rubbishValue (Lit LitRubbish{}) = True
rubbishValue (App f Type{}) = rubbishValue f
rubbishValue (Cast body _) = rubbishValue body
rubbishValue _ = False

binderRep :: Ctx -> Bool -> Var -> C.Rep
binderRep d evaluated v
  | isCoVar v = voidRep
  | not (canCertify d) = unknownRepWithState evaluated
  | otherwise = typeRep (varType v) (evaluated || v `elemVarSet` evaluatedIds d || exprIsHNF (Var v))

-- Ordinals restart at each top-level binding and advance in the canonical
-- declaration order. Only the lexical scope resolves local references.
type Locals = Map.Map String Word64
type Build = State (Word64,[Display.Annotation])

allocate :: Ctx -> Locals -> [Var] -> Build Locals
allocate d scope variables = do
  let names = map (varKey d) variables
  when (length names /= Map.size (Map.fromList [(name,()) | name <- names]))
    (error "Duplicate lexical declaration in one group")
  pairs <- mapM (\name -> do
    (ordinal,annotations) <- get
    when (ordinal == maxBound) (error "Too many lexical declarations")
    put (ordinal+1,annotations)
    pure (name,ordinal)) names
  pure (Map.union (Map.fromList pairs) scope)

bytes :: String -> BS.ByteString
bytes = Text.encodeUtf8 . Text.pack

entryType :: Ctx -> Var -> C.EntryType
entryType d v = case pretty d (varType v) of
  "IO ()" -> C.IOUnit
  "State# RealWorld" -> C.StateRealWorld
  _ -> C.OtherEntry

sourceId :: Ctx -> Var -> Maybe BS.ByteString
sourceId d v = case (sourceTable d,Sources.binderNote v) of
  (Just table,Just note) | Sources.hasNote table note -> Just (bytes (sourceKey d (Sources.noteKey note)))
  _ -> Nothing

annotateBinder :: Display.RecordKind -> Ctx -> Var -> Build ()
annotateBinder kind d v = modify' (\(ordinal,annotations) ->
  (ordinal,Display.Annotation kind (Just (bytes (occNameString (nameOccName (varName v))))) (sourceId d v) [] : annotations))

annotateExpr :: Ctx -> Build ()
annotateExpr d = modify' (\(ordinal,annotations) ->
  let notes = map bytes (activeSources d)
      source = case reverse notes of value:_ -> Just value; [] -> Nothing
  in (ordinal,Display.Annotation Display.ExpressionRecord Nothing source notes : annotations))

binder :: Ctx -> Locals -> Var -> Build C.Binder
binder d scope = binderWithState d scope False

binderWithState :: Ctx -> Locals -> Bool -> Var -> Build C.Binder
binderWithState d scope evaluated v = do
  let ordinal = case Map.lookup (varKey d v) scope of
        Just value -> value
        Nothing -> error "Binder has no lexical declaration"
  annotateBinder (Display.BinderRecord ordinal) d v
  pure (C.Binder ordinal (entryType d v) (lifted v) (C.Known (isCoVar v))
    (C.Known (binderRep d evaluated v)) (if isId v then C.Known (idMetadata v) else C.Unknown))

binding :: Ctx -> Locals -> Maybe Locals -> (Id,CoreExpr) -> Build C.Binding
binding d rhsScope declared (v,e) = do
  let identity = case declared of
        Nothing -> C.Global (bytes (varKey d v))
        Just scope -> case Map.lookup (varKey d v) scope of
          Just ordinal -> C.Local ordinal
          Nothing -> error "Local binding has no lexical ordinal"
  annotateBinder (Display.BindingRecord identity) d v
  exported <- expr d rhsScope e
  let (marks,origin) = if canCertify d then CBV.entryContract (deriveCBVContracts d) v e else ([],"none")
      aligned = case exported of
        C.Lam _ parameters _ | length marks <= length parameters -> take (length parameters) (marks ++ repeat False)
        _ -> marks
      annotated = case exported of
        C.Lam metadata parameters body -> C.Lam
          (metadata { C.metaEntryStrict = C.Known aligned, C.metaEntryStrictSource = C.Known (bytes origin) }) parameters body
        _ -> exported
      (joinArity,joinRep) = if isJoinId v
        then let (prefix,result) = collectNBinders (idJoinArity v) e
             in (C.Known (fromIntegral (length (filter (not . isTyVar) prefix))),C.Known (exprRep d result))
        else (C.Missing,C.Missing)
  pure (C.Binding identity (entryType d v) (lifted v) (fromIntegral (idArity v))
    (C.Known (exprRep d e)) (C.Known (idMetadata v)) (C.Known aligned) (C.Known (bytes origin))
    joinArity joinRep (hostSignature d v) annotated)

hostSignature :: Ctx -> Id -> C.Presence C.HostSignature
hostSignature d v
  | canCertify d, isExternalName (varName v), any relevant (result:parameters) =
      C.Known (C.HostSignature (map hostType parameters) (hostType result))
  | otherwise = C.Missing
  where
    (_,rho) = splitForAllTyVars (expandTypeSynonyms (varType v))
    (arguments,result) = splitFunTys rho
    parameters = map scaledThing arguments
    relevant = any (/= C.HostPlain) . hostCarriers
    hostType ty = C.HostType (typeRep ty (typeLevity_maybe ty == Just Unlifted)) (hostCarriers ty)

hostCarriers :: Type -> [C.HostCarrier]
hostCarriers ty = case splitTyConApp_maybe (expandTypeSynonyms ty) of
  Just (tc,_) | isNewTyCon tc
    , Just owner <- nameModule_maybe (tyConName tc)
    , moduleNameString (moduleName owner) == "THC.Prim"
    , Just (underlying,_) <- splitTyConApp_maybe (unwrapType ty)
    , underlying == anyTyCon
    , typePrimReps ty == Just [BoxedRep (Just Unlifted)] ->
        case occNameString (nameOccName (tyConName tc)) of
          "Object#" -> [C.HostObject]
          "InteropLibrary#" -> [C.HostInteropLibrary]
          _ -> [C.HostPlain]
  _ -> case splitTyConApp_maybe (unwrapType ty) of
    Just (tc,args) | isUnboxedTupleTyCon tc || isUnboxedSumTyCon tc -> concatMap hostCarriers (dropRuntimeRepArgs args)
    _ -> [C.HostPlain]

idMetadata :: Id -> C.IdInfo
idMetadata v = C.IdInfo
  (if isJoinId v then C.Known (fromIntegral (idJoinArity v)) else C.Unknown)
  (C.Known (CBV.eligible v)) (maybe C.Unknown C.Known (CBV.existingMarks v))

flattenBind :: CoreBind -> [(Id,CoreExpr)]
flattenBind (NonRec v e) = [(v,e)]
flattenBind (Rec vs) = vs

bindingGroup :: Ctx -> CoreBind -> [(C.Binding,[Display.Annotation])]
bindingGroup d b = map (buildBinding rhsCtx) (flattenBind b)
  where
    rhsCtx = case b of
      NonRec{} -> d
      Rec vs -> d { recursiveIds = extendVarSetList (recursiveIds d) (map fst vs) }

buildBinding :: Ctx -> (Id,CoreExpr) -> (C.Binding,[Display.Annotation])
buildBinding d pair = let (record,(_,annotations)) = runState (binding d Map.empty Nothing pair) (0,[])
                      in (record,reverse annotations)

constructor :: Ctx -> DataCon -> C.Constructor
constructor d con = C.Constructor
  (bytes (nameKey (dataConName con))) (fromIntegral (dataConRepArity con)) (fromIntegral (dataConTag con)) kind
  (map isMarkedStrict marks) (map liftedType workerTypes)
  (map (maybe C.Unknown (C.Known . map compactPrimRep) . typePrimReps) workerTypes)
  (zipWith (\ty strict -> typeRep ty (strict || typeLevity_maybe ty == Just Unlifted)) workerTypes workerStrict)
  (if isUnboxedSumDataCon con then C.Known (fromIntegral (length (tyConDataCons tc))) else C.Missing)
  (if supportedEnum tc then C.Known (enumFamily tc) else C.Missing)
  (if supportedTagFamily tc then C.Known (dataToTagFamily d tc) else C.Missing)
  where
    tc = dataConTyCon con
    kind | isUnboxedTupleDataCon con = C.TupleConstructor
         | isUnboxedSumDataCon con = C.SumConstructor
         | isNewTyCon tc = C.NewtypeConstructor
         | otherwise = C.BoxedConstructor
    workerTypes = map scaledThing (dataConRepArgTys con)
    marks = dataConRepStrictness con
    workerStrict = if length marks == length workerTypes then map isMarkedStrict marks else repeat False

expr :: Ctx -> Locals -> CoreExpr -> Build C.Expr
expr d scope original
  | Just lowered <- wiredCase original =
      fmap (withRep (exprRep d original) . mapMeta (\m -> m { C.metaUnsafeEqualityCase = C.Known (bytes "GHC.Core.Utils.isUnsafeEqualityCase/CoreToStg") })) (expr d scope lowered)
  | Just lowered <- wiredApplication original =
      if canCertify d && preservesWiredTypes original lowered
      then expr d scope lowered
      else uncertifiedRoot <$> expr d scope lowered
  | Var v <- original, isWiredVoid v = do
      annotateExpr d
      pure (C.Void (C.emptyMeta { C.metaRep = C.Known voidRep }))
  | Var v <- original, Just lowered <- wiredRhs v = expr (d { canCertify = canCertify d && preservesWiredTypes original lowered }) scope lowered
  | canCertify d, Just saturated <- saturateMask original = expr d scope saturated
  | otherwise = exprRaw d scope original

saturateMask :: CoreExpr -> Maybe CoreExpr
saturateMask original
  | (Var v,args,ticks) <- collectArgsTicks tickishFloatable original
  , Just op <- isPrimOpId_maybe v
  , op `elem` [MaskAsyncExceptionsOp, MaskUninterruptibleOp, UnmaskAsyncExceptionsOp]
  , let missing = idArity v - length (filter (not . isTypeArg) args)
  , missing > 0
  = Just (foldr Tick (etaExpand missing (mkApps (Var v) args)) ticks)
  | otherwise = Nothing
  where
    isTypeArg Type{} = True
    isTypeArg _ = False


mapMeta :: (C.Meta -> C.Meta) -> C.Expr -> C.Expr
mapMeta update = \case
  C.Var m v -> C.Var (update m) v
  C.Prim m p -> C.Prim (update m) p
  C.Lit m l -> C.Lit (update m) l
  C.Lam m bs e -> C.Lam (update m) bs e
  C.Con m c arity -> C.Con (update m) c arity
  C.App m f args lifted hnf speculate -> C.App (update m) f args lifted hnf speculate
  C.Let m recursive bs e -> C.Let (update m) recursive bs e
  C.Case m scrut ordinal binder alts -> C.Case (update m) scrut ordinal binder alts
  C.Void m -> C.Void (update m)
  C.Unsupported m reason -> C.Unsupported (update m) reason

withRep :: C.Rep -> C.Expr -> C.Expr
withRep rep = mapMeta (\m -> m { C.metaRep = C.Known rep })

uncertifiedRoot :: C.Expr -> C.Expr
uncertifiedRoot serialized = case withRep unknownRep serialized of
  C.App m f args lifted _ _ -> C.App (m { C.metaCallDemand = C.Missing }) f args lifted False False
  other -> other

foreignCall :: Ctx -> CoreExpr -> [CoreExpr] -> C.Presence C.ForeignCall
foreignCall d f@(Var v) args
  | canCertify d
  , Just (Foreign.CCall (Foreign.CCallSpec target convention safety)) <- isFCallId_maybe v
  , let (types,values) = span isTypeArg args
  , not (any isTypeArg values)
  , let instantiated = exprType (mkApps f types)
  , null (fst (splitForAllTyVars instantiated))
  , let (parameters,result) = splitFunTys instantiated
        arrays = map (arrayType . scaledThing) parameters
        hasArrays = any (\case C.Known _ -> True; _ -> False) arrays
  = C.Known (C.ForeignCall (if hasArrays then 2 else 1) (targetRecord target)
      (callConvention convention) (callSafety safety) (fromIntegral (length parameters)) (fromIntegral (length values))
      [typeRep (scaledThing parameter) False | parameter <- parameters] (typeRep result False)
      (maybe C.Missing (const (C.Known (bytes "javascript-v1"))) script)
      (maybe C.Missing (C.Known . bytes) script)
      (if hasArrays then C.Known arrays else C.Missing))
  where
    script = case isFCallId_maybe v of
      Just (Foreign.CCall (Foreign.CCallSpec (Foreign.StaticTarget _ symbol _ True) Foreign.CCallConv safety))
        | safety `elem` [Foreign.PlaySafe,Foreign.PlayRisky] -> javascriptSource (unpackFS symbol)
      _ -> Nothing
    arrayType ty = case splitTyConApp_maybe (unwrapType ty) of
      Just (constructor,_) | constructor == byteArrayPrimTyCon -> C.Known (bytes "ByteArray#")
      Just (constructor,_) | constructor == mutableByteArrayPrimTyCon -> C.Known (bytes "MutableByteArray#")
      _ -> C.Unknown
    isTypeArg Type{} = True
    isTypeArg _ = False
    targetRecord (Foreign.StaticTarget _ symbol unit isFunction) = C.StaticTarget (bytes (unpackFS symbol))
      (maybe C.Unknown (C.Known . bytes . unitString) unit) isFunction
    targetRecord Foreign.DynamicTarget = C.DynamicTarget
    callConvention Foreign.CCallConv = C.CCall
    callConvention Foreign.CApiConv = C.CApi
    callConvention Foreign.StdCallConv = C.StdCall
    callConvention Foreign.PrimCallConv = C.PrimCall
    callConvention Foreign.JavaScriptCallConv = C.JavaScriptCall
    callSafety Foreign.PlayRisky = C.UnsafeCall
    callSafety Foreign.PlaySafe = C.SafeCall
    callSafety Foreign.PlayInterruptible = C.InterruptibleCall
foreignCall _ _ _ = C.Missing

-- Only the versioned Truffle intrinsics are link-resolved without a source
-- definition. Every other foreign import stays in missingDefinitions.
polyglotForeign :: Id -> Bool
polyglotForeign v = case isFCallId_maybe v of
  Just (Foreign.CCall (Foreign.CCallSpec
    (Foreign.StaticTarget _ symbol _ True) Foreign.PrimCallConv Foreign.PlaySafe)) ->
      "thc_vector_v1_" `isPrefixOf` unpackFS symbol ||
      "thc_interop_v1_" `isPrefixOf` unpackFS symbol ||
      "thc_string_v1_" `isPrefixOf` unpackFS symbol || unpackFS symbol `elem`
        ["thc_polyglot_v1_eval", "thc_polyglot_v1_read_member", "thc_polyglot_v1_execute_int"
        , "thc_polyglot_v1_execute_value"
        , "thc_polyglot_v1_buffer_view"
        , "thc_polyglot_v1_buffer_mutable_view"
        , "thc_polyglot_v1_array_view"
        , "thc_polyglot_v1_array_mutable_view"
        , "thc_polyglot_v1_buffer_size"
        , "thc_polyglot_v1_buffer_read_byte"
        , "thc_polyglot_v1_buffer_write_byte"
        , "thc_polyglot_v1_buffer_read_long"
        , "thc_polyglot_v1_buffer_write_long"
        , "thc_polyglot_v1_buffer_copy"
        , "thc_polyglot_v1_buffer_copy_into"
        , "thc_polyglot_v1_array_size"
        , "thc_polyglot_v1_array_read"
        , "thc_polyglot_v1_array_write"
        , "thc_polyglot_v1_array_copy"
        , "thc_interop_v1_get_library", "thc_interop_v1_import_value"
        , "thc_interop_v1_is_string", "thc_interop_v1_as_truffle_string"
        , "thc_interop_v1_has_buffer_elements", "thc_interop_v1_is_buffer_writable"
        , "thc_interop_v1_get_buffer_size", "thc_interop_v1_read_buffer_byte", "thc_interop_v1_write_buffer_byte"
        , "thc_interop_v1_has_array_elements", "thc_interop_v1_get_array_size"
        , "thc_interop_v1_read_array_element", "thc_interop_v1_write_array_element", "thc_interop_v1_as_long"]
  Just (Foreign.CCall (Foreign.CCallSpec
    (Foreign.StaticTarget _ symbol _ True) Foreign.CCallConv safety)) ->
      (safety == Foreign.PlayRisky && unpackFS symbol `elem`
        ["thc_cpu_affinity_v1_support", "thc_cpu_affinity_v1_applied"]) ||
      (safety `elem` [Foreign.PlaySafe, Foreign.PlayRisky]
       && case javascriptSource (unpackFS symbol) of Just _ -> True; Nothing -> False)
  _ -> False

-- Preserve the exact nominal payload type before erasure. Primitive raises
-- remain lazy; only a compatible public exit may normalize a proven exception.
someExceptionPayload :: CoreExpr -> [CoreExpr] -> C.Presence C.ExceptionPayload
someExceptionPayload (Var callee) (payload:_)
  | Just op <- isPrimOpId_maybe callee
  , occNameString (primOpOcc op) `elem` ["raise#","raiseIO#"]
  , Just (tc,[]) <- splitTyConApp_maybe (exprType payload)
  , nameKey (tyConName tc) == "ghc-internal:GHC.Internal.Exception.Type.SomeException"
  = C.Known (C.ExceptionPayload 1 (bytes "ghc-internal:GHC.Internal.Exception.Type.SomeException"))
someExceptionPayload _ _ = C.Missing

exprRaw :: Ctx -> Locals -> CoreExpr -> Build C.Expr
exprRaw d scope original = case original of
  Cast e _ -> withRep (exprRep d original) <$> expr d scope e
  Tick tick e -> expr (underTick d tick) scope e
  Type _ -> error "Core type used as an executable value"
  a@App{} | let (_,args) = collectArgs a, all isTypeArg args ->
    let (f,_) = collectArgs a in withRep (exprRep d a) <$> expr d scope f
  l@Lam{} | let (bs,_) = collectBinders l, all isTyVar bs ->
    let (_,body) = collectBinders l in withRep (exprRep d l) <$> expr d scope body
  _ -> do
    annotateExpr d
    let metadata = C.emptyMeta { C.metaRep = C.Known (exprRep d original) }
    case original of
      Var v | Just p <- isPrimOpId_maybe v -> pure (C.Prim metadata (bytes (occNameString (primOpOcc p))))
            | Just con <- isDataConWorkId_maybe v -> pure (C.Con metadata (bytes (nameKey (dataConName con))) (fromIntegral (dataConRepArity con)))
            | otherwise -> pure (C.Var metadata (maybe (C.Global (bytes (varKey d v))) C.Local (Map.lookup (varKey d v) scope)))
      Lit (LitRubbish _ rep) | noFreeVarsOfType rep -> pure (C.Lit metadata C.LitRubbish)
      Lit l -> pure (C.Lit metadata (literal d l))
      a@App{} -> do
        let (f,args) = collectArgs a
            vals = filter (not . isTypeArg) args
            application = metadata
              { C.metaCallDemand = case if canCertify d then Demands.callDemand f args else Nothing of
                  Just (arity,strict) -> C.Known (C.CallDemand (fromIntegral arity) strict)
                  Nothing -> C.Missing
              , C.metaEnumFamily = maybe C.Missing (C.Known . enumFamily) (tagToEnumFamily a)
              , C.metaTagFamily = maybe C.Missing (C.Known . dataToTagFamily d) (dataToTagApplication a)
              , C.metaExceptionPayload = someExceptionPayload f vals
              , C.metaForeignCall = foreignCall d f args }
        function <- case f of
          Var v | Just _ <- isPrimOpId_maybe v -> exprRaw d scope f
          _ -> expr d scope f
        arguments <- mapM (expr d scope) vals
        pure (C.App application function arguments (map argLifted vals)
          (canCertify d && exprIsHNF a)
          (canCertify d && exprOkForSpecEval (\v -> not (v `elemVarSet` recursiveIds d)) a))
      l@Lam{} -> do
        let (bs,body) = collectBinders l
            vals = filter (not . isTyVar) bs
        inner <- allocate d scope vals
        parameters <- mapM (binder d inner) vals
        C.Lam (metadata { C.metaResultRep = C.Known (exprRep d body) }) parameters <$> expr d inner body
      Let b body -> do
        let pairs = flattenBind b
            recursive = case b of Rec{} -> True; _ -> False
            rhsCtx = case b of Rec{} -> d { recursiveIds = extendVarSetList (recursiveIds d) (map fst pairs) }; _ -> d
        inner <- allocate d scope (map fst pairs)
        records <- mapM (binding rhsCtx (if recursive then inner else scope) (Just inner)) pairs
        C.Let metadata recursive records <$> expr d inner body
      Case scrut b _ alts -> do
        let branchCtx = d { evaluatedIds = extendVarSetList (evaluatedIds d) (b : scrutineeVars scrut) }
        scrutinee <- expr d scope scrut
        inner <- allocate d scope [b]
        information <- binderWithState d inner True b
        alternatives <- mapM (alt branchCtx inner) alts
        pure (C.Case (metadata { C.metaResultRep = C.Known (exprRep d original) }) scrutinee
          (C.binderOrdinal information) (C.Known information) alternatives)
      Coercion _ -> pure (C.Void metadata)
  where
    isTypeArg Type{} = True
    isTypeArg _ = False
    argLifted Coercion{} = C.Known False
    argLifted e = liftedType (exprType e)
    scrutineeVars (Var v) = [v]
    scrutineeVars (Cast e _) = scrutineeVars e
    scrutineeVars (Tick _ e) = scrutineeVars e
    scrutineeVars _ = []
    alt branchCtx inner (Alt ac bs body) = do
      let vals = filter (not . isTyVar) bs
          strictFields = case ac of
            DataAlt c | canCertify d, let marks = dataConRepStrictness c, length marks == length vals ->
              [v | (v,mark) <- zip vals marks, isMarkedStrict mark]
            _ -> []
          bodyCtx = branchCtx { evaluatedIds = extendVarSetList (evaluatedIds branchCtx) strictFields }
      declared <- allocate d inner vals
      parameters <- mapM (binder bodyCtx declared) vals
      result <- expr bodyCtx declared body
      pure $ case ac of
        DEFAULT -> C.DefaultAlt parameters result
        DataAlt c -> C.DataAlt (bytes (nameKey (dataConName c))) parameters result
        LitAlt l -> C.LiteralAlt (literal d l) parameters result

literal :: Ctx -> Literal -> C.Literal
literal d = \case
  LitNumber kind value -> case kind of
    LitNumInt -> C.LitInt (fromInteger value); LitNumWord -> C.LitWord (fromInteger value)
    LitNumInt8 -> C.LitInt8 (fromInteger value); LitNumInt16 -> C.LitInt16 (fromInteger value)
    LitNumInt32 -> C.LitInt32 (fromInteger value); LitNumInt64 -> C.LitInt64 (fromInteger value)
    LitNumWord8 -> C.LitWord8 (fromInteger value); LitNumWord16 -> C.LitWord16 (fromInteger value)
    LitNumWord32 -> C.LitWord32 (fromInteger value); LitNumWord64 -> C.LitWord64 (fromInteger value)
    LitNumBigNat -> C.LitBigNat value
  LitChar c -> C.LitChar (fromIntegral (ord c))
  LitString s -> C.LitBytes s
  LitFloat f -> C.LitFloatBits (castFloatToWord32 (fromRational f))
  LitDouble f -> C.LitDoubleBits (castDoubleToWord64 (fromRational f))
  LitNullAddr -> C.LitNullAddr
  LitLabel symbol IsFunction -> C.LitFunctionAddr (bytes (unpackFS symbol))
  LitLabel symbol IsData -> C.LitDataAddr (bytes (unpackFS symbol))
  other -> C.LitUnsupported (bytes (pretty d other))

-- tagToEnum# carries a nominal result-type argument which ordinary application
-- export erases. Retain only a complete, concrete nullary family; never infer
-- an enum from a boxed runtime representation or a printed type name.
supportedEnum :: TyCon -> Bool
supportedEnum tc = isEnumerationTyCon tc && tyConArity tc == 0 && not (isFamInstTyCon tc)
  && not (null cs) && all ((== 0) . dataConRepArity) cs
  where cs = tyConDataCons tc

enumFamily :: TyCon -> C.EnumFamily
enumFamily tc = C.EnumFamily (bytes (nameKey (tyConName tc))) [bytes (nameKey (dataConName c)) | c <- tyConDataCons tc]

tagToEnumFamily :: CoreExpr -> Maybe TyCon
tagToEnumFamily e = case collectArgs e of
  (Var v,[Type ty,arg]) | Just TagToEnumOp <- isPrimOpId_maybe v
                        , not (isTypeArg arg), not (isCoArg arg)
                        , Just (tc,[]) <- splitTyConApp_maybe ty
                        , supportedEnum tc -> Just tc
  _ -> Nothing
  where isCoArg Coercion{} = True
        isCoArg _ = False
-- GHC.Tc.Instance.Class Note [DataToTag overview], DTT1-3. Do not unwrap
-- newtypes or guess a family from a physical pointer. The original primop
-- type application preserves the representation TyCon of a data instance.
supportedTagFamily :: TyCon -> Bool
supportedTagFamily tc = isBoxedDataTyCon tc && not (isNewTyCon tc || isTypeDataTyCon tc)
  && case tyConDataCons_maybe tc of Just (_:_) -> True; _ -> False

dataToTagFamily :: Ctx -> TyCon -> C.TagFamily
dataToTagFamily d tc = C.TagFamily (enumFamily tc) (fromIntegral (mAX_PTR_TAG platform)) (isSmallFamily platform (tyConFamilySize tc))
  where platform = targetPlatform (dynFlags d)

dataToTagApplication :: CoreExpr -> Maybe TyCon
dataToTagApplication e = case collectArgs e of
  (Var v,[Type _,Type ty,arg])
    | Just p <- isPrimOpId_maybe v
    , occNameString (primOpOcc p) `elem` ["dataToTagSmall#","dataToTagLarge#"]
    , Just _ <- typeLevity_maybe ty
    , eqType ty (exprType arg)
    , Just (tc,_) <- splitTyConApp_maybe ty
    , supportedTagFamily tc -> Just tc
  _ -> Nothing

exprCons :: CoreExpr -> [DataCon]
exprCons = \case
  Var v -> maybe [] (:[]) (isDataConWorkId_maybe v)
  e@(App a b) -> maybe [] tyConDataCons (tagToEnumFamily e) ++ maybe [] tyConDataCons (dataToTagApplication e) ++ exprCons a ++ exprCons b
  Lam _ e -> exprCons e
  Let b e -> concatMap (exprCons . snd) (flattenBind b) ++ exprCons e
  Case s _ _ as -> exprCons s ++ concat [case ac of DataAlt c -> c : exprCons e; _ -> exprCons e | Alt ac _ e <- as]
  Cast e _ -> exprCons e
  Tick _ e -> exprCons e
  _ -> []

exportModule :: [CommandLineOption] -> ModGuts -> CoreM ModGuts
exportModule opts guts = do
  flags <- getDynFlags
  hsc <- getHscEnv
  (d,result) <- liftIO $ optimizedModule flags opts guts
  let dir = case opts of [] -> "build/core"; x:_ -> x
      closureRoots = mapMaybe (stripPrefix "closure=") (drop 1 opts)
      modName = moduleNameString (moduleName (mg_module guts))
      binds = concatMap flattenBind (mg_binds guts)
  liftIO $ do
    let path = coreOutputPath opts dir (unitString (moduleUnit (mg_module guts))) modName
    createDirectoryIfMissing True (takeDirectory path)
    writeCoreOutput opts path result
    modifyIORef' sourceDefinitions ((d,binds):)
    let roots = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) `elem` closureRoots]
    if null roots then pure () else exportInterfaceClosure hsc opts dir d roots
  pure guts

-- | The same pre-Tidy serializer used by the plugin, without filesystem writes
-- or closure registration. Callers must supply genuine optimized ModGuts.
serializeOptimizedCore :: DynFlags -> [CommandLineOption] -> ModGuts -> IO String
serializeOptimizedCore flags opts guts = do
  (_,output) <- optimizedModule flags opts guts
  encoded <- encodeCoreOutput output
  utf8DecodeByteString <$> inspectCore output encoded

serializeOptimizedCoreCBD :: DynFlags -> [CommandLineOption] -> ModGuts -> IO BS.ByteString
serializeOptimizedCoreCBD flags opts guts = do
  (_,result) <- optimizedModule flags opts guts
  encodeCoreOutput result


-- A runtime bridge is exported only from the defining module, with the genuine
-- helper identities and checked reciprocal Any/SomeException function types.
foreignExceptionBridgeFields :: Ctx -> String -> String -> [(Id,CoreExpr)] -> [(String,J)]
foreignExceptionBridgeFields d unit modName binds
  | modName /= "THC.Internal.Exception" = []
  | otherwise = case (named "boxForeign", named "projectForeign") of
      ([boxer], [projector])
        | Just (raw,exception) <- unary (varType boxer)
        , Just (exception',raw') <- unary (varType projector)
        , eqType raw raw', eqType exception exception'
        , typeName exception == Just "ghc-internal:GHC.Internal.Exception.Type.SomeException"
        , typeName raw == Just "ghc-internal:GHC.Internal.Types.Any"
        -> [("foreignExceptionBridge",O
           [("schema",num (1::Int)),("unit",S unit),("module",S modName)
           ,("box",S (varKey d boxer)),("project",S (varKey d projector))
           ,("payloadType",S (unit ++ ":" ++ modName ++ ".ForeignException"))
           ,("exceptionType",S "ghc-internal:GHC.Internal.Exception.Type.SomeException")])]
      _ -> error ("THC.Internal.Exception lacks the genuine boxForeign/projectForeign bridge types: " ++ show [(occNameString (nameOccName (varName v)), pretty d (varType v), fmap (\(a,b) -> (typeName a,typeName b)) (unary (varType v))) | v <- named "boxForeign" ++ named "projectForeign"])
  where
    named name = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) == name]
    unary ty = case splitFunTys (expandTypeSynonyms ty) of
      ([a],b) -> Just (scaledThing a,b)
      _ -> Nothing
    typeName ty = nameKey . tyConName . fst <$> splitTyConApp_maybe ty

optimizedModule :: DynFlags -> [CommandLineOption] -> ModGuts -> IO (Ctx,CoreOutput)
optimizedModule flags opts guts = do
  sources <- loadSources (any (`elem` opts) ["source-notes", "source-spans"]) ("source-spans" `notElem` opts) (concatMap flattenBind (mg_binds guts))
  policies <- either fail pure (Backend.backendFields (mg_module guts)
    [(fmap nameOccName target,payload) | Annotation target payload <- mg_anns guts])
  exports <- staticExportFields (mg_module guts) (mg_anns guts) (mg_binds guts)
  let unit = unitString (moduleUnit (mg_module guts))
      d = Ctx flags (unit ++ ":" ++ moduleNameString (moduleName (mg_module guts))) (if "unit-qualified" `elem` opts then Just unit else Nothing) emptyVarSet emptyVarSet True True ("pretty-diagnostics" `elem` opts) sources []
      modName = moduleNameString (moduleName (mg_module guts))
      binds = concatMap flattenBind (mg_binds guts)
      cons = nubBy (\a b -> dataConName a == dataConName b) $ concatMap tyConDataCons (mg_tcs guts) ++ concatMap (exprCons . snd) binds
      result = O $
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName)
        , ("unit",S (unitString (moduleUnit (mg_module guts))))
        , ("boundary",S "optimized-Core-before-Tidy")
        , ("bindings",A []), ("constructors",A [])
        ] ++ (if prettyDiagnostics d then
          [("sourceCore",S (pretty d (mg_binds guts))), ("rules",S (pretty d (mg_rules guts)))] else []) ++
         policies ++ exports ++ foreignExceptionBridgeFields d unit modName binds
  pure (d,moduleOutput d result cons (concatMap (bindingGroup d) (mg_binds guts)) (programCalls d (mg_binds guts)))

-- Package rebuilding needs identities that agree with the newly emitted
-- interfaces, including Tidy-generated external names and implicit selectors.
-- Keep this optional boundary explicit; application/source exports stay at the
-- original optimized-Core-before-Tidy boundary.
exportLate :: LatePlugin
exportLate hsc opts pair@(guts,_)
  | not ("post-tidy" `elem` opts) = pure pair
  | otherwise = do
      (d,result) <- postTidyModule (hsc_dflags hsc) opts (cg_module guts) (cg_tycons guts) (cg_binds guts)
      let m = cg_module guts
          dir = case opts of [] -> "build/core"; x:_ -> x
          modName = moduleNameString (moduleName m)
          binds = concatMap flattenBind (cg_binds guts)
      let path = coreOutputPath opts dir (unitString (moduleUnit m)) modName
      createDirectoryIfMissing True (takeDirectory path)
      writeCoreOutput opts path result
      modifyIORef' sourceDefinitions ((d,binds):)
      let roots = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) `elem` mapMaybe (stripPrefix "closure=") opts]
      if null roots then pure () else exportInterfaceClosure hsc opts dir d roots
      pure pair

-- | Serialize actual post-Tidy Core, including Core hydrated from a complete
-- installed interface. JSON inspection is derived from CBD; optional original
-- pretty Core remains output-only. "source-notes" and
-- "unit-qualified" also affect this entry point. It neither writes files nor
-- registers plugin closure roots.
-- Foreign products are archival metadata, not executable registration. The
-- schema bump prevents older runtimes/auditors from silently ignoring them.
serializePostTidyCore :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> IO String
serializePostTidyCore flags opts m tycons program foreignArtifacts =
  serializePostTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts []

-- | The installed-interface path retains typed module annotations. CgGuts in
-- the late source plugin does not: use the emitted interface to recover this
-- optional inventory, never a process-local table or a previous export file.
serializePostTidyCoreWithAnnotations :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> [Annotation] -> IO String
serializePostTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts annotations =
  utf8DecodeByteString <$> serializePostTidyCoreWithAnnotationsBytes flags opts m tycons program foreignArtifacts annotations

-- | JSON inspection is an output-only view of the same typed binary artifact.
serializePostTidyCoreWithAnnotationsBytes :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> [Annotation] -> IO BS.ByteString
serializePostTidyCoreWithAnnotationsBytes flags opts m tycons program foreignArtifacts annotations = do
  output <- postTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts annotations
  encoded <- encodeCoreOutput output
  inspectCore output encoded

-- Normal compiler and interface publication always use these binary APIs,
-- including when pretty diagnostics are requested.
serializePostTidyCoreCBD :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> IO BS.ByteString
serializePostTidyCoreCBD flags opts m tycons program foreignArtifacts =
  serializePostTidyCoreWithAnnotationsCBD flags opts m tycons program foreignArtifacts []

serializePostTidyCoreWithAnnotationsCBD :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> [Annotation] -> IO BS.ByteString
serializePostTidyCoreWithAnnotationsCBD flags opts m tycons program foreignArtifacts annotations =
  postTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts annotations >>= encodeCoreOutput

inspectCore :: CoreOutput -> BS.ByteString -> IO BS.ByteString
inspectCore (CoreOutput metadata _ _ _ _) encoded = do
  value <- either fail pure (readModuleValue encoded)
  -- Optional original pretty Core/rules are output-only diagnostics. They
  -- never supply executable fields or pass through the typed body reader.
  let extras = case metadata of
        O fields -> moduleValue (O (filter ((`elem` ["sourceCore","rules"]) . fst) fields))
        _ -> error "Core metadata requires a module object"
      inspected = case (value,extras) of
        (Aeson.Object fields,Aeson.Object diagnostics) -> Aeson.Object (KeyMap.union diagnostics fields)
        _ -> error "Compact inspection requires a module object"
  pure (BS.snoc (BL.toStrict (Aeson.encode inspected)) 10)

postTidyCoreWithAnnotations :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> [Annotation] -> IO CoreOutput
postTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts annotations = do
  policies <- either fail pure (Backend.backendFields m
    [(fmap nameOccName target,payload) | Annotation target payload <- annotations])
  associations <- either (ioError . userError . ("THC: " ++)) pure
    (Exports.readStaticExports m annotations program)
  -- A boxed identity need not inspect or construct its argument in Core. Host
  -- codecs still need the genuine constructor layout of its declared type.
  let exported = case associations of
        Nothing -> []
        Just (Exports.StaticExports _ _ _ records) -> map (exportKey . Exports.exportBinder) records
      signatureTycons = concat [foreignSignatureTycons (idType binder)
        | (binder, _) <- flattenBinds program, nameKey (varName binder) `elem` exported]
  (_,CoreOutput result constructors bindings catalog calls) <- postTidyModule flags opts m (tycons ++ signatureTycons) program
  exports <- staticExportFields m annotations program
  provenance <- exportProvenanceFields m annotations program foreignArtifacts
  imports <- importProvenanceFields m annotations foreignArtifacts
  let annotated = case result of O fields -> O (fields ++ policies ++ exports ++ provenance ++ imports); _ -> result
  pure (CoreOutput (withForeignArtifacts foreignArtifacts annotated) constructors bindings catalog calls)
  where
    exportKey (Exports.ExportName unit modName occurrence _) = unit ++ ":" ++ modName ++ "." ++ occurrence

-- GHC's representation view erases newtypes, including IO's state transformer.
-- Foreign-export types are already checked closed/monomorphic by the producer.
foreignSignatureTycons :: Type -> [TyCon]
foreignSignatureTycons original =
  let ty = unwrapType original
      (parameters, result) = splitFunTys ty
  in if not (null parameters)
    then concatMap (foreignSignatureTycons . scaledThing) parameters ++ foreignSignatureTycons result
    else case splitTyConApp_maybe ty of
      Just (constructorType, arguments) -> [constructorType | isBoxedDataTyCon constructorType] ++
        concatMap foreignSignatureTycons arguments
      Nothing -> []

exportProvenanceFields :: Module -> [Annotation] -> CoreProgram -> ForeignCore.IfaceForeign -> IO [(String,J)]
exportProvenanceFields owner annotations program original = do
  associations <- checked (Exports.readStaticExports owner annotations program)
  case associations of
    Nothing -> pure []
    Just (Exports.StaticExports _ _ _ []) -> pure []
    Just (Exports.StaticExports _ _ _ exports) -> do
      inventory <- staticExportFields owner annotations program
      verdict <- checked (ExportProvenance.inspectProvenance owner annotations
        (Just (map Exports.exportBinder exports)) original)
      let details = case verdict of
            ExportProvenance.UnknownProvenance reason -> [("status",S "unclassified"),("reason",S reason)]
            ExportProvenance.RejectedProvenance reason -> [("status",S "rejected"),("reason",S reason)]
            ExportProvenance.VerifiedRetainedRegistration _ roots ->
              [("status",S "verified"),("roots",A (map identity roots)),
               ("wordBits",num (64::Int)),("expectedForeign",foreignArtifactRecord original),
               ("expectedExports",case lookup "staticForeignExports" inventory of
                  Just value -> value
                  Nothing -> error "THC verified registration lost its export inventory")]
          profile = case verdict of
            ExportProvenance.VerifiedRetainedRegistration 3 _ -> "ghc-9.14.1-thc-only-native-static-c-products-v3"
            ExportProvenance.VerifiedRetainedRegistration 2 _ -> "ghc-9.14.1-thc-only-native-static-ccall-imports-v2"
            _ -> "ghc-9.14.1-thc-only-native-static-ccall-v1"
      pure [("staticForeignExportRegistration",O
        ([("schema",num (2::Int)),("scope",S "retained-foreign-products"),("execution",S "not-linked"),
          ("profile",S profile)] ++ details))]
  where
    checked = either (ioError . userError . ("THC: " ++)) pure
    identity (Exports.ExportName unit modName occurrence namespace) = O
      [("unit",S unit),("module",S modName),("occurrence",S occurrence),("namespace",S namespace)]

-- The complete archived product is compared before producing managed-stub
-- evidence. The execution label remains not-linked: no C code is registered.
importProvenanceFields :: Module -> [Annotation] -> ForeignCore.IfaceForeign -> IO [(String,J)]
importProvenanceFields owner annotations original = do
  verdict <- either (ioError . userError . ("THC: " ++)) pure
    (ImportProvenance.inspectImports owner annotations original)
  pure $ case verdict of
    Nothing -> []
    Just (ImportProvenance.VerifiedMixed [] [] [] _) -> []
    Just value ->
      let schema = case value of ImportProvenance.VerifiedMixed {} -> 4; ImportProvenance.VerifiedWrappers {} -> 3; ImportProvenance.Verified _ (_:_) -> 2; _ -> 1
          imports = case value of
            ImportProvenance.Verified xs _ -> xs
            ImportProvenance.VerifiedWrappers xs _ _ -> xs
            ImportProvenance.VerifiedMixed xs _ _ _ -> xs
            _ -> []
          primitive (ImportProvenance.Import _ _ _ _ _ conv _ _ _ _) = conv == "prim"
          profile = if any primitive imports then "ghc-9.14.1-thc-stock-static-foreign-imports-v2"
            else "ghc-9.14.1-thc-only-static-c-imports-v1"
          record = O (("schema",num (schema::Int)) : ("profile",S profile) : common ++ details value)
          associations = case value of
            ImportProvenance.Verified [] [] -> []
            _ -> [("staticForeignImports", record)]
          emptyImports = case value of
            ImportProvenance.VerifiedMixed _ _ _ (ImportProvenance.Product Nothing []) -> True
            ImportProvenance.VerifiedMixed _ _ _ (ImportProvenance.Product (Just ("","",[],[])) []) -> True
            ImportProvenance.VerifiedMixed {} -> False
            _ -> emptyProduct
      in associations ++ [("staticForeignImportStubs", record) | not emptyImports]
  where
    emptyProduct = case original of
      ForeignCore.IfaceForeign Nothing [] -> True
      ForeignCore.IfaceForeign (Just (ForeignCore.IfaceCStubs "" "" [] [])) [] -> True
      _ -> False
    common = [("scope",S "retained-static-import-products"),("execution",S "not-linked"),
      ("unit",S (unitString (moduleUnit owner))),("module",S (moduleNameString (moduleName owner)))]
    details (ImportProvenance.Unknown reason) = [("status",S "unclassified"),("reason",S reason)]
    details (ImportProvenance.Rejected reason) = [("status",S "rejected"),("reason",S reason)]
    details (ImportProvenance.Verified imports addresses) = [("status",S "verified"),("wordBits",num (64::Int)),
      ("expectedForeign",foreignArtifactRecord original),("imports",A (map imported imports)),
      ("expectedCalls",A [])] ++ [("addresses",A (map address addresses)) | not (null addresses)]
    details (ImportProvenance.VerifiedWrappers imports addresses wrappers) =
      details (ImportProvenance.Verified imports []) ++
      [("addresses",A (map address addresses)),("wrappers",A (map wrapper wrappers))]
    details (ImportProvenance.VerifiedMixed imports addresses wrappers product') =
      details (ImportProvenance.VerifiedWrappers imports addresses wrappers) ++
      [("importForeign",importProduct product')]
    importProduct (ImportProvenance.Product stubs files) = O
      [("schema",num (1::Int)),("execution",S "not-linked"),
       ("stubs",maybe Z (\(header,source,initializers,finalizers) -> O
         [("header",S header),("source",S source),("initializers",A (map label initializers)),
          ("finalizers",A (map label finalizers))]) stubs),
       ("files",A [O [("language",S language),("source",S source),("extension",S extension)]
         | (language,source,extension) <- files])]
    label (initializer,unit,name,symbol) = O
      [("isInitializer",B initializer),("unit",S unit),("module",S name),("name",S symbol)]
    identity (Exports.ExportName unit modName occurrence namespace) = O
      [("unit",S unit),("module",S modName),("occurrence",S occurrence),("namespace",S namespace)]
    ty (ImportProvenance.ImportTyCon name arguments) = O [("kind",S "tycon"),("name",identity name),("arguments",A (map ty arguments))]
    ty (ImportProvenance.ImportApp function argument) = O [("kind",S "application"),("function",ty function),("argument",ty argument)]
    ty (ImportProvenance.ImportArrow multiplicity argument result) = O
      [("kind",S "function"),("multiplicity",ty multiplicity),("argument",ty argument),("result",ty result)]
    ty (ImportProvenance.ImportVariable index) = O [("kind",S "bound-variable"),("index",num index)]
    ty (ImportProvenance.ImportForall kind body) = O [("kind",S "forall"),("binderKind",ty kind),("body",ty body)]
    imported (ImportProvenance.Import binder header symbol unit function conv safe declared normalized emitted) = O
      [("binder",identity binder),("header",maybe Z S header),("symbol",S symbol),("unit",maybe Z S unit),
       ("isFunction",B function),("convention",S conv),("safety",S safe),("declaredType",ty declared),
       ("normalizedType",ty normalized),("normalizationRole",S "representational"),("emitted",call emitted)]
    address (ImportProvenance.Address binder header symbol function conv declared normalized callback) = O
      [("binder",identity binder),("header",maybe Z S header),("symbol",S symbol),
       ("isFunction",B function),("convention",S conv),("declaredType",ty declared),
       ("normalizedType",ty normalized),("normalizationRole",S "representational"),
       ("callback",maybe Z (\(arguments,result) -> O [("arguments",A (map S arguments)),("result",S result)]) callback)]
    wrapper (ImportProvenance.Wrapper binder helper conv declared normalized arguments result io encoding) = O
      [("binder",identity binder),("helper",S helper),("convention",S conv),
       ("declaredType",ty declared),("normalizedType",ty normalized),("normalizationRole",S "representational"),
       ("arguments",A (map ty arguments)),("result",ty result),("effect",S (if io then "io" else "pure")),
       ("typeString",S encoding)]
    call (ImportProvenance.Call symbol unit conv safe arguments result) = O
      [("symbol",S symbol),("unit",maybe Z S unit),("convention",S conv),("safety",S safe),
       ("arguments",A (map S arguments)),("result",A (map S result))]

staticExportFields :: Module -> [Annotation] -> CoreProgram -> IO [(String,J)]
staticExportFields owner annotations bindings = do
  record <- either (ioError . userError . ("THC: " ++)) pure
    (Exports.readStaticExports owner annotations bindings)
  pure $ case record of
    Nothing -> []
    Just (Exports.StaticExports _ _ _ []) -> []
    Just (Exports.StaticExports version unit modName exports) -> [("staticForeignExports",O
      [("schema",num version),("producer",S "THC.Plugin/typeCheckResultAction"),
       ("scope",S "static-export-associations"),("execution",S "not-linked"),
       ("unit",S unit),("module",S modName),("exports",A (map entry exports))])]
  where
    identity (Exports.ExportName unit modName occurrence namespace) = O
      [("unit",S unit),("module",S modName),("occurrence",S occurrence),("namespace",S namespace)]
    ty (Exports.ExportTyCon name arguments) = O
      [("kind",S "tycon"),("name",identity name),("arguments",A (map ty arguments))]
    ty (Exports.ExportApp function argument) = O
      [("kind",S "application"),("function",ty function),("argument",ty argument)]
    ty (Exports.ExportArrow multiplicity argument result) = O
      [("kind",S "function"),("multiplicity",ty multiplicity),("argument",ty argument),("result",ty result)]
    entry value = O
      [("binder",identity (Exports.exportBinder value)),("symbol",S (Exports.exportSymbol value)),
       ("convention",S (Exports.exportConvention value)),("declaredType",ty (Exports.exportDeclared value)),
       ("normalizedType",ty (Exports.exportNormalized value)),("normalizationRole",S "representational"),
       ("arguments",A (map ty (Exports.exportArguments value))),("result",ty (Exports.exportResult value)),
       ("effect",S (if Exports.exportIO value then "io" else "pure"))]

withForeignArtifacts :: ForeignCore.IfaceForeign -> J -> J
withForeignArtifacts (ForeignCore.IfaceForeign Nothing []) result = result
withForeignArtifacts (ForeignCore.IfaceForeign (Just (ForeignCore.IfaceCStubs "" "" [] [])) []) result = result
withForeignArtifacts original (O fields) = O $
  ("schema",num (2::Int)) : ("foreign",foreignArtifactRecord original) :
  filter ((/= "schema") . fst) fields
withForeignArtifacts _ _ = error "THC foreign artifacts require a module object"

-- One structural encoder binds the archived product to its verified evidence.
foreignArtifactRecord :: ForeignCore.IfaceForeign -> J
foreignArtifactRecord (ForeignCore.IfaceForeign stubs files) = O
  [("schema",num (1::Int)),("execution",S "not-linked"),
   ("stubs",maybe Z stub stubs),("files",A (map file files))]
  where
    stub (ForeignCore.IfaceCStubs header source initializers finalizers) = O
      [("header",S header),("source",S source),
       ("initializers",A (map label initializers)),("finalizers",A (map label finalizers))]
    label (ForeignCore.IfaceCLabel value) = O
      [("isInitializer",B (csl_is_initializer value)),
       ("unit",S (unitString (moduleUnit (csl_module value)))),
       ("module",S (moduleNameString (moduleName (csl_module value)))),
       ("name",S (unpackFS (csl_name value)))]
    file (ForeignCore.IfaceForeignFile sourceLanguage source extension) = O
      [("language",S (show sourceLanguage)),("source",S source),("extension",S extension)]

postTidyModule :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> IO (Ctx,CoreOutput)
postTidyModule flags opts m tycons program = do
  sources <- loadSources (any (`elem` opts) ["source-notes", "source-spans"]) ("source-spans" `notElem` opts) binds
  let unit = unitString (moduleUnit m)
      modName = moduleNameString (moduleName m)
      d = Ctx flags (unit ++ ":" ++ modName) (if "unit-qualified" `elem` opts then Just unit else Nothing)
            emptyVarSet emptyVarSet True False ("pretty-diagnostics" `elem` opts) sources []
      cons = nubBy (\a b -> dataConName a == dataConName b)
        (concatMap tyConDataCons tycons ++ concatMap (exprCons . snd) binds)
      result = O $
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName), ("unit",S unit)
        , ("boundary",S "optimized-Core-after-Tidy-before-CorePrep")
        , ("bindings",A []), ("constructors",A [])
        ] ++ [("sourceCore",S (pretty d program)) | prettyDiagnostics d] ++
         foreignExceptionBridgeFields d unit modName binds
  pure (d,moduleOutput d result cons (concatMap (bindingGroup d) program) (programCalls d program))
  where binds = concatMap flattenBind program

-- Source definitions from earlier modules of this same --make invocation let
-- the dependency walk use their complete bodies, even when GHC did not retain
-- an inline unfolding. No pretty-printed Core is parsed or reconstructed.
{-# NOINLINE sourceDefinitions #-}
sourceDefinitions :: IORef [(Ctx,[(Id,CoreExpr)])]
sourceDefinitions = unsafePerformIO (newIORef [])

exportInterfaceClosure :: HscEnv -> [CommandLineOption] -> FilePath -> Ctx -> [Id] -> IO ()
exportInterfaceClosure hsc opts dir rootCtx roots = do
  modules <- readIORef sourceDefinitions
  let sourceEnv = mkVarEnv [(v,(d,e)) | (d,bs) <- modules, (v,e) <- bs]
      -- A driver can supply complete, checked modules alongside this closure.
      -- Do not also import their partial interface unfoldings. Missing supplied
      -- definitions still fail the ordinary final reachable-Core audit.
      provided = mapMaybe (stripPrefix "closure-provided-module=") opts
      providedId v = case nameModule_maybe (varName v) of
        Nothing -> False
        Just m -> (unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m)) `elem` provided
      -- exprFreeVars deliberately omits global IDs. Dependency discovery
      -- instead walks all term references with explicit lexical scopes.
      refs = go emptyVarSet
        where
          go bound original
            | Just lowered <- wiredCase original = go bound lowered
            | Just lowered <- wiredApplication original = go bound lowered
            | Var v <- original, isWiredVoid v = []
            | Var v <- original, Just lowered <- wiredRhs v = go bound lowered
            | otherwise = raw bound original
          raw bound = \case
            Var v | isId v && not (isCoVar v) && not (v `elemVarSet` bound) -> [v]
                  | otherwise -> []
            App f x -> go bound f ++ go bound x
            Lam v e -> go (extendVarSet bound v) e
            Let b e ->
              let bound' = extendVarSetList bound (map fst (flattenBind b))
                  rhsBound = case b of Rec{} -> bound'; _ -> bound
              in concatMap (go rhsBound . snd) (flattenBind b) ++ go bound' e
            Case e v _ alts -> go bound e ++ concat [go (extendVarSetList (extendVarSet bound v) bs) rhs | Alt _ bs rhs <- alts]
            Cast e _ -> go bound e
            Tick _ e -> go bound e
            _ -> []
      originCtx v = case nameModule_maybe (varName v) of
        Nothing -> rootCtx
        Just m -> rootCtx { modulePrefix = unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m) }
      -- The RTS raises this original closure for both descriptor waits, even
      -- though it is absent from the primop's explicit Core operands.
      waitPayloadId = do
        name <- initIfaceCheck (text "THC implicit descriptor-wait exception") hsc $
          lookupOrig (mkModule ghcInternalUnit (mkModuleName "GHC.Internal.Event.Thread"))
            (mkVarOcc "blockedOnBadFD")
        thing <- lookupGlobal hsc name
        case thing of
          AnId v -> pure v
          _ -> error "THC implicit descriptor-wait exception is not an Id"
      blockedPayloadId occurrence = do
        name <- initIfaceCheck (text "THC implicit blocked-thread exception") hsc $
          lookupOrig (mkModule ghcInternalUnit (mkModuleName "GHC.Internal.IO.Exception")) (mkVarOcc occurrence)
        thing <- lookupGlobal hsc name
        case thing of
          AnId v -> pure v
          _ -> error "THC implicit blocked-thread exception is not an Id"
      stmPayloadId = do
        name <- initIfaceCheck (text "THC implicit nested atomically exception") hsc $
          lookupOrig (mkModule ghcInternalUnit (mkModuleName "GHC.Internal.Control.Exception.Base"))
            (mkVarOcc "nestedAtomically")
        thing <- lookupGlobal hsc name
        case thing of
          AnId v -> pure v
          _ -> error "THC implicit nested atomically exception is not an Id"
      -- Exception.cmm supplies these closures implicitly. Resolve their real
      -- installed Ids/unfoldings in the current GHC session so the normal
      -- dependency walk retains the SomeException dictionary and Typeable data.
      arithmeticException op = lookup (occNameString (primOpOcc op))
        [("raiseDivZero#", "divZeroException"), ("raiseOverflow#", "overflowException"),
         ("raiseUnderflow#", "underflowException")]
      compactExceptionId occurrence = do
        name <- initIfaceCheck (text "THC implicit compaction exception") hsc $
          lookupOrig (mkModule ghcInternalUnit (mkModuleName "GHC.Internal.IO.Exception")) (mkVarOcc occurrence)
        thing <- lookupGlobal hsc name
        case thing of
          AnId v -> pure v
          _ -> error "THC implicit compaction exception is not an Id"
      exceptionId occurrence = do
        name <- initIfaceCheck (text "THC implicit arithmetic exception") hsc $
          lookupOrig (mkModule ghcInternalUnit (mkModuleName "GHC.Internal.Exception.Type")) (mkVarOcc occurrence)
        thing <- lookupGlobal hsc name
        case thing of
          AnId v -> pure v
          _ -> error "THC implicit arithmetic exception is not an Id"
      walk _ [] found missing = pure (reverse found, reverse missing)
      walk seen (v:todo) found missing
        | v `elemVarSet` seen = walk seen todo found missing
        | Just op <- isPrimOpId_maybe v
        , occNameString (primOpOcc op) `elem` ["compactAdd#", "compactAddWithSharing#"] = do
            payloads <- mapM compactExceptionId ["cannotCompactFunction", "cannotCompactPinned", "cannotCompactMutable"]
            walk seen' (payloads ++ todo) found missing
        | Just op <- isPrimOpId_maybe v
        , occNameString (primOpOcc op) == "atomically#" = do
            payload <- stmPayloadId
            blocked <- blockedPayloadId "blockedIndefinitelyOnSTM"
            walk seen' (payload : blocked : todo) found missing
        | Just op <- isPrimOpId_maybe v
        , occNameString (primOpOcc op) `elem` ["takeMVar#", "readMVar#", "putMVar#"] = do
            payload <- blockedPayloadId "blockedIndefinitelyOnMVar"
            walk seen' (payload : todo) found missing
        | Just op <- isPrimOpId_maybe v
        , occNameString (primOpOcc op) `elem` ["waitRead#", "waitWrite#"] = do
            payload <- waitPayloadId
            walk seen' (payload : todo) found missing
        | Just op <- isPrimOpId_maybe v
        , Just occurrence <- arithmeticException op = do
            payload <- exceptionId occurrence
            walk seen' (payload : todo) found missing
        | Just _ <- isPrimOpId_maybe v = walk seen' todo found missing
        | Just _ <- isDataConWorkId_maybe v = walk seen' todo found missing
        | polyglotForeign v = walk seen' todo found missing
        | Just (_,e) <- lookupVarEnv sourceEnv v = walk seen' (refs e ++ todo) found missing
        | providedId v = walk seen' todo found missing
        | Just e <- maybeUnfoldingTemplate (realIdUnfolding v) =
            let kind = case realIdUnfolding v of DFunUnfolding{} -> "interface-dfun-unfolding"; _ -> "interface-core-unfolding"
            in walk seen' (refs e ++ todo) ((originCtx v,v,e,kind):found) missing
        | otherwise = walk seen' todo found ((originCtx v,v):missing)
        where seen' = extendVarSet seen v
  (imports,missing) <- walk emptyVarSet roots [] []
  policies <- Backend.closureFields hsc [(varName v,varKey d v) | (d,v,_,_) <- imports]
  let
      -- Interfaces do not retain complete source recursive-group boundaries.
      -- Conservatively forbid speculation of every imported definition while
      -- exporting their RHSs; this preserves recursive dictionary guards.
      recIds = mkVarSet [v | (_,v,_,_) <- imports]
  sources <- loadSources (case sourceTable rootCtx of Just _ -> True; _ -> False) ("source-spans" `notElem` opts) [(v,e) | (_,v,e,_) <- imports]
  let closureCtx = rootCtx { sourceTable = sources, sourceUnit = if "unit-qualified" `elem` opts then Just "dependency-closure" else Nothing }
  let importedBinding (d,v,e,_) = buildBinding (d { recursiveIds = recIds, deriveCBVContracts = False, sourceTable = sources, sourceUnit = sourceUnit closureCtx, activeSources = [] }) (v,e)
      cons = nubBy (\a b -> dataConName a == dataConName b) (concat [exprCons e | (_,_,e,_) <- imports])
      result = O $
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S "THC.InterfaceClosure"), ("unit",S "dependency-closure")
        , ("boundary",S "actual-interface-unfoldings"), ("roots",A [S (varKey rootCtx v) | v <- roots])
        , ("sourceModules",A [S (modulePrefix d) | (d,_) <- reverse modules])
        , ("bindings",A [O [("id",S (varKey d v)),("origin",S kind),("originModule",S (modulePrefix d))] | (d,v,_,kind) <- imports]), ("constructors",A [])
        , ("missingDefinitions",A [O [("id",S (varKey d v)),("type",S (pretty d (varType v))), ("reason",S "No executable interface unfolding; source export required")] | (d,v) <- missing])
        ] ++ [("sourceCore",S (pretty rootCtx [(v,e) | (_,v,e,_) <- imports])) | prettyDiagnostics rootCtx] ++
        [("providedModules", A (map S provided)) | not (null provided)] ++ policies
  let path = coreOutputPath opts dir "dependency-closure" "THC.InterfaceClosure"
  createDirectoryIfMissing True (takeDirectory path)
  writeCoreOutput opts path (moduleOutput closureCtx result cons (map importedBinding imports)
    (concat [coreCalls (d { deriveCBVContracts = False }) e | (d,_,e,_) <- imports]))
  putStrLn ("THC interface closure: " ++ show (length imports) ++ " actual unfoldings, " ++ show (length missing) ++ " missing source definitions")
