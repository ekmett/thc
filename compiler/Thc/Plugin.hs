{-# LANGUAGE LambdaCase #-}
module Thc.Plugin (plugin) where

import GHC.Plugins
import Thc.Wired (wiredApplication, wiredRhs, isWiredVoid)
import GHC.Types.Literal
import GHC.Types.RepType (typePrimRep_maybe)
import GHC.Core.TyCo.Rep (scaledThing)
import GHC.Core.Utils (exprIsHNF, exprOkForSpecEval)
import GHC.Builtin.PrimOps (primOpOcc)
import qualified Data.ByteString as BS
import Data.Char (ord)
import Data.List (intercalate, nubBy, stripPrefix)
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
  , canCertify :: Bool
  }

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

binder :: Ctx -> Var -> J
binder d v = O
  [ ("id",S (varKey d v)), ("name",S (occNameString (nameOccName (varName v))))
  , ("type",S (pretty d (varType v))), ("lifted",lifted v)
  , ("coercion",B (isCoVar v))
  , ("info",if isId v then idMetadata d v else Z)
  ]

binding :: Ctx -> (Id,CoreExpr) -> J
binding d (v,e) = O $
  [ ("id",S (varKey d v)), ("name",S (occNameString (nameOccName (varName v))))
  , ("type",S (pretty d (varType v))), ("lifted",lifted v)
  , ("arity",num (idArity v)), ("expr",expr d e)
  , ("info",idMetadata d v)
  ]

idMetadata :: Ctx -> Id -> J
idMetadata d v = O
  [ ("callArity",num (idCallArity v)), ("demand",S (pretty d (idDemandInfo v)))
  , ("strictness",S (pretty d (idDmdSig v))), ("cpr",S (pretty d (idCprSig v)))
  , ("occurrence",S (pretty d (idOccInfo v))), ("oneShot",S (pretty d (idOneShotInfo v)))
  , ("joinArity",if isJoinId v then num (idJoinArity v) else Z)
  , ("inline",S (pretty d (idInlinePragma v)))
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
constructor d con = O
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
  , ("fieldReps",A (map (fieldReps . scaledThing) (dataConRepArgTys con)))
  ]
  where
    fieldReps ty = case typePrimRep_maybe ty of
      Nothing -> Z
      Just reps -> A (map (S . show) reps)

expr :: Ctx -> CoreExpr -> J
expr d original
  | Just lowered <- wiredApplication original = expr (d { canCertify = False }) lowered
  | Var v <- original, isWiredVoid v = A [S "void"]
  | Var v <- original, Just lowered <- wiredRhs v = expr (d { canCertify = False }) lowered
  | otherwise = exprRaw d original

exprRaw :: Ctx -> CoreExpr -> J
exprRaw d = \case
  Var v | Just p <- isPrimOpId_maybe v -> A [S "prim",S (occNameString (primOpOcc p))]
        | Just con <- isDataConWorkId_maybe v -> A [S "con",S (nameKey (dataConName con)),num (dataConRepArity con)]
        | otherwise -> A [S "var",S (varKey d v)]
  Lit l -> let (k,v) = literal d l in A [S "lit",S k,S v]
  a@App{} -> let (f,args) = collectArgs a
                 vals = filter (not . isTypeArg) args
             -- Analyse the original Core application, before erasing type or
                 -- coercion information. A false result is conservative.
             in if null vals then expr d f else A [S "app",expr d f,A (map (expr d) vals),A (map argLifted vals),B (canCertify d && exprIsHNF a),B (canCertify d && exprOkForSpecEval (\v -> not (v `elemVarSet` recursiveIds d)) a)]
  l@Lam{} -> let (bs,body) = collectBinders l
                 vals = filter (not . isTyVar) bs
             in if null vals then expr d body else A [S "lam",A (map (binder d) vals),expr d body]
  Let b body -> A [S "let",B (isRec b),A (bindingGroup d b),expr d body]
  Case scrut b _ alts -> A [S "case",expr d scrut,S (varKey d b),A (map alt alts)]
  Cast e _ -> expr d e
  Tick _ e -> expr d e
  Type _ -> A [S "unsupported",S "type-as-value"]
  Coercion _ -> A [S "void"]
  where
    isTypeArg Type{} = True
    isTypeArg _ = False
    argLifted Coercion{} = B False
    argLifted e = liftedType (exprType e)
    isRec Rec{} = True
    isRec _ = False
    alt (Alt ac bs body) = let ids = A [S (varKey d b) | b <- bs, not (isTyVar b)]
                          in case ac of
      DEFAULT -> A [S "default",Z,ids,expr d body]
      DataAlt c -> A [S "data",S (nameKey (dataConName c)),ids,expr d body]
      LitAlt l -> let (k,v) = literal d l in A [S "lit",A [S k,S v],ids,expr d body]

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
  let d = Ctx flags (unitString (moduleUnit (mg_module guts)) ++ ":" ++ moduleNameString (moduleName (mg_module guts))) emptyVarSet True
      dir = case opts of [] -> "build/core"; x:_ -> x
      closureRoots = mapMaybe (stripPrefix "closure=") (drop 1 opts)
      modName = moduleNameString (moduleName (mg_module guts))
      binds = concatMap flattenBind (mg_binds guts)
      cons = nubBy (\a b -> dataConName a == dataConName b) $ concatMap tyConDataCons (mg_tcs guts) ++ concatMap (exprCons . snd) binds
      result = O
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName)
        , ("unit",S (unitString (moduleUnit (mg_module guts))))
        , ("boundary",S "optimized-Core-before-Tidy")
        , ("sourceCore",S (pretty d (mg_binds guts)))
        , ("bindings",A (concatMap (bindingGroup d) (mg_binds guts))), ("constructors",A (map (constructor d) cons))
        , ("groups",A [O [("recursive",B (case b of Rec{} -> True; _ -> False)),("ids",A [S (varKey d v) | (v,_) <- flattenBind b])] | b <- mg_binds guts])
        , ("rules",S (pretty d (mg_rules guts)))
        , ("lowering",O [("typeArguments",S "erased"),("coercionArguments",S "void-value"),("casts",S "erased"),("ticks",S "erased")])
        ]
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
      let m = cg_module guts
          d = Ctx (hsc_dflags hsc) (unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m)) emptyVarSet True
          dir = case opts of [] -> "build/core"; x:_ -> x
          modName = moduleNameString (moduleName m)
          binds = concatMap flattenBind (cg_binds guts)
          cons = nubBy (\a b -> dataConName a == dataConName b) (concatMap tyConDataCons (cg_tycons guts) ++ concatMap (exprCons . snd) binds)
          result = O
            [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S modName), ("unit",S (unitString (moduleUnit m)))
            , ("boundary",S "optimized-Core-after-Tidy-before-CorePrep")
            , ("sourceCore",S (pretty d (cg_binds guts)))
            , ("bindings",A (concatMap (bindingGroup d) (cg_binds guts))), ("constructors",A (map (constructor d) cons))
            , ("groups",A [O [("recursive",B (case b of Rec{} -> True; _ -> False)),("ids",A [S (varKey d v) | (v,_) <- flattenBind b])] | b <- cg_binds guts])
            ]
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
      importedBinding (d,v,e,kind) = case binding (d { recursiveIds = recIds }) (v,e) of
        O fields -> O (fields ++ [("origin",S kind),("originModule",S (modulePrefix d))])
        _ -> error "binding was not an object"
      cons = nubBy (\a b -> dataConName a == dataConName b) (concat [exprCons e | (_,_,e,_) <- imports])
      result = O
        [ ("schema",num (1::Int)), ("ghc",S "9.14.1"), ("module",S "THC.InterfaceClosure"), ("unit",S "dependency-closure")
        , ("boundary",S "actual-interface-unfoldings"), ("roots",A [S (varKey rootCtx v) | v <- roots])
        , ("sourceModules",A [S (modulePrefix d) | (d,_) <- reverse modules])
        , ("bindings",A (map importedBinding imports)), ("constructors",A (map (constructor rootCtx) cons))
        , ("groups",A [O [("recursive",B True),("ids",A [S (varKey d v) | (d,v,_,_) <- imports])]])
        , ("missingDefinitions",A [O [("id",S (varKey d v)),("type",S (pretty d (varType v))), ("reason",S "No executable interface unfolding; source export required")] | (d,v) <- missing])
        , ("sourceCore",S (pretty rootCtx [(v,e) | (_,v,e,_) <- imports]))
        ]
  writeFile (dir </> "THC.InterfaceClosure.json") (json result ++ "\n")
  putStrLn ("THC interface closure: " ++ show (length imports) ++ " actual unfoldings, " ++ show (length missing) ++ " missing source definitions")
