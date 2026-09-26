-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), nullAddr#)
import Mixed
import Unknown
import Unresolved
import CapiMix (mixed)
import Control.Monad (forM)
main :: IO ()
main = do
  print (I# (allowed 3#))
  print (I# (blocked nullAddr#))
  print (I# (count 0#))
  print (I# (other 3#))
  print (I# (process 0#) > 0)
  observations <- forM [(rounds,keylen) | rounds <- [-1,0,255,256,4294967298],
    keylen <- [-1,0,4294967297,2147483648]] $ \(rounds,keylen) -> do
      (typed,wide) <- mixed rounds keylen
      pure (rounds,keylen,typed,wide)
  print observations
