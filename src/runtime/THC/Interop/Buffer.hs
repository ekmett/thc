-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, Trustworthy #-}

-- |
-- Module      : THC.Interop.Buffer
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC polyglot runtime
--
-- Byte-buffer interop. Views alias and retain the original allocation; copies
-- allocate ordinary guest storage. A foreign read-only view is not immutable.
-- Mutable views may escape: do not unsafeFreeze their storage while a foreign
-- consumer can write. These operations never manufacture native pointers.
module THC.Interop.Buffer
  ( ByteArray(..), ByteOrder(..), view, mutableView, size, readByte, writeByte
  , readInt64, writeInt64, copy, copySlice, copyInto
  ) where

import GHC.Exts (ByteArray#, MutableByteArray#, RealWorld, Int#, Int(..))
import GHC.IO (IO(..))
import THC.Internal.Polyglot

-- | Lifted owner for a copied immutable 'ByteArray#'.
data ByteArray = ByteArray ByteArray#
-- | Explicit byte order, independent of host and foreign storage order.
data ByteOrder = LittleEndian | BigEndian

-- | Read-only, zero-copy view. Shrinking owned storage changes the view size.
view :: ByteArray# -> IO Value
view bytes = IO $ \state -> case bufferView# bytes state of
  (# next, raw #) -> (# next, Value raw #)

-- | Writable zero-copy view of RealWorld storage. Pointers stored in a managed
-- byte allocation exclude raw byte export; later pointer stores are rejected.
mutableView :: MutableByteArray# RealWorld -> IO Value
mutableView bytes = IO $ \state -> case mutableBufferView# bytes state of
  (# next, raw #) -> (# next, Value raw #)

-- | Current byte length. Foreign storage need not be native or pinned.
size :: Value -> IO Int
size (Value buffer) = IO $ \state -> case bufferSize# buffer state of
  (# next, count #) -> (# next, I# count #)

-- | Read one byte, as an integer in 0..255.
readByte :: Value -> Int -> IO Int
readByte (Value buffer) (I# offset) = IO $ \state -> case bufferReadByte# buffer offset state of
  (# next, byte #) -> (# next, I# byte #)

-- | Write one byte. Reject values outside 0..255 and read-only receivers.
writeByte :: Value -> Int -> Int -> IO ()
writeByte (Value buffer) (I# offset) (I# byte) = IO $ \state -> case bufferWriteByte# buffer offset byte state of
  (# next, _ #) -> (# next, () #)

order :: ByteOrder -> Int#
order LittleEndian = 0#
order BigEndian = 1#

-- | Read eight bytes at a byte offset. THC's machine Int is 64 bits.
readInt64 :: ByteOrder -> Value -> Int -> IO Int
readInt64 endian (Value buffer) (I# offset) = IO $ \state -> case bufferReadLong# buffer (order endian) offset state of
  (# next, value #) -> (# next, I# value #)

-- | Write eight bytes at a byte offset.
writeInt64 :: ByteOrder -> Value -> Int -> Int -> IO ()
writeInt64 endian (Value buffer) (I# offset) (I# value) = IO $ \state -> case bufferWriteLong# buffer (order endian) offset value state of
  (# next, _ #) -> (# next, () #)

-- | Copy the whole current foreign buffer into a fresh immutable ByteArray#.
-- The read uses the foreign bulk-buffer protocol, not pointer conversion.
copy :: Value -> IO ByteArray
copy buffer = size buffer >>= copySlice buffer 0

-- | Copy an offset/count region. Empty regions at the end are valid.
copySlice :: Value -> Int -> Int -> IO ByteArray
copySlice (Value buffer) (I# offset) (I# count) = IO $ \state -> case bufferCopy# buffer offset count state of
  (# next, bytes #) -> (# next, ByteArray bytes #)

-- | Copy source offset, mutable destination, destination offset, and byte count.
-- Reads complete into a temporary snapshot before destination mutation, so
-- overlapping aliases are safe. Foreign reads can execute arbitrary code.
copyInto :: Value -> Int -> MutableByteArray# RealWorld -> Int -> Int -> IO ()
copyInto (Value source) (I# sourceOffset) destination (I# destinationOffset) (I# count) =
  IO $ \state -> case bufferCopyInto# source sourceOffset destination destinationOffset count state of
    (# next, _ #) -> (# next, () #)
