-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Caller-side evaluation permissions from GHC 9.14.1 demand signatures.
-- These are neither entry calling-convention marks nor prior WHNF facts.
module THC.Demands (callDemand) where

import GHC.Plugins
import GHC.Builtin.Names (hasKey, lazyIdKey)
import GHC.Types.Demand (isStrUsedDmd, splitDmdSig)

-- The original, typed application spine is required. Deliberately do not
-- look through head casts/ticks: a nonfloating tick can stop the guaranteed
-- application depth (CoreToStg.Prep.cpeApp/val_args).
callDemand :: CoreExpr -> [CoreArg] -> Maybe (Int, [Bool])
callDemand (Var callee) args = Just (arity, marks)
  where
    (demands, _) = splitDmdSig (idDmdSig callee)
    arity = length demands
    values = filter (not . isTypeArg) args
    -- The demand-sig arity is a semantic threshold, independent of idArity
    -- and the number of leading lambdas. Coercions consume a value slot.
    marks
      | length values < arity = replicate (length values) False
      | otherwise = zipWith demanded values (map isStrUsedDmd demands ++ repeat False)
    demanded Coercion{} _ = False
    demanded argument strict = strict && not (isLazyExpr argument)
    isTypeArg Type{} = True
    isTypeArg _ = False
callDemand _ _ = Nothing

-- Match CorePrep.isLazyExpr before THC.Wired erases the lazyId marker.
isLazyExpr :: CoreExpr -> Bool
isLazyExpr (Cast expression _) = isLazyExpr expression
isLazyExpr (Tick _ expression) = isLazyExpr expression
isLazyExpr (Var callee `App` _ `App` _) = callee `hasKey` lazyIdKey
isLazyExpr _ = False
