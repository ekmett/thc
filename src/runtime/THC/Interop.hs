-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, RoleAnnotations, UnboxedTuples, Trustworthy #-}

-- |
-- Module      : THC.Interop
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC runtime
--
-- A reusable receiver and its explicitly acquired Truffle dispatcher. Every
-- access invokes the foreign protocol; no collection is copied. Array elements
-- are heterogeneous opaque objects, not a promised Haskell element type.
-- Use 'Control.Monad.ST.stToIO' for a handle in 'RealWorld'. This interface does
-- not make a foreign receiver safe for concurrent use.
module THC.Interop
  ( Interop, fromObject#, fromValue
  , hasBufferElements, isBufferWritable, getBufferSize, readBufferByte, writeBufferByte
  , hasArrayElements, getArraySize, readArrayElement, writeArrayElement, asInt64
  ) where

import GHC.Exts (Int(I#), RealWorld, isTrue#, (/=#))
import GHC.Int (Int8(I8#), Int64(I64#))
import GHC.IO (IO(..))
import GHC.ST (ST(..))
import THC.Internal.Polyglot (Value(..))
import THC.Prim

-- | The fields are unlifted references; the state index cannot be coerced.
type role Interop nominal
data Interop s = Interop (Object# s) (InteropLibrary# s)

-- | Acquire at this operation site and retain the actual dispatcher for reuse.
fromObject# :: Object# s -> Interop s
fromObject# object = Interop object (getInteropLibrary# object)

-- | Import an existing context-owned foreign handle into the external region.
fromValue :: Value -> IO (Interop RealWorld)
fromValue (Value value) = IO $ \state -> case importPolyglotValue# value state of
  (# next, object #) -> (# next, fromObject# object #)

hasBufferElements :: Interop s -> ST s Bool
hasBufferElements (Interop object library) = ST $ \state ->
  case hasBufferElements# object library state of (# next, answer #) -> (# next, isTrue# (answer /=# 0#) #)
isBufferWritable :: Interop s -> ST s Bool
isBufferWritable (Interop object library) = ST $ \state ->
  case isBufferWritable# object library state of (# next, answer #) -> (# next, isTrue# (answer /=# 0#) #)
getBufferSize :: Interop s -> ST s Int
getBufferSize (Interop object library) = ST $ \state ->
  case getBufferSize# object library state of (# next, answer #) -> (# next, I# answer #)
-- | Read a signed byte at a byte offset. Unsupported access or invalid offsets
-- raise a catchable foreign exception.
readBufferByte :: Interop s -> Int -> ST s Int8
readBufferByte (Interop object library) (I# offset) = ST $ \state ->
  case readBufferByte# object library offset state of (# next, answer #) -> (# next, I8# answer #)
-- | Write a signed byte at a byte offset, if the receiver permits writes.
writeBufferByte :: Interop s -> Int -> Int8 -> ST s ()
writeBufferByte (Interop object library) (I# offset) (I8# value) = ST $ \state ->
  case writeBufferByte# object library offset value state of next -> (# next, () #)
hasArrayElements :: Interop s -> ST s Bool
hasArrayElements (Interop object library) = ST $ \state ->
  case hasArrayElements# object library state of (# next, answer #) -> (# next, isTrue# (answer /=# 0#) #)
getArraySize :: Interop s -> ST s Int
getArraySize (Interop object library) = ST $ \state ->
  case getArraySize# object library state of (# next, answer #) -> (# next, I# answer #)
-- | Read at an element index and acquire a dispatcher for the returned object.
-- No homogeneous element type is assumed and no collection is copied.
readArrayElement :: Interop s -> Int -> ST s (Interop s)
readArrayElement (Interop object library) (I# offset) = ST $ \state ->
  case readArrayElement# object library offset state of (# next, answer #) -> (# next, fromObject# answer #)
-- | Replace the object at an element index, if the receiver permits it.
writeArrayElement :: Interop s -> Int -> Interop s -> ST s ()
writeArrayElement (Interop object library) (I# offset) (Interop value _) = ST $ \state ->
  case writeArrayElement# object library offset value state of next -> (# next, () #)
-- | Convert exactly to a signed 64-bit integer, or raise a foreign exception.
asInt64 :: Interop s -> ST s Int64
asInt64 (Interop object library) = ST $ \state ->
  case asLong# object library state of (# next, answer #) -> (# next, I64# answer #)
