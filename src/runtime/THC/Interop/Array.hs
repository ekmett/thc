-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, Trustworthy #-}

-- |
-- Module      : THC.Interop.Array
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC polyglot runtime
--
-- Fixed Haskell arrays expose indexed, lazy references, never insertion or
-- removal. Foreign collections remain opaque values. Copy imports their elements
-- as Value handles without pretending they are Haskell values of arbitrary type.
-- Mutable Haskell export lives separately in "THC.Interop.Array.Unsafe": its
-- caller must preserve the Haskell element type of foreign replacements.
module THC.Interop.Array
  ( Array(..), view, smallView, size, read, write, copy ) where

import Prelude hiding (read)
import GHC.Exts (Array#, SmallArray#, Int(..))
import GHC.IO (IO(..))
import THC.Internal.Polyglot

-- | Lifted owner for fixed guest element storage.
data Array a = Array (Array# a)

-- | Read-only alias. Reading an element does not force that Haskell element.
view :: Array# a -> IO Value
view array = IO $ \state -> case arrayView# array state of
  (# next, value #) -> (# next, Value value #)

-- | Read-only SmallArray# alias.
smallView :: SmallArray# a -> IO Value
smallView array = IO $ \state -> case smallArrayView# array state of
  (# next, value #) -> (# next, Value value #)

-- | Query the current foreign collection size.
size :: Value -> IO Int
size (Value array) = IO $ \state -> case arraySize# array state of
  (# next, count #) -> (# next, I# count #)

-- | Read only the requested element, retained as an opaque Value.
read :: Value -> Int -> IO Value
read (Value array) (I# index) = IO $ \state -> case arrayRead# array index state of
  (# next, value #) -> (# next, Value value #)

-- | Replace an existing element according to the foreign receiver's protocol.
write :: Value -> Int -> Value -> IO ()
write (Value array) (I# index) (Value value) = IO $ \state -> case arrayWrite# array index value state of
  (# next, _ #) -> (# next, () #)

-- | Copy foreign elements into a fixed Array# Value. No element is forced and
-- each retained foreign value stays owned by the current context.
copy :: Value -> IO (Array Value)
copy (Value array) = IO $ \state -> case arrayCopy# array state of
  (# next, values #) -> (# next, Array values #)
