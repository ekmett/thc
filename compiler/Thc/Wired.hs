-- | Export-only lowering for operations that GHC itself eliminates after Core.
-- These templates are never inserted into the optimized ModGuts or passed back
-- through Core simplification: unary-class erasure changes the apparent Core
-- type, exactly as GHC.CoreToStg.myCollectArgs does at the representation boundary.
module Thc.Wired (wiredApplication, wiredRhs, isWiredVoid, wiredOrigin) where

import GHC.Plugins
import GHC.Builtin.Names
  ( hasKey, lazyIdKey, noinlineIdKey, noinlineConstraintIdKey, nospecIdKey
  , runRWKey, realWorldPrimIdKey )
import GHC.Core.Class (classAllSelIds)
import GHC.Core.Utils (isUnaryClassId)
import GHC.Types.Id.Make (mkDictSelRhs, realWorldPrimId)
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
