-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE LambdaCase #-}
-- | GHC 9.14.1 plugin and direct serializers for THC's executable Core format.
--
-- Use @-fplugin=THC.Plugin@ with the output directory as the first plugin
-- option. Package exports require @post-tidy@ and @unit-qualified@; add
-- @source-notes@ to retain source-location metadata. The direct serializers
-- consume genuine GHC Core and do not establish runtime support or link native
-- foreign products. This library is tied to the selected GHC API version.
module THC.Plugin (plugin, serializeOptimizedCore, serializePostTidyCore, serializePostTidyCoreWithAnnotations) where

import GHC.Plugins
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
import GHC.Utils.Encoding.UTF8 (utf8DecodeByteString)
import GHC.Builtin.Names (ioTyConName)
import GHC.Builtin.Types (intTy, doubleTy, unitTy)
import GHC.Tc.Types (TcGblEnv(..))
import qualified THC.Sources as Sources
import qualified THC.CBV as CBV
import qualified THC.Demands as Demands
import qualified THC.ForeignExports as Exports
import qualified THC.ForeignExportProvenance as ExportProvenance
import qualified THC.ForeignImportProvenance as ImportProvenance
import THC.Wired (wiredApplication, wiredCase, wiredRhs, preservesWiredTypes, isWiredVoid)
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
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing)
import System.FilePath ((</>), takeDirectory)

-- | Export executable trees from GHC's Core pipeline. Without @post-tidy@ the
-- pass runs last before Tidy; that option selects the late plugin boundary
-- after Tidy and before CorePrep, where package identities agree with emitted
-- interfaces. The first option is the destination directory (default
-- @build/core@). GHC compilations using the plugin are forced to recompile so
-- the export is not silently skipped by native recompilation checks.
plugin :: Plugin
plugin = defaultPlugin
  { parsedResultAction = rewriteJavaScriptImports
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

data J = O [(String,J)] | A [J] | S String | N Integer | B Bool | Z

json :: J -> String
json value = render value ""
  where
    -- Append directly to the enclosing output instead of copying each child's
    -- complete String at every ancestor. Deep Core with source-note metadata
    -- otherwise spends minutes repeatedly copying the same JSON characters.
    render :: J -> ShowS
    render (O xs) = showChar '{' . separated field xs . showChar '}'
    render (A xs) = showChar '[' . separated render xs . showChar ']'
    render (S s) = showChar '"' . foldr ((.) . escape) id s . showChar '"'
    render (N n) = shows n
    render (B b) = showString (if b then "true" else "false")
    render Z = showString "null"
    field (key, item) = render (S key) . showChar ':' . render item
    separated :: (a -> ShowS) -> [a] -> ShowS
    separated _ [] = id
    separated item (x:xs) = item x . foldr (\y rest -> showChar ',' . item y . rest) id xs
    escape '"' = showString "\\\""
    escape '\\' = showString "\\\\"
    escape c | ord c < 32 = showString "\\u" . showString (replicate (4-length h) '0') . showString h
      where h = showHex (ord c) ""
    escape c = showChar c

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
  , sourceTable :: Maybe Sources.SourceTable
  , activeSources :: [String]
  }

-- Source notes are side metadata: they never become executable wrappers.
sourceFields :: Ctx -> [(String,J)]
sourceFields d = case activeSources d of
  [] -> []
  notes -> [("source",S (last notes)),("sourceNotes",A (map S notes))]

binderSource :: Ctx -> Var -> [(String,J)]
binderSource d v = case (sourceTable d, Sources.binderNote v) of
  (Just table, Just note) | Sources.hasNote table note -> [("source",S (sourceKey d (Sources.noteKey note)))]
  _ -> []

underTick :: Ctx -> CoreTickish -> Ctx
underTick d tick = case (sourceTable d, Sources.tickNote tick) of
  (Just table, Just note) | Sources.hasNote table note ->
    d { activeSources = activeSources d ++ [sourceKey d (Sources.noteKey note)] }
  _ -> d

sourceKey :: Ctx -> String -> String
sourceKey d key = maybe key (\unit -> show (unit,key)) (sourceUnit d)

sourceTableFields :: Ctx -> [(String,J)]
sourceTableFields d = case sourceTable d of
  Nothing -> []
  Just table ->
   [("sourceFiles",A [O [("id",S (sourceKey d (Sources.sourceFileId file))),("path",S (Sources.sourceFilePath file)),
                       ("content",maybe Z S (Sources.sourceFileContent file))] | file <- Sources.sourceFiles table])
   ,("sourceSpans",A (map spanRecord (Sources.sourceSpans table)))]
  where
    spanRecord record = let Sources.Note span label = Sources.sourceSpanNote record in O
      [("id",S (sourceKey d (Sources.sourceSpanId record))),("file",S (sourceKey d (Sources.sourceSpanFile record)))
      ,("startLine",num (srcSpanStartLine span)),("startColumn",num (srcSpanStartCol span))
      ,("endLine",num (srcSpanEndLine span)),("endColumn",num (srcSpanEndCol span))
      ,("charIndex",maybe Z num (Sources.sourceCharIndex record))
      ,("charLength",maybe Z num (Sources.sourceCharLength record)),("label",S label)]

loadSources :: Bool -> [(Id,CoreExpr)] -> IO (Maybe Sources.SourceTable)
loadSources enabled bindings = if enabled then Just <$> Sources.buildSourceTable bindings else pure Nothing

pretty :: Outputable a => Ctx -> a -> String
pretty d = showSDoc (dynFlags d) . ppr

nameKey :: Name -> String
nameKey n = case nameModule_maybe n of
  Just m -> unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m) ++ "." ++ occNameString (nameOccName n)
  Nothing -> occNameString (nameOccName n) ++ "_" ++ showSDocUnsafe (ppr (nameUnique n))

-- Cabal can compile the same module name in several distinct units. Keep the
-- historical flat layout for fixtures, but let package exports preserve the
-- exact GHC unit ID in their path. Escaping is injective and cannot traverse
-- out of the export root, even for an unusual unit ID.
coreOutputPath :: [CommandLineOption] -> FilePath -> String -> String -> FilePath
coreOutputPath opts dir unit modName
  | "unit-qualified" `elem` opts = dir </> "units" </> ("u-" ++ concatMap escapeUnit unit) </> modName ++ ".json"
  | otherwise = dir </> modName ++ ".json"
  where
    escapeUnit c
      | asciiAlphaNum c || c `elem` ("-._" :: String) = [c]
      | otherwise = '%' : showHex (ord c) ";"
    asciiAlphaNum c = ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9')

varKey :: Ctx -> Var -> String
varKey d v = case nameModule_maybe (varName v) of
  Just _ -> nameKey (varName v)
  Nothing -> modulePrefix d ++ "." ++ nameKey (varName v)

lifted :: Var -> J
lifted v | isCoVar v = B False
         | otherwise = liftedType (varType v)

liftedType :: Type -> J
liftedType ty = case typeLevity_maybe ty of
  Just Lifted -> B True
  Just Unlifted -> B False
  Nothing -> Z

-- Runtime categories are evidence from GHC types, never parsed pretty text.
-- A category describes a value after evaluation; evaluated is a separate WHNF
-- fact, not demand/strictness and not permission to speculate an arbitrary RHS.
unknownRep :: J
unknownRep = unknownRepWithState False

unknownRepWithState :: Bool -> J
unknownRepWithState evaluated = O [("primReps",Z),("kind",S "unknown"),("evaluated",B evaluated)]

voidRep :: J
voidRep = O [("primReps",A []),("kind",S "void"),("evaluated",B True)]

-- In 9.14.1 the TupleRep/SumRep callbacks underneath typePrimRep_maybe
-- still call partial runtimeRepPrimRep for their children. A valid Core type
-- such as forall r (a :: TYPE r). Box -> (# a, Int# #) can therefore panic
-- when its result is queried. Keep the entire unresolved physical vector
-- unknown, even when some logical components have known representations.
typePrimReps :: Type -> Maybe [PrimRep]
typePrimReps ty
  | Just _ <- aggregateRuntimeKind ty
  , not (typeHasFixedRuntimeRep ty) = Nothing
  | otherwise = typePrimRep_maybe ty

-- A type variable or opaque family can expose an aggregate RuntimeRep without
-- exposing logical payload types. This is aggregate evidence, even for zero
-- or one physical registers, but it is not evidence for a component layout.
aggregateRuntimeKind :: Type -> Maybe (String,String)
aggregateRuntimeKind ty = case splitTyConApp_maybe (getRuntimeRep ty) of
  Just (tc,_)
    | tc == tupleRepDataConTyCon -> Just ("unboxed-tuple","components")
    | tc == sumRepDataConTyCon -> Just ("unboxed-sum","alternatives")
  _ -> Nothing

typeRep :: Type -> Bool -> J
typeRep ty evaluated = O $
  [("primReps",maybe Z (A . map (S . show)) reps),("kind",S kind),("evaluated",B evaluated)]
  ++ aggregateFields ++ vectorFields
  where
    reps = typePrimReps ty
    vectorFields = case reps of
      Just [VecRep lanes element] -> [("vector",O [("lanes",num lanes),("element",S (show element))])]
      _ -> []
    -- Type abstraction erases, but a newtype/family is not evidence for either
    -- a data object or a closure. isBoxedDataTyCon makes that distinction in GHC.
    (_,rho) = splitForAllTyVars ty
    -- Physical register counts do not distinguish a singleton/empty unboxed
    -- tuple from a scalar/state token. Preserve the logical GHC type evidence
    -- even where no constructor is reachable (for example an identity).
    -- GHC's cycle-checked representation view exposes newtype aliases as
    -- well as synonyms/casts/foralls. Use it only for aggregate evidence:
    -- a scalar newtype does not gain a boxed data/closure classification.
    aggregateFields = case splitTyConApp_maybe (unwrapType ty) of
      Just (tc,args)
        | isUnboxedTupleTyCon tc -> aggregate "unboxed-tuple" "components" args
        | isUnboxedSumTyCon tc -> aggregate "unboxed-sum" "alternatives" args
        -- State# is a known primitive with TupleRep '[] too. Its zero-width
        -- token representation is not a logical empty tuple. This also keeps
        -- newtype aliases of known primitives scalar after unwrapType.
        | isPrimTyCon tc -> []
      _ -> case aggregateRuntimeKind ty of
        Just (tag,field) -> [("aggregate",S tag),(field,Z)] ++
          if tag == "unboxed-sum" then [("tagSlot",num 0),("alternativeSlots",Z)] else []
        Nothing -> []
    -- GHC's kind-aware helper removes the RuntimeRep arguments, including
    -- representation variables. The remaining types are the ordered logical
    -- components/alternatives, not the flattened physical register layout.
    -- In particular an empty tuple and State# both use zero registers, but only
    -- the former has an aggregate boundary. Nested aggregates keep that shape.
    aggregate tag field args =
      let types = dropRuntimeRepArgs args
      in [("aggregate",S tag),(field,A (map component types))] ++
         if tag == "unboxed-sum" then sumLayout types else []
    -- These are physical projection indices, separate from the recursive
    -- logical alternatives above. GHC's unariser uses these same APIs for
    -- construction and case binders; its payload indices exclude the tag.
    sumLayout types = [("tagSlot",num 0),("alternativeSlots",maybe Z (A . map (A . map num)) layout)]
      where
        layout = do
          alternatives <- traverse typePrimReps types
          physical <- reps
          -- primRepSlot is partial for levity-polymorphic BoxedRep. Never call
          -- it (or the merger which calls it) on unresolved representation.
          if all knownSlot (concat alternatives) then do
            let slots = ubxSumRepType alternatives
            if physical == map slotPrimRep (NE.toList slots)
              then Just [map (+1) (layoutUbxSum (NE.tail slots) (map primRepSlot alternative))
                        | alternative <- alternatives]
              else Nothing
          else Nothing
        knownSlot (BoxedRep Nothing) = False
        knownSlot _ = True
    -- An evaluated tuple/sum does not evaluate its lifted payloads. This is a
    -- type layout, so only an unlifted component supplies a WHNF guarantee;
    -- unknown RuntimeRep/levity and lifted components stay conservative.
    component ty = typeRep ty (case typeLevity_maybe ty of Just Unlifted -> True; _ -> False)
    kind | not (null aggregateFields) = "unknown"
         | otherwise = case reps of
      Just [] -> "void"
      Just [r] | longRep r -> "long"
      Just [VecRep _ _] -> "vector"
      Just [FloatRep] -> "float"
      Just [DoubleRep] -> "double"
      Just [AddrRep] -> "address"
      Just [BoxedRep _]
        | isFunTy rho -> "closure"
        | Just (tc,_) <- splitTyConApp_maybe rho, isBoxedDataTyCon tc -> "data"
        | otherwise -> "object"
      _ -> "unknown"
    longRep = \case
      IntRep -> True; Int8Rep -> True; Int16Rep -> True; Int32Rep -> True; Int64Rep -> True
      WordRep -> True; Word8Rep -> True; Word16Rep -> True; Word32Rep -> True; Word64Rep -> True
      _ -> False

exprRep :: Ctx -> CoreExpr -> J
exprRep d (Tick _ e) = exprRep d e
exprRep d e
  | not (canCertify d) = unknownRep
  | Coercion{} <- e = voidRep
  | otherwise = typeRep (exprType e) (exprIsHNF e || case e of
      Var v -> v `elemVarSet` evaluatedIds d
      _ -> False)

binderRep :: Ctx -> Bool -> Var -> J
binderRep d evaluated v
  | isCoVar v = voidRep
  | not (canCertify d) = unknownRepWithState evaluated
  | otherwise = typeRep (varType v) (evaluated || v `elemVarSet` evaluatedIds d || exprIsHNF (Var v))

binder :: Ctx -> Var -> J
binder d = binderWithState d False

binderWithState :: Ctx -> Bool -> Var -> J
binderWithState d evaluated v = O $
  [ ("id",S (varKey d v)), ("name",S (occNameString (nameOccName (varName v))))
  , ("type",S (pretty d (varType v))), ("lifted",lifted v)
  , ("coercion",B (isCoVar v)), ("rep",binderRep d evaluated v)
  , ("info",if isId v then idMetadata d v else Z)
  ] ++ binderSource d v

binding :: Ctx -> (Id,CoreExpr) -> J
binding d (v,e) = O $
  [ ("id",S (varKey d v)), ("name",S (occNameString (nameOccName (varName v))))
  , ("type",S (pretty d (varType v))), ("lifted",lifted v)
  , ("arity",num (idArity v)), ("expr",annotated), ("rep",exprRep d e)
  , ("info",idMetadata d v)
  , ("entryStrict",A (map B aligned)), ("entryStrictSource",S origin)
  ] ++ joinMetadata d v e ++ binderSource d v
  where
    (marks,origin) = if canCertify d then CBV.entryContract (deriveCBVContracts d) v e else ([],"none")
    exported = expr d e
    -- Type binders have already erased; coercions retain their value slot.
    -- Joins may return further lambdas: their suffix must remain unmarked.
    aligned = case exported of
      A [S "lam",A parameters,_,_] | length marks <= length parameters -> take (length parameters) (marks ++ repeat False)
      _ -> marks
    annotated = case exported of
      A [S "lam",parameters,body,O metadata] ->
        A [S "lam",parameters,body,O (metadata ++ [("entryStrict",A (map B aligned)),("entryStrictSource",S origin)])]
      _ -> exported

joinMetadata :: Ctx -> Id -> CoreExpr -> [(String,J)]
joinMetadata d v e
  | not (isJoinId v) = []
  | otherwise =
      -- GHC.Core, Note [Invariants on join points]: join arity counts type
      -- lambdas too, and the RHS can return further lambdas after this prefix.
      let (prefix,result) = collectNBinders (idJoinArity v) e
      in [("joinValueArity",num (length (filter (not . isTyVar) prefix)))
         ,("joinResultRep",exprRep d result)]

idMetadata :: Ctx -> Id -> J
idMetadata d v = O
  [ ("callArity",num (idCallArity v)), ("demand",S (pretty d (idDemandInfo v)))
  , ("strictness",S (pretty d (idDmdSig v))), ("cpr",S (pretty d (idCprSig v)))
  , ("occurrence",S (pretty d (idOccInfo v))), ("oneShot",S (pretty d (idOneShotInfo v)))
  , ("joinArity",if isJoinId v then num (idJoinArity v) else Z)
  , ("inline",S (pretty d (idInlinePragma v)))
  , ("cbvEligible",B (CBV.eligible v))
  , ("cbvMarks",maybe Z (A . map B) (CBV.existingMarks v))
  ]

flattenBind :: CoreBind -> [(Id,CoreExpr)]
flattenBind (NonRec v e) = [(v,e)]
flattenBind (Rec vs) = vs

bindingGroup :: Ctx -> CoreBind -> [J]
bindingGroup d b = map (binding rhsCtx) (flattenBind b)
  where
    rhsCtx = case b of
      NonRec{} -> d
      Rec vs -> d { recursiveIds = extendVarSetList (recursiveIds d) (map fst vs) }

constructor :: Ctx -> DataCon -> J
constructor d con = O $
  [ ("id",S (nameKey (dataConName con))), ("name",S (occNameString (nameOccName (dataConName con))))
  , ("arity",num (dataConRepArity con)), ("tag",num (dataConTag con))
  , ("kind",S (if isUnboxedTupleDataCon con then "unboxed-tuple" else if isUnboxedSumDataCon con then "unboxed-sum" else if isNewTyCon (dataConTyCon con) then "newtype" else "boxed"))
  , ("type",S (pretty d (dataConRepType con)))
  -- These are worker representation slots, not source constructor fields.
  -- GHC.Core.DataCon documents that rep strictness and rep argument types
  -- align one-to-one, including zero-width coercion arguments.
  , ("strictFields",A (map (B . isMarkedStrict) (dataConRepStrictness con)))
  , ("fieldLifted",A (map (liftedType . scaledThing) (dataConRepArgTys con)))
  -- Preserve GHC's actual per-slot register representation. Void is []; a
  -- runtime-polymorphic slot is null, never a guessed reference representation.
  , ("fieldReps",A (map fieldReps workerTypes))
  -- A precise reference carrier is safe only after the worker's existing
  -- strict/unlifted-field obligation has been enforced. A lazy known-data
  -- field can still hold a THC thunk, so its evaluated flag remains false.
  , ("fieldTypes",A (zipWith fieldType workerTypes workerStrict))
  ] ++ [("sumArity",num (length (tyConDataCons (dataConTyCon con)))) | isUnboxedSumDataCon con]
    ++ [("enumFamily",enumFamily tc) | let tc = dataConTyCon con, supportedEnum tc]
    ++ [("dataToTagFamily",dataToTagFamily d tc) | let tc = dataConTyCon con, supportedTagFamily tc]
  where
    workerTypes = map scaledThing (dataConRepArgTys con)
    marks = dataConRepStrictness con
    workerStrict = if length marks == length workerTypes then map isMarkedStrict marks else repeat False
    fieldType ty strict = typeRep ty (strict || case typeLevity_maybe ty of Just Unlifted -> True; _ -> False)
    fieldReps ty = case typePrimReps ty of
      Nothing -> Z
      Just reps -> A (map (S . show) reps)

expr :: Ctx -> CoreExpr -> J
expr d original
  | Just lowered <- wiredCase original = withRep (exprRep d original) (withUnsafeEqualityCase (expr d lowered))
  | Just lowered <- wiredApplication original =
      if canCertify d && preservesWiredTypes original lowered
      then expr d lowered
      else uncertifiedRoot (expr d lowered)
  | Var v <- original, isWiredVoid v = A [S "void",O (("rep",voidRep) : sourceFields d)]
  | Var v <- original, Just lowered <- wiredRhs v = expr (d { canCertify = canCertify d && preservesWiredTypes original lowered }) lowered
  | canCertify d, Just saturated <- saturateMask original = expr d saturated
  | otherwise = exprRaw d original

-- These primops have no callable runtime closure. CorePrep normally saturates
-- them, but both export boundaries precede CorePrep. In particular bracket1
-- passes a mask applied only to its action as a State# -> (# State#, a #)
-- function. Use GHC's capture-avoiding, typed eta expansion before erasure;
-- the ordinary lambda path then exports the missing binder and tuple proof.
-- Supplied actions stay beneath the lambda and are not evaluated at creation.
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

-- Erasing a cast, tick or type-only application preserves the original result
-- type, without moving the metadata of lambda bodies or case binders.
withRep :: J -> J -> J
withRep rep (A xs) = case reverse xs of
  O fields : rest -> A (reverse rest ++ [O (("rep",rep) : filter ((/= "rep") . fst) fields)])
  _ -> A (xs ++ [O [("rep",rep)]])
withRep _ node = node

-- A wired identity/unary-class rewrite can change the type of its *result*.
-- Its retained child is still genuine, typed GHC Core: erasing its certificates
-- recursively would also erase an inner State# token, tuple layout or primop
-- application that the rewrite never changed. Only the rewritten root lacks a
-- certificate at the original call site. Keep its old no-demand/HNF contract.
uncertifiedRoot :: J -> J
uncertifiedRoot serialized = case withRep unknownRep serialized of
  A [S "app",function,arguments,lifted,_,_,O metadata] ->
    A [S "app",function,arguments,lifted,B False,B False,
       O (filter ((/= "callDemand") . fst) metadata)]
  other -> other

-- Record this export-only late rule without obscuring the original Core dump,
-- result representation, or source notes on the surviving expression.
withUnsafeEqualityCase :: J -> J
withUnsafeEqualityCase (A xs) = case reverse xs of
  O fields : rest -> A (reverse rest ++ [O (("unsafeEqualityCase",S "GHC.Core.Utils.isUnsafeEqualityCase/CoreToStg") : filter ((/= "unsafeEqualityCase") . fst) fields)])
  _ -> A (xs ++ [O [("unsafeEqualityCase",S "GHC.Core.Utils.isUnsafeEqualityCase/CoreToStg")]])
withUnsafeEqualityCase node = node

-- Preserve GHC's typed FCallId declaration at a direct application. The
-- runtime still validates the exact v1 symbol, convention and machine shape;
-- a similarly named Haskell function cannot acquire this metadata.
foreignCallFields :: Ctx -> CoreExpr -> [CoreExpr] -> [(String,J)]
foreignCallFields d f@(Var v) args
  | canCertify d
  , Just (Foreign.CCall (Foreign.CCallSpec target convention safety)) <- isFCallId_maybe v
  , let (types,values) = span isTypeArg args
  , not (any isTypeArg values)
  , let instantiated = exprType (mkApps f types)
  , null (fst (splitForAllTyVars instantiated))
  , let (parameters,result) = splitFunTys instantiated
  = [("foreignCall",O (
      [("schema",num (1 :: Int)),("target",targetRecord target)
      ,("convention",S (callConvention convention)),("safety",S (callSafety safety))
      ,("arity",num (length parameters)),("suppliedArity",num (length values))
      ,("argumentReps",A [typeRep (scaledThing parameter) False | parameter <- parameters])
      ,("resultRep",typeRep result False)] ++ javascriptFields target convention safety))]
  where
    isTypeArg Type{} = True
    isTypeArg _ = False
    targetRecord (Foreign.StaticTarget _ symbol unit isFunction) = O
      [("kind",S "static"),("symbol",S (unpackFS symbol))
      ,("unit",maybe Z (S . unitString) unit),("isFunction",B isFunction)]
    targetRecord Foreign.DynamicTarget = O [("kind",S "dynamic")]
    callConvention Foreign.CCallConv = "ccall"
    callConvention Foreign.CApiConv = "capi"
    callConvention Foreign.StdCallConv = "stdcall"
    callConvention Foreign.PrimCallConv = "prim"
    callConvention Foreign.JavaScriptCallConv = "javascript"
    callSafety Foreign.PlayRisky = "unsafe"
    callSafety Foreign.PlaySafe = "safe"
    callSafety Foreign.PlayInterruptible = "interruptible"
    javascriptFields (Foreign.StaticTarget _ symbol _ True) Foreign.CCallConv safety
      | safety `elem` [Foreign.PlaySafe, Foreign.PlayRisky]
      , Just source <- javascriptSource (unpackFS symbol)
      = [("intrinsic",S "javascript-v1"),("javascriptSource",S source)]
    javascriptFields _ _ _ = []
foreignCallFields _ _ _ = []

-- Only the versioned Truffle intrinsics are link-resolved without a source
-- definition. Every other foreign import stays in missingDefinitions.
polyglotForeign :: Id -> Bool
polyglotForeign v = case isFCallId_maybe v of
  Just (Foreign.CCall (Foreign.CCallSpec
    (Foreign.StaticTarget _ symbol _ True) Foreign.PrimCallConv Foreign.PlaySafe)) ->
      unpackFS symbol `elem` ["thc_polyglot_v1_eval", "thc_polyglot_v1_read_member", "thc_polyglot_v1_execute_int"]
  Just (Foreign.CCall (Foreign.CCallSpec
    (Foreign.StaticTarget _ symbol _ True) Foreign.CCallConv safety)) ->
      safety `elem` [Foreign.PlaySafe, Foreign.PlayRisky]
      && case javascriptSource (unpackFS symbol) of Just _ -> True; Nothing -> False
  _ -> False

exprRaw :: Ctx -> CoreExpr -> J
exprRaw d original = case original of
  Var v | Just p <- isPrimOpId_maybe v -> node [S "prim",S (occNameString (primOpOcc p))] []
        | Just con <- isDataConWorkId_maybe v -> node [S "con",S (nameKey (dataConName con)),num (dataConRepArity con)] []
        | otherwise -> node [S "var",S (varKey d v)] []
  Lit l -> let (k,v) = literal d l in node [S "lit",S k,S v] []
  a@App{} -> let (f,args) = collectArgs a
                 vals = filter (not . isTypeArg) args
                 -- Saturate the complete application, never its bare head.
                 -- Otherwise an already saturated primop would become an
                 -- indirect call through an unnecessary wrapper lambda.
                 function = case f of
                   Var v | Just _ <- isPrimOpId_maybe v -> exprRaw d f
                   _ -> expr d f
                 demand = case if canCertify d then Demands.callDemand f args else Nothing of
                   Just (arity,strict) -> [("callDemand",O [("arity",num arity),("strictArgs",A (map B strict))])]
                   Nothing -> []
             -- Analyse the original Core application, before erasing type or
             -- coercion information. A false result is conservative.
             in if null vals then withRep (exprRep d a) (expr d f) else node
               [S "app",function,A (map (expr d) vals),A (map argLifted vals)
               ,B (canCertify d && exprIsHNF a)
               ,B (canCertify d && exprOkForSpecEval (\v -> not (v `elemVarSet` recursiveIds d)) a)] (demand ++ [("enumFamily",enumFamily tc) | Just tc <- [tagToEnumFamily a]]
                 ++ [("dataToTagFamily",dataToTagFamily d tc) | Just tc <- [dataToTagApplication a]]
                 ++ foreignCallFields d f args)
  l@Lam{} -> let (bs,body) = collectBinders l
                 vals = filter (not . isTyVar) bs
             in if null vals then withRep (exprRep d l) (expr d body)
                else node [S "lam",A (map (binder d) vals),expr d body] [("resultRep",exprRep d body)]
  Let b body -> node [S "let",B (isRec b),A (bindingGroup d b),expr d body] []
  Case scrut b _ alts ->
    let branchCtx = d { evaluatedIds = extendVarSetList (evaluatedIds d) (b : scrutineeVars scrut) }
    in node [S "case",expr d scrut,S (varKey d b),A (map (alt branchCtx) alts)]
      [("binder",binderWithState d True b)]
  Cast e _ -> withRep (exprRep d original) (expr d e)
  Tick tick e -> expr (underTick d tick) e
  Type _ -> A [S "unsupported",S "type-as-value"]
  Coercion _ -> node [S "void"] []
  where
    node fields metadata = A (fields ++ [O (("rep",exprRep d original) : metadata ++ sourceFields d)])
    isTypeArg Type{} = True
    isTypeArg _ = False
    argLifted Coercion{} = B False
    argLifted e = liftedType (exprType e)
    isRec Rec{} = True
    isRec _ = False
    scrutineeVars (Var v) = [v]
    scrutineeVars (Cast e _) = scrutineeVars e
    scrutineeVars (Tick _ e) = scrutineeVars e
    scrutineeVars _ = []
    alt branchCtx (Alt ac bs body) =
      let vals = filter (not . isTyVar) bs
          ids = A (map (S . varKey d) vals)
          -- Core alternatives retain worker slots after type-variable erasure,
          -- including zero-width coercions. Only exactly aligned strictness
          -- marks certify fields; lazy fields keep their independent evidence.
          strictFields = case ac of
            DataAlt c | canCertify d
                      , let marks = dataConRepStrictness c
                      , length marks == length vals -> [v | (v,mark) <- zip vals marks, isMarkedStrict mark]
            _ -> []
          bodyCtx = branchCtx { evaluatedIds = extendVarSetList (evaluatedIds branchCtx) strictFields }
          metadata = O [("binders",A (map (binder bodyCtx) vals))]
      in case ac of
        DEFAULT -> A [S "default",Z,ids,expr bodyCtx body,metadata]
        DataAlt c -> A [S "data",S (nameKey (dataConName c)),ids,expr bodyCtx body,metadata]
        LitAlt l -> let (k,v) = literal d l in A [S "lit",A [S k,S v],ids,expr bodyCtx body,metadata]

literal :: Ctx -> Literal -> (String,String)
literal d = \case
  LitNumber n i -> (numKind n,show i)
  LitChar c -> ("char",show (ord c))
  LitString s -> ("string-bytes",concatMap hex (BS.unpack s))
  LitFloat f -> ("float",show (fromRational f :: Float))
  LitDouble f -> ("double",show (fromRational f :: Double))
  LitNullAddr -> ("null-addr","0")
  -- A label has a symbol and an exact function/data distinction, but no
  -- calling-convention or argument-type certificate. Preserve only those
  -- facts; resolving a callable ABI is the foreign provider's obligation.
  LitLabel symbol IsFunction -> ("function-addr",unpackFS symbol)
  LitLabel symbol IsData -> ("data-addr",unpackFS symbol)
  other -> ("unsupported",pretty d other)
  where
    hex b = let h = showHex b "" in replicate (2-length h) '0' ++ h
    numKind LitNumInt = "int"
    numKind LitNumWord = "word"
    numKind LitNumInt8 = "int8"
    numKind LitNumInt16 = "int16"
    numKind LitNumInt32 = "int32"
    numKind LitNumInt64 = "int64"
    numKind LitNumWord8 = "word8"
    numKind LitNumWord16 = "word16"
    numKind LitNumWord32 = "word32"
    numKind LitNumWord64 = "word64"
    numKind LitNumBigNat = "bignat"

-- tagToEnum# carries a nominal result-type argument which ordinary application
-- export erases. Retain only a complete, concrete nullary family; never infer
-- an enum from a boxed runtime representation or a printed type name.
supportedEnum :: TyCon -> Bool
supportedEnum tc = isEnumerationTyCon tc && tyConArity tc == 0 && not (isFamInstTyCon tc)
  && not (null cs) && all ((== 0) . dataConRepArity) cs
  where cs = tyConDataCons tc

enumFamily :: TyCon -> J
enumFamily tc = O [("typeConstructor",S (nameKey (tyConName tc)))
                 ,("constructors",A [S (nameKey (dataConName c)) | c <- tyConDataCons tc])]

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

dataToTagFamily :: Ctx -> TyCon -> J
dataToTagFamily d tc = O
  [("typeConstructor",S (nameKey (tyConName tc)))
  ,("constructors",A [S (nameKey (dataConName con)) | con <- tyConDataCons tc])
  ,("smallFamilyLimit",num (mAX_PTR_TAG platform))
  ,("smallFamily",B (isSmallFamily platform (tyConFamilySize tc)))]
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
    writeFile path (json result ++ "\n")
    modifyIORef' sourceDefinitions ((d,binds):)
    let roots = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) `elem` closureRoots]
    if null roots then pure () else exportInterfaceClosure hsc opts dir d roots
  pure guts

-- | The same pre-Tidy serializer used by the plugin, without filesystem writes
-- or closure registration. Callers must supply genuine optimized ModGuts.
serializeOptimizedCore :: DynFlags -> [CommandLineOption] -> ModGuts -> IO String
serializeOptimizedCore flags opts guts = do
  (_,result) <- optimizedModule flags opts guts
  pure (json result ++ "\n")

optimizedModule :: DynFlags -> [CommandLineOption] -> ModGuts -> IO (Ctx,J)
optimizedModule flags opts guts = do
  sources <- loadSources ("source-notes" `elem` opts) (concatMap flattenBind (mg_binds guts))
  exports <- staticExportFields (mg_module guts) (mg_anns guts) (mg_binds guts)
  let unit = unitString (moduleUnit (mg_module guts))
      d = Ctx flags (unit ++ ":" ++ moduleNameString (moduleName (mg_module guts))) (if "unit-qualified" `elem` opts then Just unit else Nothing) emptyVarSet emptyVarSet True True sources []
      modName = moduleNameString (moduleName (mg_module guts))
      binds = concatMap flattenBind (mg_binds guts)
      cons = nubBy (\a b -> dataConName a == dataConName b) $ concatMap tyConDataCons (mg_tcs guts) ++ concatMap (exprCons . snd) binds
      result = O $
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName)
        , ("unit",S (unitString (moduleUnit (mg_module guts))))
        , ("boundary",S "optimized-Core-before-Tidy")
        , ("sourceCore",S (pretty d (mg_binds guts)))
        , ("bindings",A (concatMap (bindingGroup d) (mg_binds guts))), ("constructors",A (map (constructor d) cons))
        , ("groups",A [O [("recursive",B (case b of Rec{} -> True; _ -> False)),("ids",A [S (varKey d v) | (v,_) <- flattenBind b])] | b <- mg_binds guts])
        , ("rules",S (pretty d (mg_rules guts)))
        , ("lowering",O [("typeArguments",S "erased"),("coercionArguments",S "void-value"),("casts",S "erased"),("ticks",S (if "source-notes" `elem` opts then "source-notes-metadata" else "erased"))])
        ] ++ sourceTableFields d ++ exports
  pure (d,result)

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
      writeFile path (json result ++ "\n")
      modifyIORef' sourceDefinitions ((d,binds):)
      let roots = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) `elem` mapMaybe (stripPrefix "closure=") opts]
      if null roots then pure () else exportInterfaceClosure hsc opts dir d roots
      pure pair

-- | Serialize actual post-Tidy Core, including Core hydrated from a complete
-- installed interface. Only "source-notes" and "unit-qualified" affect this
-- entry point. It neither writes files nor registers plugin closure roots.
-- Foreign products are archival metadata, not executable registration. The
-- schema bump prevents older runtimes/auditors from silently ignoring them.
serializePostTidyCore :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> IO String
serializePostTidyCore flags opts m tycons program foreignArtifacts =
  serializePostTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts []

-- | The installed-interface path retains typed module annotations. CgGuts in
-- the late source plugin does not: use the emitted interface to recover this
-- optional inventory, never a process-local table or a previous export file.
serializePostTidyCoreWithAnnotations :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> ForeignCore.IfaceForeign -> [Annotation] -> IO String
serializePostTidyCoreWithAnnotations flags opts m tycons program foreignArtifacts annotations = do
  associations <- either (ioError . userError . ("THC: " ++)) pure
    (Exports.readStaticExports m annotations program)
  -- A boxed identity need not inspect or construct its argument in Core. Host
  -- codecs still need the genuine constructor layout of its declared type.
  let exported = case associations of
        Nothing -> []
        Just (Exports.StaticExports _ _ _ records) -> map (exportKey . Exports.exportBinder) records
      signatureTycons = concat [foreignSignatureTycons (idType binder)
        | (binder, _) <- flattenBinds program, nameKey (varName binder) `elem` exported]
  (_,result) <- postTidyModule flags opts m (tycons ++ signatureTycons) program
  exports <- staticExportFields m annotations program
  provenance <- exportProvenanceFields m annotations program foreignArtifacts
  imports <- importProvenanceFields m annotations foreignArtifacts result
  let annotated = case result of O fields -> O (fields ++ exports ++ provenance ++ imports); _ -> result
  pure (json (withForeignArtifacts foreignArtifacts annotated) ++ "\n")
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
importProvenanceFields :: Module -> [Annotation] -> ForeignCore.IfaceForeign -> J -> IO [(String,J)]
importProvenanceFields owner annotations original core = do
  verdict <- either (ioError . userError . ("THC: " ++)) pure
    (ImportProvenance.inspectImports owner annotations original)
  pure $ case verdict of
    Nothing -> []
    Just value ->
      let record = O (common ++ details value)
          associations = case value of
            ImportProvenance.Verified [] -> []
            _ -> [("staticForeignImports", record)]
      in associations ++ [("staticForeignImportStubs", record) | not emptyProduct]
  where
    emptyProduct = case original of
      ForeignCore.IfaceForeign Nothing [] -> True
      ForeignCore.IfaceForeign (Just (ForeignCore.IfaceCStubs "" "" [] [])) [] -> True
      _ -> False
    common = [("schema",num (1::Int)),("scope",S "retained-static-import-products"),("execution",S "not-linked"),
      ("profile",S "ghc-9.14.1-thc-only-static-c-imports-v1"),
      ("unit",S (unitString (moduleUnit owner))),("module",S (moduleNameString (moduleName owner)))]
    details (ImportProvenance.Unknown reason) = [("status",S "unclassified"),("reason",S reason)]
    details (ImportProvenance.Rejected reason) = [("status",S "rejected"),("reason",S reason)]
    details (ImportProvenance.Verified imports) = [("status",S "verified"),("wordBits",num (64::Int)),
      ("expectedForeign",foreignArtifactRecord original),("imports",A (map imported imports)),
      ("expectedCalls",A (calls core))]
    calls (O fields) = [value | (key,value) <- fields, key == "foreignCall"] ++ concatMap (calls . snd) fields
    calls (A values) = concatMap calls values
    calls _ = []
    identity (Exports.ExportName unit modName occurrence namespace) = O
      [("unit",S unit),("module",S modName),("occurrence",S occurrence),("namespace",S namespace)]
    ty (Exports.ExportTyCon name arguments) = O [("kind",S "tycon"),("name",identity name),("arguments",A (map ty arguments))]
    ty (Exports.ExportApp function argument) = O [("kind",S "application"),("function",ty function),("argument",ty argument)]
    ty (Exports.ExportArrow multiplicity argument result) = O
      [("kind",S "function"),("multiplicity",ty multiplicity),("argument",ty argument),("result",ty result)]
    imported (ImportProvenance.Import binder header symbol unit function conv safe declared normalized emitted) = O
      [("binder",identity binder),("header",maybe Z S header),("symbol",S symbol),("unit",maybe Z S unit),
       ("isFunction",B function),("convention",S conv),("safety",S safe),("declaredType",ty declared),
       ("normalizedType",ty normalized),("normalizationRole",S "representational"),("emitted",call emitted)]
    call (ImportProvenance.Call symbol unit conv safe arguments result) = O
      [("symbol",S symbol),("unit",maybe Z S unit),("convention",S conv),("safety",S safe),
       ("arguments",A (map S arguments)),("result",A (map S result))]

staticExportFields :: Module -> [Annotation] -> CoreProgram -> IO [(String,J)]
staticExportFields owner annotations bindings = do
  record <- either (ioError . userError . ("THC: " ++)) pure
    (Exports.readStaticExports owner annotations bindings)
  pure $ case record of
    Nothing -> []
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

postTidyModule :: DynFlags -> [CommandLineOption] -> Module -> [TyCon] -> CoreProgram -> IO (Ctx,J)
postTidyModule flags opts m tycons program = do
  sources <- loadSources ("source-notes" `elem` opts) binds
  let unit = unitString (moduleUnit m)
      modName = moduleNameString (moduleName m)
      d = Ctx flags (unit ++ ":" ++ modName) (if "unit-qualified" `elem` opts then Just unit else Nothing)
            emptyVarSet emptyVarSet True False sources []
      cons = nubBy (\a b -> dataConName a == dataConName b)
        (concatMap tyConDataCons tycons ++ concatMap (exprCons . snd) binds)
      result = O $
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName), ("unit",S unit)
        , ("boundary",S "optimized-Core-after-Tidy-before-CorePrep")
        , ("sourceCore",S (pretty d program))
        , ("bindings",A (concatMap (bindingGroup d) program)), ("constructors",A (map (constructor d) cons))
        , ("groups",A [O [("recursive",B (case b of Rec{} -> True; _ -> False)),("ids",A [S (varKey d v) | (v,_) <- flattenBind b])] | b <- program])
        ] ++ sourceTableFields d
  pure (d,result)
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
        | Just e <- maybeUnfoldingTemplate (realIdUnfolding v) =
            let kind = case realIdUnfolding v of DFunUnfolding{} -> "interface-dfun-unfolding"; _ -> "interface-core-unfolding"
            in walk seen' (refs e ++ todo) ((originCtx v,v,e,kind):found) missing
        | otherwise = walk seen' todo found ((originCtx v,v):missing)
        where seen' = extendVarSet seen v
  (imports,missing) <- walk emptyVarSet roots [] []
  let
      -- Interfaces do not retain complete source recursive-group boundaries.
      -- Conservatively forbid speculation of every imported definition while
      -- exporting their RHSs; this preserves recursive dictionary guards.
      recIds = mkVarSet [v | (_,v,_,_) <- imports]
  sources <- loadSources (case sourceTable rootCtx of Just _ -> True; _ -> False) [(v,e) | (_,v,e,_) <- imports]
  let closureCtx = rootCtx { sourceTable = sources, sourceUnit = if "unit-qualified" `elem` opts then Just "dependency-closure" else Nothing }
  let importedBinding (d,v,e,kind) = case binding (d { recursiveIds = recIds, deriveCBVContracts = False, sourceTable = sources, sourceUnit = sourceUnit closureCtx, activeSources = [] }) (v,e) of
        O fields -> O (fields ++ [("origin",S kind),("originModule",S (modulePrefix d))])
        _ -> error "binding was not an object"
      cons = nubBy (\a b -> dataConName a == dataConName b) (concat [exprCons e | (_,_,e,_) <- imports])
      result = O $
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S "THC.InterfaceClosure"), ("unit",S "dependency-closure")
        , ("boundary",S "actual-interface-unfoldings"), ("roots",A [S (varKey rootCtx v) | v <- roots])
        , ("sourceModules",A [S (modulePrefix d) | (d,_) <- reverse modules])
        , ("bindings",A (map importedBinding imports)), ("constructors",A (map (constructor rootCtx) cons))
        , ("groups",A [O [("recursive",B True),("ids",A [S (varKey d v) | (d,v,_,_) <- imports])]])
        , ("missingDefinitions",A [O [("id",S (varKey d v)),("type",S (pretty d (varType v))), ("reason",S "No executable interface unfolding; source export required")] | (d,v) <- missing])
        , ("sourceCore",S (pretty rootCtx [(v,e) | (_,v,e,_) <- imports]))
        ] ++ sourceTableFields closureCtx
  let path = coreOutputPath opts dir "dependency-closure" "THC.InterfaceClosure"
  createDirectoryIfMissing True (takeDirectory path)
  writeFile path (json result ++ "\n")
  putStrLn ("THC interface closure: " ++ show (length imports) ++ " actual unfoldings, " ++ show (length missing) ++ " missing source definitions")
