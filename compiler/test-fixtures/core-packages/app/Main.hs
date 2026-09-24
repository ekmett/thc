-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
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
