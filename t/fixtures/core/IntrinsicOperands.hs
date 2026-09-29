-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DataKinds, GHCForeignImportPrim, KindSignatures, MagicHash #-}
{-# LANGUAGE UnboxedTuples, UnliftedFFITypes #-}
module IntrinsicOperands where

import GHC.Exts

-- Direct raw declarations avoid newtype-unboxing case prefixes obscuring the
-- application-operand shape this fixture is intended to exercise.
foreign import prim "thc_string_v1_code_point_at"
  stringAt :: (Any :: UnliftedType) -> (Any :: UnliftedType) -> Int# -> Int#
foreign import prim "thc_vector_v1_vec_int64_with_lane"
  vectorWithLane :: (Any :: UnliftedType) -> Int# -> Int64# -> (Any :: UnliftedType)

data Operand = Operand Int#
data RawOperand = RawOperand (Any :: UnliftedType)

{-# NOINLINE takeOperand #-}
takeOperand :: MVar# RealWorld Operand -> Int#
takeOperand cell = runRW# (\s -> case takeMVar# cell s of
  (# _, Operand n #) -> n)

takeRaw :: MVar# RealWorld RawOperand -> Int# -> (Any :: UnliftedType)
takeRaw cell _ = runRW# (\s -> case takeMVar# cell s of
  (# _, RawOperand value #) -> value)

takeIndex :: MVar# RealWorld Operand -> Int# -> Int#
takeIndex cell _ = takeOperand cell

takeInt64 :: MVar# RealWorld Operand -> Int# -> Int64#
takeInt64 cell _ = intToInt64# (takeOperand cell)

stringOperands :: (Int# -> (Any :: UnliftedType)) -> (Int# -> (Any :: UnliftedType)) -> (Int# -> Int#) -> Int#
stringOperands first second third = stringAt (first 0#) (second 1#) (third 2#)

vectorOperands :: (Int# -> (Any :: UnliftedType)) -> (Int# -> Int#) -> (Int# -> Int64#) -> (Any :: UnliftedType)
vectorOperands first second third = vectorWithLane (first 0#) (second 1#) (third 2#)
