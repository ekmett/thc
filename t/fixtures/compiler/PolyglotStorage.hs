-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module PolyglotStorage (copySlice#, view#, mutableView#, copyInto#, readByte#) where
import GHC.Exts
import GHC.IO (IO(..))
import qualified THC.Interop.Buffer as Buffer
import THC.Polyglot (Value)
import THC.Internal.Polyglot (Value(..))

-- Real GHC lowering of the public typed IO wrappers, without a native substitute.
copySlice# :: Value -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, ByteArray# #)
copySlice# value offset count state = case Buffer.copySlice value (I# offset) (I# count) of
  IO action -> case action state of
    (# next, Buffer.ByteArray bytes #) -> (# next, bytes #)
{-# OPAQUE copySlice# #-}

view# :: ByteArray# -> State# RealWorld -> (# State# RealWorld, Any #)
view# bytes state = case Buffer.view bytes of
  IO action -> case action state of
    (# next, Value value #) -> (# next, value #)
{-# OPAQUE view# #-}

mutableView# :: MutableByteArray# RealWorld -> State# RealWorld -> (# State# RealWorld, Any #)
mutableView# bytes state = case Buffer.mutableView bytes of
  IO action -> case action state of
    (# next, Value value #) -> (# next, value #)
{-# OPAQUE mutableView# #-}

copyInto# :: Value -> Int# -> MutableByteArray# RealWorld -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
copyInto# value sourceOffset bytes destinationOffset count state =
  case Buffer.copyInto value (I# sourceOffset) bytes (I# destinationOffset) (I# count) of
    IO action -> case action state of
      (# next, () #) -> (# next, 0# #)
{-# OPAQUE copyInto# #-}

readByte# :: Value -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
readByte# value offset state = case Buffer.readByte value (I# offset) of
  IO action -> case action state of
    (# next, I# byte #) -> (# next, byte #)
{-# OPAQUE readByte# #-}
