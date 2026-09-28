-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GHCForeignImportPrim, MagicHash, UnboxedTuples, UnliftedFFITypes, Unsafe #-}

-- |
-- Module      : THC.Internal.Polyglot
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC polyglot runtime
--
-- Raw versioned intrinsics. Arbitrary Any values do not constitute foreign
-- handles; mutable views must not be manufactured from immutable storage.
module THC.Internal.Polyglot
  ( Value(..), eval#, readMember#, executeInt#, executeValue#
  , bufferView#, mutableBufferView#, bufferSize#, bufferReadByte#, bufferWriteByte#
  , bufferReadLong#, bufferWriteLong#, bufferCopy#, bufferCopyInto#
  , arrayView#, mutableArrayView#, smallArrayView#, mutableSmallArrayView#
  , arraySize#, arrayRead#, arrayWrite#, arrayCopy#
  ) where

import GHC.Exts

-- | Strong context-owned foreign reference, never a native address.
newtype Value = Value Any

foreign import prim "thc_polyglot_v1_eval"
  eval# :: Addr# -> Addr# -> Addr# -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_read_member"
  readMember# :: Any -> Addr# -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_execute_int"
  executeInt# :: Any -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_execute_value"
  executeValue# :: Any -> Any -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_buffer_view"
  bufferView# :: ByteArray# -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_buffer_mutable_view"
  mutableBufferView# :: MutableByteArray# RealWorld -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_buffer_size"
  bufferSize# :: Any -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_buffer_read_byte"
  bufferReadByte# :: Any -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_buffer_write_byte"
  bufferWriteByte# :: Any -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_buffer_read_long"
  bufferReadLong# :: Any -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_buffer_write_long"
  bufferWriteLong# :: Any -> Int# -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_buffer_copy"
  bufferCopy# :: Any -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, ByteArray# #)
foreign import prim "thc_polyglot_v1_buffer_copy_into"
  bufferCopyInto# :: Any -> Int# -> MutableByteArray# RealWorld -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_array_view"
  arrayView# :: Array# a -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_array_mutable_view"
  mutableArrayView# :: MutableArray# RealWorld a -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_array_view"
  smallArrayView# :: SmallArray# a -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_array_mutable_view"
  mutableSmallArrayView# :: SmallMutableArray# RealWorld a -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_array_size"
  arraySize# :: Any -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_array_read"
  arrayRead# :: Any -> Int# -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_array_write"
  arrayWrite# :: Any -> Int# -> Any -> State# RealWorld -> (# State# RealWorld, Int# #)
foreign import prim "thc_polyglot_v1_array_copy"
  arrayCopy# :: Any -> State# RealWorld -> (# State# RealWorld, Array# Value #)
