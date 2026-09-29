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
  , isString#, asTruffleString#
  , TruffleString#, TruffleStringEncoding#, truffleStringAsObject#
  , truffleStringEncoding#
  , truffleStringFromByteArray#
  , truffleStringToByteArray#
  , truffleStringFromCodePoint#
  , truffleStringFromInt64#
  , truffleStringByteLength#
  , truffleStringCodePointLength#
  , truffleStringIsValid#
  , truffleStringReadByte#
  , truffleStringCodePointAt#
  , truffleStringCodePointAtByte#
  , truffleStringCodePointByteLength#
  , truffleStringByteToCodePointIndex#
  , truffleStringCodePointToByteIndex#
  , truffleStringEqual#
  , truffleStringCompareBytes#
  , truffleStringHash#
  , truffleStringIndexOfCodePoint#
  , truffleStringByteIndexOfCodePoint#
  , truffleStringIndexOfString#
  , truffleStringByteIndexOfString#
  , truffleStringSubstring#
  , truffleStringSubstringBytes#
  , truffleStringConcat#
  , truffleStringRepeat#
  , truffleStringSwitchEncoding#
  , truffleStringParseInt64#
  , truffleStringParseDouble#
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

-- | Raw immutable Truffle carriers; neither type allocates a Haskell wrapper.
type TruffleString# :: UnliftedType
newtype TruffleString# = TruffleString# (Any :: UnliftedType)
type TruffleStringEncoding# :: UnliftedType
newtype TruffleStringEncoding# = TruffleStringEncoding# (Any :: UnliftedType)

-- | Erased reference conversion for other raw interop primitives.
-- Immutable strings carry no mutable context-owned storage.
truffleStringAsObject# :: TruffleString# -> Object# s
truffleStringAsObject# (TruffleString# value) = Object# value
{-# INLINE truffleStringAsObject# #-}

-- | Explicit encoding codes: 0 UTF-8, 1 native UTF-16, 2 native UTF-32,
-- 3 ISO-8859-1, 4 US-ASCII, 5 BYTES, 6 UTF-16LE, 7 UTF-16BE,
-- 8 UTF-32LE, 9 UTF-32BE. Unknown codes are errors, not enum ordinals.
-- Byte-array creation always copies; returned byte arrays are fresh.
-- CodePoint access and per-code-point byte-length queries return -1 on malformed input.
-- Index conversions take a byte offset and a relative index. Substring/search
-- units follow their names; search end offsets are exclusive.
-- SwitchEncoding uses Truffle's default replacement policy for invalid input.
-- Parse failures and invalid ranges are primitive errors. See docs/prim-strings.md.
foreign import prim "thc_string_v1_encoding"
  truffleStringEncoding# :: Int# -> TruffleStringEncoding#

foreign import prim "thc_string_v1_from_bytes"
  truffleStringFromByteArray# :: TruffleStringEncoding# -> ByteArray# -> Int# -> Int# -> TruffleString#

foreign import prim "thc_string_v1_to_bytes"
  truffleStringToByteArray# :: TruffleStringEncoding# -> TruffleString# -> ByteArray#

foreign import prim "thc_string_v1_from_code_point"
  truffleStringFromCodePoint# :: TruffleStringEncoding# -> Int# -> TruffleString#

foreign import prim "thc_string_v1_from_int64"
  truffleStringFromInt64# :: TruffleStringEncoding# -> Int64# -> TruffleString#

foreign import prim "thc_string_v1_byte_length"
  truffleStringByteLength# :: TruffleStringEncoding# -> TruffleString# -> Int#

foreign import prim "thc_string_v1_code_point_length"
  truffleStringCodePointLength# :: TruffleStringEncoding# -> TruffleString# -> Int#

foreign import prim "thc_string_v1_is_valid"
  truffleStringIsValid# :: TruffleStringEncoding# -> TruffleString# -> Int#

foreign import prim "thc_string_v1_read_byte"
  truffleStringReadByte# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int#

foreign import prim "thc_string_v1_code_point_at"
  truffleStringCodePointAt# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int#

foreign import prim "thc_string_v1_code_point_at_byte"
  truffleStringCodePointAtByte# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int#

foreign import prim "thc_string_v1_code_point_byte_length"
  truffleStringCodePointByteLength# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int#

foreign import prim "thc_string_v1_byte_to_code_point"
  truffleStringByteToCodePointIndex# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int# -> Int#

foreign import prim "thc_string_v1_code_point_to_byte"
  truffleStringCodePointToByteIndex# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int# -> Int#

foreign import prim "thc_string_v1_equal"
  truffleStringEqual# :: TruffleStringEncoding# -> TruffleString# -> TruffleString# -> Int#

foreign import prim "thc_string_v1_compare_bytes"
  truffleStringCompareBytes# :: TruffleStringEncoding# -> TruffleString# -> TruffleString# -> Int#

foreign import prim "thc_string_v1_hash"
  truffleStringHash# :: TruffleStringEncoding# -> TruffleString# -> Int#

foreign import prim "thc_string_v1_index_of_code_point"
  truffleStringIndexOfCodePoint# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int# -> Int# -> Int#

foreign import prim "thc_string_v1_byte_index_of_code_point"
  truffleStringByteIndexOfCodePoint# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int# -> Int# -> Int#

foreign import prim "thc_string_v1_index_of_string"
  truffleStringIndexOfString# :: TruffleStringEncoding# -> TruffleString# -> TruffleString# -> Int# -> Int# -> Int#

foreign import prim "thc_string_v1_byte_index_of_string"
  truffleStringByteIndexOfString# :: TruffleStringEncoding# -> TruffleString# -> TruffleString# -> Int# -> Int# -> Int#

foreign import prim "thc_string_v1_substring"
  truffleStringSubstring# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int# -> TruffleString#

foreign import prim "thc_string_v1_substring_bytes"
  truffleStringSubstringBytes# :: TruffleStringEncoding# -> TruffleString# -> Int# -> Int# -> TruffleString#

foreign import prim "thc_string_v1_concat"
  truffleStringConcat# :: TruffleStringEncoding# -> TruffleString# -> TruffleString# -> TruffleString#

foreign import prim "thc_string_v1_repeat"
  truffleStringRepeat# :: TruffleStringEncoding# -> TruffleString# -> Int# -> TruffleString#

foreign import prim "thc_string_v1_switch_encoding"
  truffleStringSwitchEncoding# :: TruffleStringEncoding# -> TruffleString# -> TruffleString#

foreign import prim "thc_string_v1_parse_int64"
  truffleStringParseInt64# :: TruffleString# -> Int# -> Int64#

foreign import prim "thc_string_v1_parse_double"
  truffleStringParseDouble# :: TruffleString# -> Double#

foreign import prim "thc_interop_v1_is_string"
  isString# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, Int# #)
foreign import prim "thc_interop_v1_as_truffle_string"
  asTruffleString# :: Object# s -> InteropLibrary# s -> State# s -> (# State# s, TruffleString# #)

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
