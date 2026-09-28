-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Compiler fixture for main Core and metadata.
module Main where

import Dep
import GHC.Exts (Int#,(+#))
import GHC.Types (Int(I#))

{-# NOINLINE score# #-}
score# :: Int# -> Int#
score# x = case mkPair (I# x) of
  Pair (I# a) (I# b) -> case pairScore (Pair (I# a) (I# b)) of
    I# result -> result +# a

main :: IO ()
main = print (I# (score# 5#))
