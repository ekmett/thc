-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE CPP, MagicHash, NoImplicitPrelude, ScopedTypeVariables, TypeApplications, UnboxedTuples #-}
{-# OPTIONS_GHC -fno-worker-wrapper -fno-specialise -fno-spec-constr -fno-do-lambda-eta-expansion -fno-full-laziness #-}
-- Ordinary strict calls exercise caller demand without requesting an entry ABI.
module DemandAudit where
import GHC.Exts (Int#, (+#), (<=#), lazy)
import GHC.Base (seq)
#ifdef THC_PRECISE_EXCEPTION_AUDIT
import GHC.Exts (State#, RealWorld, raiseIO#)
#endif

data Spine = Done | More Int# Spine

{-# OPAQUE makeTree #-}
makeTree :: Int# -> Spine
makeTree n = case n <=# 0# of
  1# -> Done
  _ -> More (n +# 7#) Done

{-# OPAQUE strictTree #-}
strictTree :: Spine -> Int#
strictTree tree = case tree of Done -> 0#; More n _ -> n

{-# OPAQUE ordinaryEntry #-}
ordinaryEntry :: Int# -> Int#
ordinaryEntry n = strictTree (makeTree n)

{-# OPAQUE strictPair #-}
strictPair :: Spine -> Spine -> Int#
strictPair first second = strictTree first +# strictTree second

{-# OPAQUE strictPoly #-}
strictPoly :: a -> Int#
strictPoly value = value `seq` 41#

{-# OPAQUE polyDataEntry #-}
polyDataEntry :: Int# -> Int#
polyDataEntry n = strictPoly @Spine (makeTree n)

-- Evaluating this function-valued argument must not enter its captured tree.
{-# OPAQUE polyFunctionEntry #-}
polyFunctionEntry :: Int# -> Int#
polyFunctionEntry n = strictPoly @(Spine -> Int#) (strictPair (makeTree n))

{-# OPAQUE ignore #-}
ignore :: Spine -> Int#
ignore _ = 41#

{-# OPAQUE absentEntry #-}
absentEntry :: Int# -> Int#
absentEntry n = ignore (makeTree n)

-- GHC retains lazy until CorePrep; the exporter must see it before erasure.
{-# OPAQUE lazyBarrier #-}
lazyBarrier :: Int# -> Int#
lazyBarrier n = strictTree (lazy (makeTree n))

{-# OPAQUE bottom #-}
bottom :: Spine
bottom = bottom

{-# OPAQUE deadEnd #-}
deadEnd :: Spine -> Int#
deadEnd _ = strictTree bottom

{-# OPAQUE deadEndEntry #-}
deadEndEntry :: Int# -> Int#
deadEndEntry n = deadEnd (makeTree n)

{-# OPAQUE keepPAP #-}
keepPAP :: (Spine -> Int#) -> Int#
keepPAP value = value `seq` 41#

{-# OPAQUE bottomPAPEntry #-}
bottomPAPEntry :: Int# -> Int#
bottomPAPEntry _ = keepPAP (strictPair bottom)

#ifdef THC_PRECISE_EXCEPTION_AUDIT
-- Metadata-only: this flagged export is never loaded by the THC runtime.
-- These are the two cases in Demand.hs, Note [Precise exceptions and strictness
-- analysis]. Forcing tree first could replace the precise exception with bottom.
{-# OPAQUE preciseBranch #-}
preciseBranch :: Int# -> Spine -> State# RealWorld -> (# State# RealWorld, Int# #)
preciseBranch n tree state = case n <=# 0# of
  1# -> raiseIO# Done state
  _ -> case strictTree tree of result -> (# state, result #)

{-# OPAQUE preciseStep #-}
preciseStep :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
preciseStep n state = case n <=# 0# of
  1# -> raiseIO# Done state
  _ -> (# state, n #)

{-# OPAQUE preciseScrutinee #-}
preciseScrutinee :: Int# -> Spine -> State# RealWorld -> (# State# RealWorld, Int# #)
preciseScrutinee n tree state = case preciseStep n state of
  (# state', value #) -> case strictTree tree of result -> (# state', result +# value #)

-- An ordinary pure scrutinee must retain the strict tree demand.
{-# OPAQUE pureStep #-}
pureStep :: Int# -> Int#
pureStep n = n +# 1#

{-# OPAQUE strictControl #-}
strictControl :: Int# -> Spine -> State# RealWorld -> (# State# RealWorld, Int# #)
strictControl n tree state = case pureStep n of
  value -> case strictTree tree of result -> (# state, result +# value #)

{-# OPAQUE preciseBranchEntry #-}
preciseBranchEntry :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
preciseBranchEntry n state = preciseBranch n (makeTree n) state

{-# OPAQUE preciseScrutineeEntry #-}
preciseScrutineeEntry :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
preciseScrutineeEntry n state = preciseScrutinee n (makeTree n) state

{-# OPAQUE strictControlEntry #-}
strictControlEntry :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
strictControlEntry n state = strictControl n (makeTree n) state
#endif
