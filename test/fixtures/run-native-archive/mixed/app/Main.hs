-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), nullAddr#)
import Mixed
import Unknown
import Unresolved
main :: IO ()
main = do
  print (I# (allowed 3#))
  print (I# (blocked nullAddr#))
  print (I# (count 0#))
  print (I# (other 3#))
  print (I# (process 0#) > 0)
