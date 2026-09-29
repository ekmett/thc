-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : FunctionFieldsWorkload
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC primitive integers
--
-- Strict and lazy ordinary function fields for code-cache tests.
module FunctionFieldsWorkload (calculate) where

import GHC.Exts (Int#, (+#), (-#))

data Words = Nil | Cons Int# Words
data Box = Box !(Int# -> Int#) (Int# -> Int#) Words

{-# OPAQUE descending #-}
descending :: Int# -> Words
descending n = case n of
  0# -> Nil
  _ -> Cons n (descending (n -# 1#))

{-# OPAQUE shift #-}
shift :: Int# -> Int# -> Int#
shift offset x = x +# offset

{-# OPAQUE unused #-}
unused :: Words
unused = unused

{-# OPAQUE makeBox #-}
makeBox :: Int# -> Box
makeBox offset = Box (shift offset) (shift 1#) unused

{-# OPAQUE foldWith #-}
foldWith :: (Int# -> Int#) -> Words -> Int# -> Int#
foldWith f xs acc = case xs of
  Nil -> acc
  Cons n rest -> foldWith f rest (acc +# f n)

{-# OPAQUE shared #-}
shared :: Words
shared = descending 3#

{-# OPAQUE foldBox #-}
foldBox :: Box -> Words -> Int# -> Int#
foldBox box xs seed = case box of
  Box strict lazy _ -> foldWith strict xs (foldWith lazy shared seed)

-- | Fold strict and lazy function fields while leaving a bottom-valued neighbour
-- untouched. The count must be nonnegative; arithmetic uses machine words.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int# -> Int#
calculate count offset seed = foldBox (makeBox offset) (descending count) seed
