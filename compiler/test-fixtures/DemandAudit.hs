{-# LANGUAGE MagicHash, NoImplicitPrelude, ScopedTypeVariables, TypeApplications #-}
{-# OPTIONS_GHC -fno-worker-wrapper -fno-specialise -fno-spec-constr -fno-do-lambda-eta-expansion -fno-full-laziness #-}
-- Ordinary strict calls exercise caller demand without requesting an entry ABI.
module DemandAudit where
import GHC.Exts (Int#, (+#), (<=#), lazy)
import GHC.Base (seq)

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
