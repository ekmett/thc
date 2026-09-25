-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- | Export-only lowering for operations that GHC itself eliminates after Core.
-- These templates are never inserted into the optimized ModGuts or passed back
-- through Core simplification: unary-class erasure changes the apparent Core
-- type, exactly as GHC.CoreToStg.myCollectArgs does at the representation boundary.
module THC.Wired (wiredApplication, wiredCase, wiredRhs, preservesWiredTypes, isWiredVoid, wiredOrigin) where

import GHC.Plugins
import GHC.Builtin.Names
  ( hasKey, lazyIdKey, noinlineIdKey, noinlineConstraintIdKey, nospecIdKey
  , runRWKey, realWorldPrimIdKey )
import GHC.Core.Class (classAllSelIds)
import GHC.Core.FVs (exprFreeVars)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.Utils (isUnaryClassId, isUnsafeEqualityCase)
import GHC.Types.Basic (OccInfo(IAmDead))
import GHC.Types.Id (setIdOccInfo)
import GHC.Types.Id.Make (mkDictSelRhs, realWorldPrimId)
import GHC.Types.Var.Set (elemVarSet)
import Data.List (findIndex)

-- | GHC's runtime identity operations. In particular this includes BOTH a unary
-- class's constructor and its selector. Boxing the constructor while treating
-- its selector as identity would change the runtime representation and strictness.
-- Primary source: GHC.Core.TyCon, Note [Unary class magic] (UCM1), and
-- GHC.CoreToStg.myCollectArgs; CoreToStg.Prep.cpeApp for the magic Ids.
isIdentity :: Id -> Bool
isIdentity v = isUnaryClassId v || any (v `hasKey`)
  [lazyIdKey, noinlineIdKey, noinlineConstraintIdKey, nospecIdKey]

-- | Apply the same late application rewrites as GHC. Remaining value arguments
-- are retained (e.g. @noinline f x@ becomes @f x@). No strict case is introduced,
-- preserving call-by-need for lazy/identity arguments.
wiredApplication :: CoreExpr -> Maybe CoreExpr
wiredApplication expression = case collectArgs expression of
  (Var v, args)
    | arg : rest <- dropWhile isTypeArg args
    , isIdentity v -> Just (mkApps arg rest)
    | arg : rest <- dropWhile isTypeArg args
    , v `hasKey` runRWKey -> Just (mkApps arg (Var realWorldPrimId : rest))
  _ -> Nothing
  where
    isTypeArg Type{} = True
    isTypeArg _ = False

-- | The exact late CoreToStg case rule, before erasing type/coercion arguments.
-- Installed interfaces need not retain the case binder's occurrence mark.
-- Recover only its deadness from the actual alternative RHSs, then still ask
-- GHC to check the wired Id/DataCon keys and the precise proof application.
-- In particular this supplies neither a first-class proof value nor a rewrite
-- for an arbitrary bottom or a case whose binder is used.
-- The returned Core is used only by export, never by the simplifier.
wiredCase :: CoreExpr -> Maybe CoreExpr
wiredCase (Case scrut bndr _ alts) = case isUnsafeEqualityCase scrut (setIdOccInfo bndr IAmDead) alts of
  Just rhs | not (bndr `elemVarSet` exprFreeVars rhs) -> Just rhs
  _ -> Nothing
wiredCase _ = Nothing

-- runRW# f becomes f realWorld#, while lazy/noinline return their retained
-- operand. Preserve the root certificate only when GHC confirms equal types;
-- unary-class erasure can change the apparent type and stays uncertified.
-- Keep the exact GHC type check here rather than trusting a printed type/name.
preservesWiredTypes :: CoreExpr -> CoreExpr -> Bool
preservesWiredTypes original lowered = case collectArgs original of
  (Var v, _) | any (v `hasKey`) [runRWKey, lazyIdKey, noinlineIdKey] ->
    eqType (exprType original) (exprType lowered)
  _ -> False

-- | First-class fallback and compiler-generated selectors absent from interface
-- unfoldings. The identity template deliberately has the payload representation;
-- it is for the erased export only, not a new typed Core source definition.
-- mkDictSelRhs returns a self-reference for unary classes, so handle them first.
wiredRhs :: Id -> Maybe CoreExpr
wiredRhs v
  | isIdentity v = unaryTemplate v Var
  | v `hasKey` runRWKey = unaryTemplate v (\f -> App (Var f) (Var realWorldPrimId))
  | Just cls <- isClassOpId_maybe v
  , Just index <- findIndex ((== idName v) . idName) (classAllSelIds cls)
  = Just (mkDictSelRhs cls index)
  | otherwise = Nothing

unaryTemplate :: Id -> (Id -> CoreExpr) -> Maybe CoreExpr
unaryTemplate v body = do
  let (typeBinders, rho) = splitForAllTyCoVars (idType v)
  (_, multiplicity, argumentType, _) <- splitFunTy_maybe rho
  let argument = setIdMult (mkTemplateLocal 1 argumentType) multiplicity
  pure (mkLams (typeBinders ++ [argument]) (body argument))

-- | State# RealWorld is a zero-width runtime token, not an external thunk.
-- The runtime must still sequence stateful primops and retain logical tuple slots.
isWiredVoid :: Id -> Bool
isWiredVoid v = v `hasKey` realWorldPrimIdKey

wiredOrigin :: Id -> Maybe String
wiredOrigin v
  | isUnaryClassId v = Just "GHC.CoreToStg.myCollectArgs/unary-class-erasure"
  | isIdentity v = Just "GHC.CoreToStg.Prep.cpeApp/identity-magic"
  | v `hasKey` runRWKey = Just "GHC.CoreToStg.Prep.cpeApp/runRW#"
  | isWiredVoid v = Just "GHC.Types.Id.Make.realWorldPrimId/zero-width-state"
  | Just _ <- isClassOpId_maybe v = Just "GHC.Types.Id.Make.mkDictSelRhs"
  | otherwise = Nothing
