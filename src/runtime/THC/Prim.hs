-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DataKinds, GHCForeignImportPrim, KindSignatures, MagicHash #-}
{-# LANGUAGE RoleAnnotations, StandaloneKindSignatures, UnboxedTuples #-}
{-# LANGUAGE UnliftedFFITypes, UnliftedNewtypes, PolyKinds, ExplicitForAll, Unsafe #-}

-- |
-- Module      : THC.Prim
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC runtime
--
-- Raw state-indexed foreign references and explicit Truffle message dispatch.
-- These are GC references, never native addresses. A library is the actual
-- acquired dispatcher, not a receiver or a pair. It must accept the receiver
-- supplied to each operation. External mutable objects belong to 'RealWorld';
-- the state index alone does not establish confinement or thread safety.
--
-- Runtime-shaped Vector API values are raw JDK references too. Their lane
-- types and operations are described under 'VecSpecies#'.
module THC.Prim
  ( Object#, InteropLibrary#, getInteropLibrary#, importPolyglotValue#
  , hasBufferElements#, isBufferWritable#, getBufferSize#
  , readBufferByte#, writeBufferByte#
  , hasArrayElements#, getArraySize#, readArrayElement#, writeArrayElement#
  , asLong#
    -- * Java host access and raw primitive arrays
  , lookupHostSymbol#
  , asGuestValue#
  , javaNull#
  , javaStringUtf8#
  , asHostObject#
  , execute#
  , instantiate#
  , readMember#
  , writeMember#
  , invokeMember#
  , boxJavaBoolean#
  , unboxJavaBoolean#
  , newJavaBooleanArray#
  , javaBooleanArrayLength#
  , readJavaBooleanArray#
  , writeJavaBooleanArray#
  , copyJavaBooleanArray#
  , objectAsJavaBooleanArray#
  , boxJavaByte#
  , unboxJavaByte#
  , newJavaByteArray#
  , javaByteArrayLength#
  , readJavaByteArray#
  , writeJavaByteArray#
  , copyJavaByteArray#
  , objectAsJavaByteArray#
  , boxJavaShort#
  , unboxJavaShort#
  , newJavaShortArray#
  , javaShortArrayLength#
  , readJavaShortArray#
  , writeJavaShortArray#
  , copyJavaShortArray#
  , objectAsJavaShortArray#
  , boxJavaChar#
  , unboxJavaChar#
  , newJavaCharArray#
  , javaCharArrayLength#
  , readJavaCharArray#
  , writeJavaCharArray#
  , copyJavaCharArray#
  , objectAsJavaCharArray#
  , boxJavaInt#
  , unboxJavaInt#
  , newJavaIntArray#
  , javaIntArrayLength#
  , readJavaIntArray#
  , writeJavaIntArray#
  , copyJavaIntArray#
  , objectAsJavaIntArray#
  , boxJavaLong#
  , unboxJavaLong#
  , newJavaLongArray#
  , javaLongArrayLength#
  , readJavaLongArray#
  , writeJavaLongArray#
  , copyJavaLongArray#
  , objectAsJavaLongArray#
  , boxJavaFloat#
  , unboxJavaFloat#
  , newJavaFloatArray#
  , javaFloatArrayLength#
  , readJavaFloatArray#
  , writeJavaFloatArray#
  , copyJavaFloatArray#
  , objectAsJavaFloatArray#
  , boxJavaDouble#
  , unboxJavaDouble#
  , newJavaDoubleArray#
  , javaDoubleArrayLength#
  , readJavaDoubleArray#
  , writeJavaDoubleArray#
  , copyJavaDoubleArray#
  , objectAsJavaDoubleArray#
  , JavaBooleanArray#
  , javaBooleanArrayAsObject#
  , JavaByteArray#
  , javaByteArrayAsObject#
  , JavaShortArray#
  , javaShortArrayAsObject#
  , JavaCharArray#
  , javaCharArrayAsObject#
  , JavaIntArray#
  , javaIntArrayAsObject#
  , JavaLongArray#
  , javaLongArrayAsObject#
  , JavaFloatArray#
  , javaFloatArrayAsObject#
  , JavaDoubleArray#
  , javaDoubleArrayAsObject#
    -- * Java array Vector API access
  , readJavaBooleanVector#
  , writeJavaBooleanVector#
  , readJavaBooleanVectorMasked#
  , writeJavaBooleanVectorMasked#
  , readJavaBooleanVectorIndexed#
  , writeJavaBooleanVectorIndexed#
  , readJavaBooleanVectorIndexedMasked#
  , writeJavaBooleanVectorIndexedMasked#
  , readJavaByteVector#
  , writeJavaByteVector#
  , readJavaByteVectorMasked#
  , writeJavaByteVectorMasked#
  , readJavaByteVectorIndexed#
  , writeJavaByteVectorIndexed#
  , readJavaByteVectorIndexedMasked#
  , writeJavaByteVectorIndexedMasked#
  , readJavaShortVector#
  , writeJavaShortVector#
  , readJavaShortVectorMasked#
  , writeJavaShortVectorMasked#
  , readJavaShortVectorIndexed#
  , writeJavaShortVectorIndexed#
  , readJavaShortVectorIndexedMasked#
  , writeJavaShortVectorIndexedMasked#
  , readJavaCharVector#
  , writeJavaCharVector#
  , readJavaCharVectorMasked#
  , writeJavaCharVectorMasked#
  , readJavaCharVectorIndexed#
  , writeJavaCharVectorIndexed#
  , readJavaCharVectorIndexedMasked#
  , writeJavaCharVectorIndexedMasked#
  , readJavaIntVector#
  , writeJavaIntVector#
  , readJavaIntVectorMasked#
  , writeJavaIntVectorMasked#
  , readJavaIntVectorIndexed#
  , writeJavaIntVectorIndexed#
  , readJavaIntVectorIndexedMasked#
  , writeJavaIntVectorIndexedMasked#
  , readJavaLongVector#
  , writeJavaLongVector#
  , readJavaLongVectorMasked#
  , writeJavaLongVectorMasked#
  , readJavaLongVectorIndexed#
  , writeJavaLongVectorIndexed#
  , readJavaLongVectorIndexedMasked#
  , writeJavaLongVectorIndexedMasked#
  , readJavaFloatVector#
  , writeJavaFloatVector#
  , readJavaFloatVectorMasked#
  , writeJavaFloatVectorMasked#
  , readJavaFloatVectorIndexed#
  , writeJavaFloatVectorIndexed#
  , readJavaFloatVectorIndexedMasked#
  , writeJavaFloatVectorIndexedMasked#
  , readJavaDoubleVector#
  , writeJavaDoubleVector#
  , readJavaDoubleVectorMasked#
  , writeJavaDoubleVectorMasked#
  , readJavaDoubleVectorIndexed#
  , writeJavaDoubleVectorIndexed#
  , readJavaDoubleVectorIndexedMasked#
  , writeJavaDoubleVectorIndexedMasked#
  , readJavaMask#
  , writeJavaMask#
  , readJavaShuffle#
  , writeJavaShuffle#
    -- * Runtime-shaped Vector API
  , Vec#, VecMask#, VecShuffle#, VecSpecies#
  , int8Species#
  , broadcastInt8#
  , vecInt8Lane#
  , vecInt8WithLane#
  , vecInt8ReduceAdd#
  , indexInt8Vector#
  , readInt8Vector#
  , writeInt8Vector#
  , int16Species#
  , broadcastInt16#
  , vecInt16Lane#
  , vecInt16WithLane#
  , vecInt16ReduceAdd#
  , indexInt16Vector#
  , readInt16Vector#
  , writeInt16Vector#
  , int32Species#
  , broadcastInt32#
  , vecInt32Lane#
  , vecInt32WithLane#
  , vecInt32ReduceAdd#
  , indexInt32Vector#
  , readInt32Vector#
  , writeInt32Vector#
  , int64Species#
  , broadcastInt64#
  , vecInt64Lane#
  , vecInt64WithLane#
  , vecInt64ReduceAdd#
  , indexInt64Vector#
  , readInt64Vector#
  , writeInt64Vector#
  , floatSpecies#
  , broadcastFloat#
  , vecFloatLane#
  , vecFloatWithLane#
  , vecFloatReduceAdd#
  , indexFloatVector#
  , readFloatVector#
  , writeFloatVector#
  , doubleSpecies#
  , broadcastDouble#
  , vecDoubleLane#
  , vecDoubleWithLane#
  , vecDoubleReduceAdd#
  , indexDoubleVector#
  , readDoubleVector#
  , writeDoubleVector#
  , speciesWithShape#
  , speciesLength#
  , speciesElementBits#
  , speciesVectorBits#
  , speciesVectorBytes#
  , speciesLoopBound#
  , speciesPartLimit#
  , speciesIndexInRange#
  , speciesMaskAll#
  , speciesZero#
  , vecSpecies#
  , maskSpecies#
  , shuffleSpecies#
  , maskFromBits#
  , maskToBits#
  , maskTrueCount#
  , maskFirstTrue#
  , maskLastTrue#
  , maskAnyTrue#
  , maskAllTrue#
  , maskLane#
  , maskAnd#
  , maskOr#
  , maskXor#
  , maskAndNot#
  , maskNot#
  , maskCast#
  , shuffleIota#
  , shuffleLane#
  , shuffleValid#
  , shuffleWrap#
  , shuffleCast#
  , vecToShuffle#
  , vecAdd#
  , vecSub#
  , vecMul#
  , vecDiv#
  , vecMin#
  , vecMax#
  , vecAddMasked#
  , vecSubMasked#
  , vecMulMasked#
  , vecDivMasked#
  , vecAbs#
  , vecNeg#
  , vecEq#
  , vecNe#
  , vecLt#
  , vecLe#
  , vecGt#
  , vecGe#
  , vecUnsignedLt#
  , vecBlend#
  , vecRearrange#
  , vecRearrangeMasked#
  , vecCompress#
  , vecExpand#
  , vecConvert#
  , vecReinterpret#
  ) where

import Data.Kind (Type)
import GHC.Exts

-- | A raw JDK vector. The lane parameter is nominal: 'Int8#', 'Int16#',
-- 'Int32#', 'Int64#', 'Float#' and 'Double#' map to Java byte, short, int,
-- long, float and double. In particular, machine-sized 'Int#' is not Int32.
type Vec# :: TYPE r -> UnliftedType
type role Vec# nominal
newtype Vec# e = Vec# (Any :: UnliftedType)

-- | A raw JDK lane mask; its species must agree with the vector it selects.
type VecMask# :: TYPE r -> UnliftedType
type role VecMask# nominal
newtype VecMask# e = VecMask# (Any :: UnliftedType)

-- | A raw JDK lane permutation.
type VecShuffle# :: TYPE r -> UnliftedType
type role VecShuffle# nominal
newtype VecShuffle# e = VecShuffle# (Any :: UnliftedType)

-- | A raw JDK element/shape descriptor. Species functions take a bit width:
-- 0 selects SPECIES_PREFERRED, -1 SPECIES_MAX, and 64/128/256/512 select an
-- explicit shape. Widths and masks may be computed at run time. Keep species
-- loop-invariant where possible so Graal can specialize the vector intrinsics.
--
-- Masked memory operations use native byte order and /element/ offsets.
-- Inactive lanes do not read or write memory; inactive loaded lanes are zero.
-- Mutable reads and writes thread 'State#'. Conversion uses Java numeric cast
-- semantics; reinterpretation preserves bits. Signed comparisons use Java's
-- lane types; 'vecUnsignedLt#' compares integer lane bits as unsigned.
--
-- The JDK may scalarize a shape unsupported by the host. It also rejects
-- mismatched species, invalid shuffle indices and out-of-range active lanes.
-- 'maskToBits#' represents at most 64 lanes, as in VectorMask.toLong.
type VecSpecies# :: TYPE r -> UnliftedType
type role VecSpecies# nominal
newtype VecSpecies# e = VecSpecies# (Any :: UnliftedType)

foreign import prim "thc_vector_v1_int8_species"
  int8Species# :: Int# -> VecSpecies# Int8#

foreign import prim "thc_vector_v1_broadcast_int8"
  broadcastInt8# :: (VecSpecies# Int8#) -> Int8# -> Vec# Int8#

foreign import prim "thc_vector_v1_vec_int8_lane"
  vecInt8Lane# :: (Vec# Int8#) -> Int# -> Int8#

foreign import prim "thc_vector_v1_vec_int8_with_lane"
  vecInt8WithLane# :: (Vec# Int8#) -> Int# -> Int8# -> Vec# Int8#

foreign import prim "thc_vector_v1_vec_int8_reduce_add"
  vecInt8ReduceAdd# :: (Vec# Int8#) -> (VecMask# Int8#) -> Int8#

foreign import prim "thc_vector_v1_index_int8_vector"
  indexInt8Vector# :: (VecSpecies# Int8#) -> ByteArray# -> Int# -> (VecMask# Int8#) -> Vec# Int8#

foreign import prim "thc_vector_v1_read_int8_vector"
  readInt8Vector# :: (VecSpecies# Int8#) -> (MutableByteArray# s) -> Int# -> (VecMask# Int8#) -> (State# s) -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_int8_vector"
  writeInt8Vector# :: (MutableByteArray# s) -> Int# -> (Vec# Int8#) -> (VecMask# Int8#) -> (State# s) -> State# s

foreign import prim "thc_vector_v1_int16_species"
  int16Species# :: Int# -> VecSpecies# Int16#

foreign import prim "thc_vector_v1_broadcast_int16"
  broadcastInt16# :: (VecSpecies# Int16#) -> Int16# -> Vec# Int16#

foreign import prim "thc_vector_v1_vec_int16_lane"
  vecInt16Lane# :: (Vec# Int16#) -> Int# -> Int16#

foreign import prim "thc_vector_v1_vec_int16_with_lane"
  vecInt16WithLane# :: (Vec# Int16#) -> Int# -> Int16# -> Vec# Int16#

foreign import prim "thc_vector_v1_vec_int16_reduce_add"
  vecInt16ReduceAdd# :: (Vec# Int16#) -> (VecMask# Int16#) -> Int16#

foreign import prim "thc_vector_v1_index_int16_vector"
  indexInt16Vector# :: (VecSpecies# Int16#) -> ByteArray# -> Int# -> (VecMask# Int16#) -> Vec# Int16#

foreign import prim "thc_vector_v1_read_int16_vector"
  readInt16Vector# :: (VecSpecies# Int16#) -> (MutableByteArray# s) -> Int# -> (VecMask# Int16#) -> (State# s) -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_int16_vector"
  writeInt16Vector# :: (MutableByteArray# s) -> Int# -> (Vec# Int16#) -> (VecMask# Int16#) -> (State# s) -> State# s

foreign import prim "thc_vector_v1_int32_species"
  int32Species# :: Int# -> VecSpecies# Int32#

foreign import prim "thc_vector_v1_broadcast_int32"
  broadcastInt32# :: (VecSpecies# Int32#) -> Int32# -> Vec# Int32#

foreign import prim "thc_vector_v1_vec_int32_lane"
  vecInt32Lane# :: (Vec# Int32#) -> Int# -> Int32#

foreign import prim "thc_vector_v1_vec_int32_with_lane"
  vecInt32WithLane# :: (Vec# Int32#) -> Int# -> Int32# -> Vec# Int32#

foreign import prim "thc_vector_v1_vec_int32_reduce_add"
  vecInt32ReduceAdd# :: (Vec# Int32#) -> (VecMask# Int32#) -> Int32#

foreign import prim "thc_vector_v1_index_int32_vector"
  indexInt32Vector# :: (VecSpecies# Int32#) -> ByteArray# -> Int# -> (VecMask# Int32#) -> Vec# Int32#

foreign import prim "thc_vector_v1_read_int32_vector"
  readInt32Vector# :: (VecSpecies# Int32#) -> (MutableByteArray# s) -> Int# -> (VecMask# Int32#) -> (State# s) -> (# State# s, Vec# Int32# #)

foreign import prim "thc_vector_v1_write_int32_vector"
  writeInt32Vector# :: (MutableByteArray# s) -> Int# -> (Vec# Int32#) -> (VecMask# Int32#) -> (State# s) -> State# s

foreign import prim "thc_vector_v1_int64_species"
  int64Species# :: Int# -> VecSpecies# Int64#

foreign import prim "thc_vector_v1_broadcast_int64"
  broadcastInt64# :: (VecSpecies# Int64#) -> Int64# -> Vec# Int64#

foreign import prim "thc_vector_v1_vec_int64_lane"
  vecInt64Lane# :: (Vec# Int64#) -> Int# -> Int64#

foreign import prim "thc_vector_v1_vec_int64_with_lane"
  vecInt64WithLane# :: (Vec# Int64#) -> Int# -> Int64# -> Vec# Int64#

foreign import prim "thc_vector_v1_vec_int64_reduce_add"
  vecInt64ReduceAdd# :: (Vec# Int64#) -> (VecMask# Int64#) -> Int64#

foreign import prim "thc_vector_v1_index_int64_vector"
  indexInt64Vector# :: (VecSpecies# Int64#) -> ByteArray# -> Int# -> (VecMask# Int64#) -> Vec# Int64#

foreign import prim "thc_vector_v1_read_int64_vector"
  readInt64Vector# :: (VecSpecies# Int64#) -> (MutableByteArray# s) -> Int# -> (VecMask# Int64#) -> (State# s) -> (# State# s, Vec# Int64# #)

foreign import prim "thc_vector_v1_write_int64_vector"
  writeInt64Vector# :: (MutableByteArray# s) -> Int# -> (Vec# Int64#) -> (VecMask# Int64#) -> (State# s) -> State# s

foreign import prim "thc_vector_v1_float_species"
  floatSpecies# :: Int# -> VecSpecies# Float#

foreign import prim "thc_vector_v1_broadcast_float"
  broadcastFloat# :: (VecSpecies# Float#) -> Float# -> Vec# Float#

foreign import prim "thc_vector_v1_vec_float_lane"
  vecFloatLane# :: (Vec# Float#) -> Int# -> Float#

foreign import prim "thc_vector_v1_vec_float_with_lane"
  vecFloatWithLane# :: (Vec# Float#) -> Int# -> Float# -> Vec# Float#

foreign import prim "thc_vector_v1_vec_float_reduce_add"
  vecFloatReduceAdd# :: (Vec# Float#) -> (VecMask# Float#) -> Float#

foreign import prim "thc_vector_v1_index_float_vector"
  indexFloatVector# :: (VecSpecies# Float#) -> ByteArray# -> Int# -> (VecMask# Float#) -> Vec# Float#

foreign import prim "thc_vector_v1_read_float_vector"
  readFloatVector# :: (VecSpecies# Float#) -> (MutableByteArray# s) -> Int# -> (VecMask# Float#) -> (State# s) -> (# State# s, Vec# Float# #)

foreign import prim "thc_vector_v1_write_float_vector"
  writeFloatVector# :: (MutableByteArray# s) -> Int# -> (Vec# Float#) -> (VecMask# Float#) -> (State# s) -> State# s

foreign import prim "thc_vector_v1_double_species"
  doubleSpecies# :: Int# -> VecSpecies# Double#

foreign import prim "thc_vector_v1_broadcast_double"
  broadcastDouble# :: (VecSpecies# Double#) -> Double# -> Vec# Double#

foreign import prim "thc_vector_v1_vec_double_lane"
  vecDoubleLane# :: (Vec# Double#) -> Int# -> Double#

foreign import prim "thc_vector_v1_vec_double_with_lane"
  vecDoubleWithLane# :: (Vec# Double#) -> Int# -> Double# -> Vec# Double#

foreign import prim "thc_vector_v1_vec_double_reduce_add"
  vecDoubleReduceAdd# :: (Vec# Double#) -> (VecMask# Double#) -> Double#

foreign import prim "thc_vector_v1_index_double_vector"
  indexDoubleVector# :: (VecSpecies# Double#) -> ByteArray# -> Int# -> (VecMask# Double#) -> Vec# Double#

foreign import prim "thc_vector_v1_read_double_vector"
  readDoubleVector# :: (VecSpecies# Double#) -> (MutableByteArray# s) -> Int# -> (VecMask# Double#) -> (State# s) -> (# State# s, Vec# Double# #)

foreign import prim "thc_vector_v1_write_double_vector"
  writeDoubleVector# :: (MutableByteArray# s) -> Int# -> (Vec# Double#) -> (VecMask# Double#) -> (State# s) -> State# s

foreign import prim "thc_vector_v1_species_with_shape"
  speciesWithShape# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int# -> VecSpecies# e

foreign import prim "thc_vector_v1_species_length"
  speciesLength# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int#

foreign import prim "thc_vector_v1_species_element_bits"
  speciesElementBits# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int#

foreign import prim "thc_vector_v1_species_vector_bits"
  speciesVectorBits# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int#

foreign import prim "thc_vector_v1_species_vector_bytes"
  speciesVectorBytes# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int#

foreign import prim "thc_vector_v1_species_loop_bound"
  speciesLoopBound# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int# -> Int#

foreign import prim "thc_vector_v1_species_part_limit"
  speciesPartLimit# :: forall r (e :: TYPE r) q (f :: TYPE q). (VecSpecies# e) -> (VecSpecies# f) -> Int# -> Int#

foreign import prim "thc_vector_v1_species_index_in_range"
  speciesIndexInRange# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int# -> Int# -> VecMask# e

foreign import prim "thc_vector_v1_species_mask_all"
  speciesMaskAll# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int# -> VecMask# e

foreign import prim "thc_vector_v1_species_zero"
  speciesZero# :: forall r (e :: TYPE r). (VecSpecies# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_species"
  vecSpecies# :: forall r (e :: TYPE r). (Vec# e) -> VecSpecies# e

foreign import prim "thc_vector_v1_mask_species"
  maskSpecies# :: forall r (e :: TYPE r). (VecMask# e) -> VecSpecies# e

foreign import prim "thc_vector_v1_shuffle_species"
  shuffleSpecies# :: forall r (e :: TYPE r). (VecShuffle# e) -> VecSpecies# e

foreign import prim "thc_vector_v1_mask_from_bits"
  maskFromBits# :: forall r (e :: TYPE r). (VecSpecies# e) -> Word# -> VecMask# e

foreign import prim "thc_vector_v1_mask_to_bits"
  maskToBits# :: forall r (e :: TYPE r). (VecMask# e) -> Word#

foreign import prim "thc_vector_v1_mask_true_count"
  maskTrueCount# :: forall r (e :: TYPE r). (VecMask# e) -> Int#

foreign import prim "thc_vector_v1_mask_first_true"
  maskFirstTrue# :: forall r (e :: TYPE r). (VecMask# e) -> Int#

foreign import prim "thc_vector_v1_mask_last_true"
  maskLastTrue# :: forall r (e :: TYPE r). (VecMask# e) -> Int#

foreign import prim "thc_vector_v1_mask_any_true"
  maskAnyTrue# :: forall r (e :: TYPE r). (VecMask# e) -> Int#

foreign import prim "thc_vector_v1_mask_all_true"
  maskAllTrue# :: forall r (e :: TYPE r). (VecMask# e) -> Int#

foreign import prim "thc_vector_v1_mask_lane"
  maskLane# :: forall r (e :: TYPE r). (VecMask# e) -> Int# -> Int#

foreign import prim "thc_vector_v1_mask_and"
  maskAnd# :: forall r (e :: TYPE r). (VecMask# e) -> (VecMask# e) -> VecMask# e

foreign import prim "thc_vector_v1_mask_or"
  maskOr# :: forall r (e :: TYPE r). (VecMask# e) -> (VecMask# e) -> VecMask# e

foreign import prim "thc_vector_v1_mask_xor"
  maskXor# :: forall r (e :: TYPE r). (VecMask# e) -> (VecMask# e) -> VecMask# e

foreign import prim "thc_vector_v1_mask_and_not"
  maskAndNot# :: forall r (e :: TYPE r). (VecMask# e) -> (VecMask# e) -> VecMask# e

foreign import prim "thc_vector_v1_mask_not"
  maskNot# :: forall r (e :: TYPE r). (VecMask# e) -> VecMask# e

foreign import prim "thc_vector_v1_mask_cast"
  maskCast# :: forall r (e :: TYPE r) q (f :: TYPE q). (VecMask# e) -> (VecSpecies# f) -> VecMask# f

foreign import prim "thc_vector_v1_shuffle_iota"
  shuffleIota# :: forall r (e :: TYPE r). (VecSpecies# e) -> Int# -> Int# -> Int# -> VecShuffle# e

foreign import prim "thc_vector_v1_shuffle_lane"
  shuffleLane# :: forall r (e :: TYPE r). (VecShuffle# e) -> Int# -> Int#

foreign import prim "thc_vector_v1_shuffle_valid"
  shuffleValid# :: forall r (e :: TYPE r). (VecShuffle# e) -> VecMask# e

foreign import prim "thc_vector_v1_shuffle_wrap"
  shuffleWrap# :: forall r (e :: TYPE r). (VecShuffle# e) -> VecShuffle# e

foreign import prim "thc_vector_v1_shuffle_cast"
  shuffleCast# :: forall r (e :: TYPE r) q (f :: TYPE q). (VecShuffle# e) -> (VecSpecies# f) -> VecShuffle# f

foreign import prim "thc_vector_v1_vec_to_shuffle"
  vecToShuffle# :: forall r (e :: TYPE r). (Vec# e) -> VecShuffle# e

foreign import prim "thc_vector_v1_vec_add"
  vecAdd# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_sub"
  vecSub# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_mul"
  vecMul# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_div"
  vecDiv# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_min"
  vecMin# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_max"
  vecMax# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_add_masked"
  vecAddMasked# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_sub_masked"
  vecSubMasked# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_mul_masked"
  vecMulMasked# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_div_masked"
  vecDivMasked# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_abs"
  vecAbs# :: forall r (e :: TYPE r). (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_neg"
  vecNeg# :: forall r (e :: TYPE r). (Vec# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_eq"
  vecEq# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_ne"
  vecNe# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_lt"
  vecLt# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_le"
  vecLe# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_gt"
  vecGt# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_ge"
  vecGe# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_unsigned_lt"
  vecUnsignedLt# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> VecMask# e

foreign import prim "thc_vector_v1_vec_blend"
  vecBlend# :: forall r (e :: TYPE r). (Vec# e) -> (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_rearrange"
  vecRearrange# :: forall r (e :: TYPE r). (Vec# e) -> (VecShuffle# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_rearrange_masked"
  vecRearrangeMasked# :: forall r (e :: TYPE r). (Vec# e) -> (VecShuffle# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_compress"
  vecCompress# :: forall r (e :: TYPE r). (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_expand"
  vecExpand# :: forall r (e :: TYPE r). (Vec# e) -> (VecMask# e) -> Vec# e

foreign import prim "thc_vector_v1_vec_convert"
  vecConvert# :: forall r (e :: TYPE r) q (f :: TYPE q). (Vec# e) -> (VecSpecies# f) -> Int# -> Vec# f

foreign import prim "thc_vector_v1_vec_reinterpret"
  vecReinterpret# :: forall r (e :: TYPE r) q (f :: TYPE q). (Vec# e) -> (VecSpecies# f) -> Int# -> Vec# f


-- | An arbitrary raw Java reference. Not every object is an interop receiver.
type Object# :: Type -> TYPE UnliftedRep
type role Object# nominal
newtype Object# s = Object# (Any :: TYPE UnliftedRep)

-- | An actual acquired Truffle dispatcher. Its acquisition site owns adoption.
type InteropLibrary# :: Type -> TYPE UnliftedRep
type role InteropLibrary# nominal
newtype InteropLibrary# s = InteropLibrary# (Any :: TYPE UnliftedRep)

-- | Acquire a dispatcher for this receiver. An invalid receiver raises a
-- catchable foreign exception; acquisition does not convert the object.
foreign import prim "thc_interop_v1_get_library"
  getInteropLibrary# :: Object# s -> InteropLibrary# s
-- | Compatibility import from an existing opaque THC.Polyglot.Value payload.
-- Arbitrary lifted values are not valid inputs. Ownership is checked once here.
foreign import prim "thc_interop_v1_import_value"
  importPolyglotValue# :: Any -> State# RealWorld -> (# State# RealWorld, Object# RealWorld #)
foreign import prim "thc_interop_v1_has_buffer_elements"
  hasBufferElements# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)
foreign import prim "thc_interop_v1_is_buffer_writable"
  isBufferWritable# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)
foreign import prim "thc_interop_v1_get_buffer_size"
  getBufferSize# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)
-- | Read a signed 8-bit value at a byte offset. Unsupported access and invalid
-- offsets use the foreign-exception path, not sentinel return values.
foreign import prim "thc_interop_v1_read_buffer_byte"
  readBufferByte# :: Object# s -> InteropLibrary# s -> Int# -> State# s -> (# State# s, Int8# #)
-- | Write at a byte offset; the returned state follows the completed effect.
foreign import prim "thc_interop_v1_write_buffer_byte"
  writeBufferByte# :: Object# s -> InteropLibrary# s -> Int# -> Int8# -> State# s -> State# s
foreign import prim "thc_interop_v1_has_array_elements"
  hasArrayElements# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)
foreign import prim "thc_interop_v1_get_array_size"
  getArraySize# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)
-- | Read at an element index. The result is an opaque, possibly heterogeneous
-- object; acquire its own dispatcher before sending it messages.
foreign import prim "thc_interop_v1_read_array_element"
  readArrayElement# :: Object# s -> InteropLibrary# s -> Int# -> State# s -> (# State# s, Object# s #)
-- | Replace an element at an element index, if the receiver permits it.
foreign import prim "thc_interop_v1_write_array_element"
  writeArrayElement# :: Object# s -> InteropLibrary# s -> Int# -> Object# s -> State# s -> State# s
foreign import prim "thc_interop_v1_as_long"
  asLong# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int64# #)

-- Java primitive arrays are the actual JVM arrays, with no THC wrapper.
-- Their state parameter is nominal; externally shared arrays belong to RealWorld.

-- | Raw Java @boolean[]@ storage.
type JavaBooleanArray# :: Type -> TYPE UnliftedRep
type role JavaBooleanArray# nominal
newtype JavaBooleanArray# s = JavaBooleanArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaBooleanArrayAsObject# :: JavaBooleanArray# s -> Object# s
javaBooleanArrayAsObject# (JavaBooleanArray# value) = Object# value

-- | Raw Java @byte[]@ storage.
type JavaByteArray# :: Type -> TYPE UnliftedRep
type role JavaByteArray# nominal
newtype JavaByteArray# s = JavaByteArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaByteArrayAsObject# :: JavaByteArray# s -> Object# s
javaByteArrayAsObject# (JavaByteArray# value) = Object# value

-- | Raw Java @short[]@ storage.
type JavaShortArray# :: Type -> TYPE UnliftedRep
type role JavaShortArray# nominal
newtype JavaShortArray# s = JavaShortArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaShortArrayAsObject# :: JavaShortArray# s -> Object# s
javaShortArrayAsObject# (JavaShortArray# value) = Object# value

-- | Raw Java @char[]@ storage.
type JavaCharArray# :: Type -> TYPE UnliftedRep
type role JavaCharArray# nominal
newtype JavaCharArray# s = JavaCharArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaCharArrayAsObject# :: JavaCharArray# s -> Object# s
javaCharArrayAsObject# (JavaCharArray# value) = Object# value

-- | Raw Java @int[]@ storage.
type JavaIntArray# :: Type -> TYPE UnliftedRep
type role JavaIntArray# nominal
newtype JavaIntArray# s = JavaIntArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaIntArrayAsObject# :: JavaIntArray# s -> Object# s
javaIntArrayAsObject# (JavaIntArray# value) = Object# value

-- | Raw Java @long[]@ storage.
type JavaLongArray# :: Type -> TYPE UnliftedRep
type role JavaLongArray# nominal
newtype JavaLongArray# s = JavaLongArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaLongArrayAsObject# :: JavaLongArray# s -> Object# s
javaLongArrayAsObject# (JavaLongArray# value) = Object# value

-- | Raw Java @float[]@ storage.
type JavaFloatArray# :: Type -> TYPE UnliftedRep
type role JavaFloatArray# nominal
newtype JavaFloatArray# s = JavaFloatArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaFloatArrayAsObject# :: JavaFloatArray# s -> Object# s
javaFloatArrayAsObject# (JavaFloatArray# value) = Object# value

-- | Raw Java @double[]@ storage.
type JavaDoubleArray# :: Type -> TYPE UnliftedRep
type role JavaDoubleArray# nominal
newtype JavaDoubleArray# s = JavaDoubleArray# (Any :: TYPE UnliftedRep)

-- | Forget the array type without allocating or copying.
javaDoubleArrayAsObject# :: JavaDoubleArray# s -> Object# s
javaDoubleArrayAsObject# (JavaDoubleArray# value) = Object# value

-- | Look up a Java class through the context host-class lookup policy.
foreign import prim "thc_interop_v1_lookup_host_symbol"
  lookupHostSymbol# :: Object# RealWorld -> State# RealWorld -> (# State# RealWorld, Object# RealWorld #)

-- | Adapt an existing raw Java object for interop without copying it. HostAccess controls exposed members.
foreign import prim "thc_interop_v1_as_guest_value"
  asGuestValue# :: Object# s -> State# s -> (# State# s, Object# s #)

-- | The interop null value, usable as a Java argument.
foreign import prim "thc_interop_v1_java_null"
  javaNull# :: State# s -> (# State# s, Object# s #)

-- | Copy a NUL-terminated UTF-8 string into a Java string.
foreign import prim "thc_interop_v1_java_string_utf8"
  javaStringUtf8# :: Addr# -> State# s -> (# State# s, Object# s #)

-- | Obtain the raw Java reference behind a host interop object.
foreign import prim "thc_interop_v1_as_host_object"
  asHostObject# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Object# s #)

-- | Pass an immutable argument array of interop values directly; arbitrary arity and scalar kinds are supported.
foreign import prim "thc_interop_v1_execute"
  execute# :: Object# s -> InteropLibrary# s -> Array# (Object# s) -> State# s -> (# State# s, Object# s #)

-- | Pass an immutable argument array of interop values directly; arbitrary arity and scalar kinds are supported.
foreign import prim "thc_interop_v1_instantiate"
  instantiate# :: Object# s -> InteropLibrary# s -> Array# (Object# s) -> State# s -> (# State# s, Object# s #)

-- | Read a named field or member. The name must support the interop string protocol.
foreign import prim "thc_interop_v1_interop_read_member"
  readMember# :: Object# s -> InteropLibrary# s -> Object# s -> State# s -> (# State# s, Object# s #)

-- | Write a named field or member through normal HostAccess policy.
foreign import prim "thc_interop_v1_interop_write_member"
  writeMember# :: Object# s -> InteropLibrary# s -> Object# s -> Object# s -> State# s -> State# s

-- | Invoke a named static or instance method with an immutable array of interop arguments.
foreign import prim "thc_interop_v1_invoke_member"
  invokeMember# :: Object# s -> InteropLibrary# s -> Object# s -> Array# (Object# s) -> State# s -> (# State# s, Object# s #)

-- | Box a Java boolean scalar for interop. Zero is false; nonzero is true.
foreign import prim "thc_interop_v1_box_java_boolean"
  boxJavaBoolean# :: Int# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java boolean, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_boolean"
  unboxJavaBoolean# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)

-- | Allocate a zero-initialized raw Java boolean array.
foreign import prim "thc_interop_v1_new_java_boolean_array"
  newJavaBooleanArray# :: Int# -> State# s -> (# State# s, JavaBooleanArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_boolean_array_length"
  javaBooleanArrayLength# :: JavaBooleanArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_boolean_array"
  readJavaBooleanArray# :: JavaBooleanArray# s -> Int# -> State# s -> (# State# s, Int# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_boolean_array"
  writeJavaBooleanArray# :: JavaBooleanArray# s -> Int# -> Int# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_boolean_array"
  copyJavaBooleanArray# :: JavaBooleanArray# s -> Int# -> JavaBooleanArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java boolean array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_boolean_array"
  objectAsJavaBooleanArray# :: Object# s -> State# s -> (# State# s, JavaBooleanArray# s #)

-- | Box a Java byte scalar for interop.
foreign import prim "thc_interop_v1_box_java_byte"
  boxJavaByte# :: Int8# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java byte, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_byte"
  unboxJavaByte# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int8# #)

-- | Allocate a zero-initialized raw Java byte array.
foreign import prim "thc_interop_v1_new_java_byte_array"
  newJavaByteArray# :: Int# -> State# s -> (# State# s, JavaByteArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_byte_array_length"
  javaByteArrayLength# :: JavaByteArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_byte_array"
  readJavaByteArray# :: JavaByteArray# s -> Int# -> State# s -> (# State# s, Int8# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_byte_array"
  writeJavaByteArray# :: JavaByteArray# s -> Int# -> Int8# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_byte_array"
  copyJavaByteArray# :: JavaByteArray# s -> Int# -> JavaByteArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java byte array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_byte_array"
  objectAsJavaByteArray# :: Object# s -> State# s -> (# State# s, JavaByteArray# s #)

-- | Box a Java short scalar for interop.
foreign import prim "thc_interop_v1_box_java_short"
  boxJavaShort# :: Int16# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java short, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_short"
  unboxJavaShort# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int16# #)

-- | Allocate a zero-initialized raw Java short array.
foreign import prim "thc_interop_v1_new_java_short_array"
  newJavaShortArray# :: Int# -> State# s -> (# State# s, JavaShortArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_short_array_length"
  javaShortArrayLength# :: JavaShortArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_short_array"
  readJavaShortArray# :: JavaShortArray# s -> Int# -> State# s -> (# State# s, Int16# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_short_array"
  writeJavaShortArray# :: JavaShortArray# s -> Int# -> Int16# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_short_array"
  copyJavaShortArray# :: JavaShortArray# s -> Int# -> JavaShortArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java short array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_short_array"
  objectAsJavaShortArray# :: Object# s -> State# s -> (# State# s, JavaShortArray# s #)

-- | Box a Java char scalar for interop. The value is one unsigned UTF-16 code unit.
foreign import prim "thc_interop_v1_box_java_char"
  boxJavaChar# :: Word16# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java char, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_char"
  unboxJavaChar# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Word16# #)

-- | Allocate a zero-initialized raw Java char array.
foreign import prim "thc_interop_v1_new_java_char_array"
  newJavaCharArray# :: Int# -> State# s -> (# State# s, JavaCharArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_char_array_length"
  javaCharArrayLength# :: JavaCharArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_char_array"
  readJavaCharArray# :: JavaCharArray# s -> Int# -> State# s -> (# State# s, Word16# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_char_array"
  writeJavaCharArray# :: JavaCharArray# s -> Int# -> Word16# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_char_array"
  copyJavaCharArray# :: JavaCharArray# s -> Int# -> JavaCharArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java char array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_char_array"
  objectAsJavaCharArray# :: Object# s -> State# s -> (# State# s, JavaCharArray# s #)

-- | Box a Java int scalar for interop.
foreign import prim "thc_interop_v1_box_java_int"
  boxJavaInt# :: Int32# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java int, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_int"
  unboxJavaInt# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int32# #)

-- | Allocate a zero-initialized raw Java int array.
foreign import prim "thc_interop_v1_new_java_int_array"
  newJavaIntArray# :: Int# -> State# s -> (# State# s, JavaIntArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_int_array_length"
  javaIntArrayLength# :: JavaIntArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_int_array"
  readJavaIntArray# :: JavaIntArray# s -> Int# -> State# s -> (# State# s, Int32# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_int_array"
  writeJavaIntArray# :: JavaIntArray# s -> Int# -> Int32# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_int_array"
  copyJavaIntArray# :: JavaIntArray# s -> Int# -> JavaIntArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java int array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_int_array"
  objectAsJavaIntArray# :: Object# s -> State# s -> (# State# s, JavaIntArray# s #)

-- | Box a Java long scalar for interop.
foreign import prim "thc_interop_v1_box_java_long"
  boxJavaLong# :: Int64# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java long, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_long"
  unboxJavaLong# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int64# #)

-- | Allocate a zero-initialized raw Java long array.
foreign import prim "thc_interop_v1_new_java_long_array"
  newJavaLongArray# :: Int# -> State# s -> (# State# s, JavaLongArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_long_array_length"
  javaLongArrayLength# :: JavaLongArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_long_array"
  readJavaLongArray# :: JavaLongArray# s -> Int# -> State# s -> (# State# s, Int64# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_long_array"
  writeJavaLongArray# :: JavaLongArray# s -> Int# -> Int64# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_long_array"
  copyJavaLongArray# :: JavaLongArray# s -> Int# -> JavaLongArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java long array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_long_array"
  objectAsJavaLongArray# :: Object# s -> State# s -> (# State# s, JavaLongArray# s #)

-- | Box a Java float scalar for interop.
foreign import prim "thc_interop_v1_box_java_float"
  boxJavaFloat# :: Float# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java float, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_float"
  unboxJavaFloat# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Float# #)

-- | Allocate a zero-initialized raw Java float array.
foreign import prim "thc_interop_v1_new_java_float_array"
  newJavaFloatArray# :: Int# -> State# s -> (# State# s, JavaFloatArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_float_array_length"
  javaFloatArrayLength# :: JavaFloatArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_float_array"
  readJavaFloatArray# :: JavaFloatArray# s -> Int# -> State# s -> (# State# s, Float# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_float_array"
  writeJavaFloatArray# :: JavaFloatArray# s -> Int# -> Float# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_float_array"
  copyJavaFloatArray# :: JavaFloatArray# s -> Int# -> JavaFloatArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java float array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_float_array"
  objectAsJavaFloatArray# :: Object# s -> State# s -> (# State# s, JavaFloatArray# s #)

-- | Box a Java double scalar for interop.
foreign import prim "thc_interop_v1_box_java_double"
  boxJavaDouble# :: Double# -> State# s -> (# State# s, Object# s #)

-- | Convert exactly to a Java double, raising a foreign exception on an incompatible value.
foreign import prim "thc_interop_v1_unbox_java_double"
  unboxJavaDouble# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Double# #)

-- | Allocate a zero-initialized raw Java double array.
foreign import prim "thc_interop_v1_new_java_double_array"
  newJavaDoubleArray# :: Int# -> State# s -> (# State# s, JavaDoubleArray# s #)

-- | Array length in elements.
foreign import prim "thc_interop_v1_java_double_array_length"
  javaDoubleArrayLength# :: JavaDoubleArray# s -> State# s -> (# State# s, Int# #)

-- | Read one element at a zero-based index.
foreign import prim "thc_interop_v1_read_java_double_array"
  readJavaDoubleArray# :: JavaDoubleArray# s -> Int# -> State# s -> (# State# s, Double# #)

-- | Write one element at a zero-based index.
foreign import prim "thc_interop_v1_write_java_double_array"
  writeJavaDoubleArray# :: JavaDoubleArray# s -> Int# -> Double# -> State# s -> State# s

-- | Copy elements with System.arraycopy overlap semantics.
foreign import prim "thc_interop_v1_copy_java_double_array"
  copyJavaDoubleArray# :: JavaDoubleArray# s -> Int# -> JavaDoubleArray# s -> Int# -> Int# -> State# s -> State# s

-- | Check that a raw Object# is a Java double array. Use asHostObject# first for an interop host wrapper.
foreign import prim "thc_interop_v1_object_as_java_double_array"
  objectAsJavaDoubleArray# :: Object# s -> State# s -> (# State# s, JavaDoubleArray# s #)

-- | Vector array offsets and gather/scatter index maps count elements, not bytes.
-- Masked-off lanes follow the JDK API: zero on load and untouched on store.
-- Boolean arrays use ByteVector and char arrays use ShortVector bit patterns.

foreign import prim "thc_vector_v1_read_java_boolean_vector"
  readJavaBooleanVector# :: VecSpecies# Int8# -> JavaBooleanArray# s -> Int# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_boolean_vector"
  writeJavaBooleanVector# :: JavaBooleanArray# s -> Int# -> Vec# Int8# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_boolean_vector_masked"
  readJavaBooleanVectorMasked# :: VecSpecies# Int8# -> JavaBooleanArray# s -> Int# -> VecMask# Int8# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_boolean_vector_masked"
  writeJavaBooleanVectorMasked# :: JavaBooleanArray# s -> Int# -> Vec# Int8# -> VecMask# Int8# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_boolean_vector_indexed"
  readJavaBooleanVectorIndexed# :: VecSpecies# Int8# -> JavaBooleanArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_boolean_vector_indexed"
  writeJavaBooleanVectorIndexed# :: JavaBooleanArray# s -> Int# -> Vec# Int8# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_boolean_vector_indexed_masked"
  readJavaBooleanVectorIndexedMasked# :: VecSpecies# Int8# -> JavaBooleanArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Int8# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_boolean_vector_indexed_masked"
  writeJavaBooleanVectorIndexedMasked# :: JavaBooleanArray# s -> Int# -> Vec# Int8# -> JavaIntArray# s -> Int# -> VecMask# Int8# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_byte_vector"
  readJavaByteVector# :: VecSpecies# Int8# -> JavaByteArray# s -> Int# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_byte_vector"
  writeJavaByteVector# :: JavaByteArray# s -> Int# -> Vec# Int8# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_byte_vector_masked"
  readJavaByteVectorMasked# :: VecSpecies# Int8# -> JavaByteArray# s -> Int# -> VecMask# Int8# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_byte_vector_masked"
  writeJavaByteVectorMasked# :: JavaByteArray# s -> Int# -> Vec# Int8# -> VecMask# Int8# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_byte_vector_indexed"
  readJavaByteVectorIndexed# :: VecSpecies# Int8# -> JavaByteArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_byte_vector_indexed"
  writeJavaByteVectorIndexed# :: JavaByteArray# s -> Int# -> Vec# Int8# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_byte_vector_indexed_masked"
  readJavaByteVectorIndexedMasked# :: VecSpecies# Int8# -> JavaByteArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Int8# -> State# s -> (# State# s, Vec# Int8# #)

foreign import prim "thc_vector_v1_write_java_byte_vector_indexed_masked"
  writeJavaByteVectorIndexedMasked# :: JavaByteArray# s -> Int# -> Vec# Int8# -> JavaIntArray# s -> Int# -> VecMask# Int8# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_short_vector"
  readJavaShortVector# :: VecSpecies# Int16# -> JavaShortArray# s -> Int# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_short_vector"
  writeJavaShortVector# :: JavaShortArray# s -> Int# -> Vec# Int16# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_short_vector_masked"
  readJavaShortVectorMasked# :: VecSpecies# Int16# -> JavaShortArray# s -> Int# -> VecMask# Int16# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_short_vector_masked"
  writeJavaShortVectorMasked# :: JavaShortArray# s -> Int# -> Vec# Int16# -> VecMask# Int16# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_short_vector_indexed"
  readJavaShortVectorIndexed# :: VecSpecies# Int16# -> JavaShortArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_short_vector_indexed"
  writeJavaShortVectorIndexed# :: JavaShortArray# s -> Int# -> Vec# Int16# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_short_vector_indexed_masked"
  readJavaShortVectorIndexedMasked# :: VecSpecies# Int16# -> JavaShortArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Int16# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_short_vector_indexed_masked"
  writeJavaShortVectorIndexedMasked# :: JavaShortArray# s -> Int# -> Vec# Int16# -> JavaIntArray# s -> Int# -> VecMask# Int16# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_char_vector"
  readJavaCharVector# :: VecSpecies# Int16# -> JavaCharArray# s -> Int# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_char_vector"
  writeJavaCharVector# :: JavaCharArray# s -> Int# -> Vec# Int16# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_char_vector_masked"
  readJavaCharVectorMasked# :: VecSpecies# Int16# -> JavaCharArray# s -> Int# -> VecMask# Int16# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_char_vector_masked"
  writeJavaCharVectorMasked# :: JavaCharArray# s -> Int# -> Vec# Int16# -> VecMask# Int16# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_char_vector_indexed"
  readJavaCharVectorIndexed# :: VecSpecies# Int16# -> JavaCharArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_char_vector_indexed"
  writeJavaCharVectorIndexed# :: JavaCharArray# s -> Int# -> Vec# Int16# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_char_vector_indexed_masked"
  readJavaCharVectorIndexedMasked# :: VecSpecies# Int16# -> JavaCharArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Int16# -> State# s -> (# State# s, Vec# Int16# #)

foreign import prim "thc_vector_v1_write_java_char_vector_indexed_masked"
  writeJavaCharVectorIndexedMasked# :: JavaCharArray# s -> Int# -> Vec# Int16# -> JavaIntArray# s -> Int# -> VecMask# Int16# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_int_vector"
  readJavaIntVector# :: VecSpecies# Int32# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int32# #)

foreign import prim "thc_vector_v1_write_java_int_vector"
  writeJavaIntVector# :: JavaIntArray# s -> Int# -> Vec# Int32# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_int_vector_masked"
  readJavaIntVectorMasked# :: VecSpecies# Int32# -> JavaIntArray# s -> Int# -> VecMask# Int32# -> State# s -> (# State# s, Vec# Int32# #)

foreign import prim "thc_vector_v1_write_java_int_vector_masked"
  writeJavaIntVectorMasked# :: JavaIntArray# s -> Int# -> Vec# Int32# -> VecMask# Int32# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_int_vector_indexed"
  readJavaIntVectorIndexed# :: VecSpecies# Int32# -> JavaIntArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int32# #)

foreign import prim "thc_vector_v1_write_java_int_vector_indexed"
  writeJavaIntVectorIndexed# :: JavaIntArray# s -> Int# -> Vec# Int32# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_int_vector_indexed_masked"
  readJavaIntVectorIndexedMasked# :: VecSpecies# Int32# -> JavaIntArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Int32# -> State# s -> (# State# s, Vec# Int32# #)

foreign import prim "thc_vector_v1_write_java_int_vector_indexed_masked"
  writeJavaIntVectorIndexedMasked# :: JavaIntArray# s -> Int# -> Vec# Int32# -> JavaIntArray# s -> Int# -> VecMask# Int32# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_long_vector"
  readJavaLongVector# :: VecSpecies# Int64# -> JavaLongArray# s -> Int# -> State# s -> (# State# s, Vec# Int64# #)

foreign import prim "thc_vector_v1_write_java_long_vector"
  writeJavaLongVector# :: JavaLongArray# s -> Int# -> Vec# Int64# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_long_vector_masked"
  readJavaLongVectorMasked# :: VecSpecies# Int64# -> JavaLongArray# s -> Int# -> VecMask# Int64# -> State# s -> (# State# s, Vec# Int64# #)

foreign import prim "thc_vector_v1_write_java_long_vector_masked"
  writeJavaLongVectorMasked# :: JavaLongArray# s -> Int# -> Vec# Int64# -> VecMask# Int64# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_long_vector_indexed"
  readJavaLongVectorIndexed# :: VecSpecies# Int64# -> JavaLongArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Int64# #)

foreign import prim "thc_vector_v1_write_java_long_vector_indexed"
  writeJavaLongVectorIndexed# :: JavaLongArray# s -> Int# -> Vec# Int64# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_long_vector_indexed_masked"
  readJavaLongVectorIndexedMasked# :: VecSpecies# Int64# -> JavaLongArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Int64# -> State# s -> (# State# s, Vec# Int64# #)

foreign import prim "thc_vector_v1_write_java_long_vector_indexed_masked"
  writeJavaLongVectorIndexedMasked# :: JavaLongArray# s -> Int# -> Vec# Int64# -> JavaIntArray# s -> Int# -> VecMask# Int64# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_float_vector"
  readJavaFloatVector# :: VecSpecies# Float# -> JavaFloatArray# s -> Int# -> State# s -> (# State# s, Vec# Float# #)

foreign import prim "thc_vector_v1_write_java_float_vector"
  writeJavaFloatVector# :: JavaFloatArray# s -> Int# -> Vec# Float# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_float_vector_masked"
  readJavaFloatVectorMasked# :: VecSpecies# Float# -> JavaFloatArray# s -> Int# -> VecMask# Float# -> State# s -> (# State# s, Vec# Float# #)

foreign import prim "thc_vector_v1_write_java_float_vector_masked"
  writeJavaFloatVectorMasked# :: JavaFloatArray# s -> Int# -> Vec# Float# -> VecMask# Float# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_float_vector_indexed"
  readJavaFloatVectorIndexed# :: VecSpecies# Float# -> JavaFloatArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Float# #)

foreign import prim "thc_vector_v1_write_java_float_vector_indexed"
  writeJavaFloatVectorIndexed# :: JavaFloatArray# s -> Int# -> Vec# Float# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_float_vector_indexed_masked"
  readJavaFloatVectorIndexedMasked# :: VecSpecies# Float# -> JavaFloatArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Float# -> State# s -> (# State# s, Vec# Float# #)

foreign import prim "thc_vector_v1_write_java_float_vector_indexed_masked"
  writeJavaFloatVectorIndexedMasked# :: JavaFloatArray# s -> Int# -> Vec# Float# -> JavaIntArray# s -> Int# -> VecMask# Float# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_double_vector"
  readJavaDoubleVector# :: VecSpecies# Double# -> JavaDoubleArray# s -> Int# -> State# s -> (# State# s, Vec# Double# #)

foreign import prim "thc_vector_v1_write_java_double_vector"
  writeJavaDoubleVector# :: JavaDoubleArray# s -> Int# -> Vec# Double# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_double_vector_masked"
  readJavaDoubleVectorMasked# :: VecSpecies# Double# -> JavaDoubleArray# s -> Int# -> VecMask# Double# -> State# s -> (# State# s, Vec# Double# #)

foreign import prim "thc_vector_v1_write_java_double_vector_masked"
  writeJavaDoubleVectorMasked# :: JavaDoubleArray# s -> Int# -> Vec# Double# -> VecMask# Double# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_double_vector_indexed"
  readJavaDoubleVectorIndexed# :: VecSpecies# Double# -> JavaDoubleArray# s -> Int# -> JavaIntArray# s -> Int# -> State# s -> (# State# s, Vec# Double# #)

foreign import prim "thc_vector_v1_write_java_double_vector_indexed"
  writeJavaDoubleVectorIndexed# :: JavaDoubleArray# s -> Int# -> Vec# Double# -> JavaIntArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_double_vector_indexed_masked"
  readJavaDoubleVectorIndexedMasked# :: VecSpecies# Double# -> JavaDoubleArray# s -> Int# -> JavaIntArray# s -> Int# -> VecMask# Double# -> State# s -> (# State# s, Vec# Double# #)

foreign import prim "thc_vector_v1_write_java_double_vector_indexed_masked"
  writeJavaDoubleVectorIndexedMasked# :: JavaDoubleArray# s -> Int# -> Vec# Double# -> JavaIntArray# s -> Int# -> VecMask# Double# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_mask"
  readJavaMask# :: forall r (e :: TYPE r) s. VecSpecies# e -> JavaBooleanArray# s -> Int# -> State# s -> (# State# s, VecMask# e #)

foreign import prim "thc_vector_v1_write_java_mask"
  writeJavaMask# :: forall r (e :: TYPE r) s. VecMask# e -> JavaBooleanArray# s -> Int# -> State# s -> State# s

foreign import prim "thc_vector_v1_read_java_shuffle"
  readJavaShuffle# :: forall r (e :: TYPE r) s. VecSpecies# e -> JavaIntArray# s -> Int# -> State# s -> (# State# s, VecShuffle# e #)

foreign import prim "thc_vector_v1_write_java_shuffle"
  writeJavaShuffle# :: forall r (e :: TYPE r) s. VecShuffle# e -> JavaIntArray# s -> Int# -> State# s -> State# s
