-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DataKinds, GHCForeignImportPrim, KindSignatures, MagicHash #-}
{-# LANGUAGE RoleAnnotations, StandaloneKindSignatures, UnboxedTuples #-}
{-# LANGUAGE UnliftedFFITypes, UnliftedNewtypes, Unsafe #-}

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
module THC.Prim
  ( Object#, InteropLibrary#, getInteropLibrary#, importPolyglotValue#
  , hasBufferElements#, isBufferWritable#, getBufferSize#
  , readBufferByte#, writeBufferByte#
  , hasArrayElements#, getArraySize#, readArrayElement#, writeArrayElement#
  , asLong#
  ) where

import Data.Kind (Type)
import GHC.Exts

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
