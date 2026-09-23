{-# LANGUAGE LambdaCase #-}
module Thc.Plugin (plugin) where

import GHC.Plugins
import qualified Thc.Sources as Sources
import qualified Thc.Cbv as Cbv
import qualified Thc.Demands as Demands
import Thc.Wired (wiredApplication, wiredRhs, preservesWiredTypes, isWiredVoid)
import GHC.Types.Tickish (CoreTickish)
import GHC.Types.Literal
import GHC.Types.RepType (typePrimRep_maybe, unwrapType, ubxSumRepType, layoutUbxSum, primRepSlot, slotPrimRep)
import GHC.Builtin.Types (tupleRepDataConTyCon, sumRepDataConTyCon)
import GHC.Core.TyCo.Rep (scaledThing)
import GHC.Core.Utils (exprIsHNF, exprOkForSpecEval)
import GHC.Builtin.PrimOps (primOpOcc)
import qualified Data.ByteString as BS
import Data.Char (ord)
import Data.List (intercalate, nubBy, stripPrefix)
import qualified Data.List.NonEmpty as NE
import Data.IORef (IORef, newIORef, readIORef, modifyIORef')
import Data.Maybe (mapMaybe)
import System.IO.Unsafe (unsafePerformIO)
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing)
import System.FilePath ((</>))

-- This plugin intentionally runs last in the ordinary Core pipeline, before
-- Tidy. It exports executable trees, never parses a pretty-printed Core dump.
plugin :: Plugin
plugin = defaultPlugin
  { installCoreToDos = \opts passes -> pure (if "post-tidy" `elem` opts then passes else passes ++ [CoreDoPluginPass "THC rich Core export" (exportModule opts)])
  , latePlugin = exportLate
  , pluginRecompile = \_ -> pure ForceRecompile
  }

data J = O [(String,J)] | A [J] | S String | N Integer | B Bool | Z

json :: J -> String
json (O xs) = "{" ++ intercalate "," [json (S k) ++ ":" ++ json v | (k,v) <- xs] ++ "}"
json (A xs) = "[" ++ intercalate "," (map json xs) ++ "]"
json (S s) = '"' : concatMap escape s ++ "\""
  where
    escape '"' = "\\\""
    escape '\\' = "\\\\"
    escape c | ord c < 32 = "\\u" ++ replicate (4-length h) '0' ++ h where h = showHex (ord c) ""
    escape c = [c]
json (N n) = show n
json (B b) = if b then "true" else "false"
json Z = "null"

num :: Integral a => a -> J
num = N . toInteger

data Ctx = Ctx
  { dynFlags :: DynFlags
  , modulePrefix :: String
  -- As in CorePrep, exclude every enclosing recursive group while traversing
  -- its RHSs: speculative calls can otherwise destroy guarded recursion.
  , recursiveIds :: VarSet
  -- Lexical WHNF facts from successful cases and strict worker fields. These
  -- are facts about already evaluated values, never demand predictions.
  , evaluatedIds :: VarSet
  , canCertify :: Bool
  -- Current-module CBV requirements are selected only during native Tidy.
  , deriveCbvContracts :: Bool
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
  (Just table, Just note) | Sources.hasNote table note -> [("source",S (Sources.noteKey note))]
  _ -> []

underTick :: Ctx -> CoreTickish -> Ctx
underTick d tick = case (sourceTable d, Sources.tickNote tick) of
  (Just table, Just note) | Sources.hasNote table note ->
    d { activeSources = activeSources d ++ [Sources.noteKey note] }
  _ -> d

sourceTableFields :: Maybe Sources.SourceTable -> [(String,J)]
sourceTableFields Nothing = []
sourceTableFields (Just table) =
  [("sourceFiles",A [O [("id",S (Sources.sourceFileId file)),("path",S (Sources.sourceFilePath file)),
                       ("content",maybe Z S (Sources.sourceFileContent file))] | file <- Sources.sourceFiles table])
  ,("sourceSpans",A (map spanRecord (Sources.sourceSpans table)))]
  where
    spanRecord record = let Sources.Note span label = Sources.sourceSpanNote record in O
      [("id",S (Sources.sourceSpanId record)),("file",S (Sources.sourceSpanFile record))
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
    (marks,origin) = if canCertify d then Cbv.entryContract (deriveCbvContracts d) v e else ([],"none")
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
  , ("cbvEligible",B (Cbv.eligible v))
  , ("cbvMarks",maybe Z (A . map B) (Cbv.existingMarks v))
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
  | Just lowered <- wiredApplication original = expr (d { canCertify = canCertify d && preservesWiredTypes original lowered }) lowered
  | Var v <- original, isWiredVoid v = A [S "void",O (("rep",voidRep) : sourceFields d)]
  | Var v <- original, Just lowered <- wiredRhs v = expr (d { canCertify = canCertify d && preservesWiredTypes original lowered }) lowered
  | otherwise = exprRaw d original

-- Erasing a cast, tick or type-only application preserves the original result
-- type, without moving the metadata of lambda bodies or case binders.
withRep :: J -> J -> J
withRep rep (A xs) = case reverse xs of
  O fields : rest -> A (reverse rest ++ [O (("rep",rep) : filter ((/= "rep") . fst) fields)])
  _ -> A (xs ++ [O [("rep",rep)]])
withRep _ node = node

exprRaw :: Ctx -> CoreExpr -> J
exprRaw d original = case original of
  Var v | Just p <- isPrimOpId_maybe v -> node [S "prim",S (occNameString (primOpOcc p))] []
        | Just con <- isDataConWorkId_maybe v -> node [S "con",S (nameKey (dataConName con)),num (dataConRepArity con)] []
        | otherwise -> node [S "var",S (varKey d v)] []
  Lit l -> let (k,v) = literal d l in node [S "lit",S k,S v] []
  a@App{} -> let (f,args) = collectArgs a
                 vals = filter (not . isTypeArg) args
                 demand = case if canCertify d then Demands.callDemand f args else Nothing of
                   Just (arity,strict) -> [("callDemand",O [("arity",num arity),("strictArgs",A (map B strict))])]
                   Nothing -> []
             -- Analyse the original Core application, before erasing type or
             -- coercion information. A false result is conservative.
             in if null vals then withRep (exprRep d a) (expr d f) else node
               [S "app",expr d f,A (map (expr d) vals),A (map argLifted vals)
               ,B (canCertify d && exprIsHNF a)
               ,B (canCertify d && exprOkForSpecEval (\v -> not (v `elemVarSet` recursiveIds d)) a)] demand
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

exprCons :: CoreExpr -> [DataCon]
exprCons = \case
  Var v -> maybe [] (:[]) (isDataConWorkId_maybe v)
  App a b -> exprCons a ++ exprCons b
  Lam _ e -> exprCons e
  Let b e -> concatMap (exprCons . snd) (flattenBind b) ++ exprCons e
  Case s _ _ as -> exprCons s ++ concat [case ac of DataAlt c -> c : exprCons e; _ -> exprCons e | Alt ac _ e <- as]
  Cast e _ -> exprCons e
  Tick _ e -> exprCons e
  _ -> []

exportModule :: [CommandLineOption] -> ModGuts -> CoreM ModGuts
exportModule opts guts = do
  flags <- getDynFlags
  sources <- liftIO $ loadSources ("source-notes" `elem` opts) (concatMap flattenBind (mg_binds guts))
  let d = Ctx flags (unitString (moduleUnit (mg_module guts)) ++ ":" ++ moduleNameString (moduleName (mg_module guts))) emptyVarSet emptyVarSet True True sources []
      dir = case opts of [] -> "build/core"; x:_ -> x
      closureRoots = mapMaybe (stripPrefix "closure=") (drop 1 opts)
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
        ] ++ sourceTableFields sources
  liftIO $ do
    createDirectoryIfMissing True dir
    writeFile (dir </> modName ++ ".json") (json result ++ "\n")
    modifyIORef' sourceDefinitions ((d,binds):)
    let roots = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) `elem` closureRoots]
    if null roots then pure () else exportInterfaceClosure dir d roots
  pure guts

-- Package rebuilding needs identities that agree with the newly emitted
-- interfaces, including Tidy-generated external names and implicit selectors.
-- Keep this optional boundary explicit; application/source exports stay at the
-- original optimized-Core-before-Tidy boundary.
exportLate :: LatePlugin
exportLate hsc opts pair@(guts,_)
  | not ("post-tidy" `elem` opts) = pure pair
  | otherwise = do
      sources <- loadSources ("source-notes" `elem` opts) (concatMap flattenBind (cg_binds guts))
      let m = cg_module guts
          d = Ctx (hsc_dflags hsc) (unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m)) emptyVarSet emptyVarSet True False sources []
          dir = case opts of [] -> "build/core"; x:_ -> x
          modName = moduleNameString (moduleName m)
          binds = concatMap flattenBind (cg_binds guts)
          cons = nubBy (\a b -> dataConName a == dataConName b) (concatMap tyConDataCons (cg_tycons guts) ++ concatMap (exprCons . snd) binds)
          result = O $
            [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName), ("unit",S (unitString (moduleUnit m)))
            , ("boundary",S "optimized-Core-after-Tidy-before-CorePrep")
            , ("sourceCore",S (pretty d (cg_binds guts)))
            , ("bindings",A (concatMap (bindingGroup d) (cg_binds guts))), ("constructors",A (map (constructor d) cons))
            , ("groups",A [O [("recursive",B (case b of Rec{} -> True; _ -> False)),("ids",A [S (varKey d v) | (v,_) <- flattenBind b])] | b <- cg_binds guts])
            ] ++ sourceTableFields sources
      createDirectoryIfMissing True dir
      writeFile (dir </> modName ++ ".json") (json result ++ "\n")
      modifyIORef' sourceDefinitions ((d,binds):)
      let roots = [v | (v,_) <- binds, occNameString (nameOccName (varName v)) `elem` mapMaybe (stripPrefix "closure=") opts]
      if null roots then pure () else exportInterfaceClosure dir d roots
      pure pair

-- Source definitions from earlier modules of this same --make invocation let
-- the dependency walk use their complete bodies, even when GHC did not retain
-- an inline unfolding. No pretty-printed Core is parsed or reconstructed.
{-# NOINLINE sourceDefinitions #-}
sourceDefinitions :: IORef [(Ctx,[(Id,CoreExpr)])]
sourceDefinitions = unsafePerformIO (newIORef [])

exportInterfaceClosure :: FilePath -> Ctx -> [Id] -> IO ()
exportInterfaceClosure dir rootCtx roots = do
  modules <- readIORef sourceDefinitions
  let sourceEnv = mkVarEnv [(v,(d,e)) | (d,bs) <- modules, (v,e) <- bs]
      -- exprFreeVars deliberately omits global IDs. Dependency discovery
      -- instead walks all term references with explicit lexical scopes.
      refs = go emptyVarSet
        where
          go bound original
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
      walk _ [] found missing = (reverse found, reverse missing)
      walk seen (v:todo) found missing
        | v `elemVarSet` seen = walk seen todo found missing
        | Just _ <- isPrimOpId_maybe v = walk seen' todo found missing
        | Just _ <- isDataConWorkId_maybe v = walk seen' todo found missing
        | Just (_,e) <- lookupVarEnv sourceEnv v = walk seen' (refs e ++ todo) found missing
        | Just e <- maybeUnfoldingTemplate (realIdUnfolding v) =
            let kind = case realIdUnfolding v of DFunUnfolding{} -> "interface-dfun-unfolding"; _ -> "interface-core-unfolding"
            in walk seen' (refs e ++ todo) ((originCtx v,v,e,kind):found) missing
        | otherwise = walk seen' todo found ((originCtx v,v):missing)
        where seen' = extendVarSet seen v
      (imports,missing) = walk emptyVarSet roots [] []
      -- Interfaces do not retain complete source recursive-group boundaries.
      -- Conservatively forbid speculation of every imported definition while
      -- exporting their RHSs; this preserves recursive dictionary guards.
      recIds = mkVarSet [v | (_,v,_,_) <- imports]
  sources <- loadSources (case sourceTable rootCtx of Just _ -> True; _ -> False) [(v,e) | (_,v,e,_) <- imports]
  let importedBinding (d,v,e,kind) = case binding (d { recursiveIds = recIds, deriveCbvContracts = False, sourceTable = sources, activeSources = [] }) (v,e) of
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
        ] ++ sourceTableFields sources
  writeFile (dir </> "THC.InterfaceClosure.json") (json result ++ "\n")
  putStrLn ("THC interface closure: " ++ show (length imports) ++ " actual unfoldings, " ++ show (length missing) ++ " missing source definitions")
