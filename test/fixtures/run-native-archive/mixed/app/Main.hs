-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Double(D#), nullAddr#)
import Mixed
import Unknown
import Unresolved
import Poisoned (poisoned)
import qualified Provider
import CapiMix (mixed)
import Lifecycle (lifecycleProbe#)
import Control.Monad (forM)
main :: IO ()
main = do
  print (I# (allowed 3#))
  print (I# (blocked nullAddr#))
  print (I# (count 0#))
  print (I# (other 3#))
  print (I# (process 0#) > 0)
  stdio >>= print
  print (I# (lifecycleProbe# 5#))
  observations <- forM [(rounds,keylen) | rounds <- [-1,0,255,256,32767,32768,65535,65536,4294967298],
    keylen <- [-1,0,4294967297,2147483648]] $ \(rounds,keylen) -> do
      (typed,wide,word16,staticPointer) <- mixed rounds keylen
      pure (rounds,keylen,typed,wide,word16,staticPointer)
  print observations
  print (I# (partialProbe# 3#))
  print (I# (partialProbe# 5#))
  print (I# (partialProbe# (-2#)))
  print (I# (partialProbe# 1#))
  print (I# (partialProbe# 4#))
  print (I# (throughGlobal 0#) > 0 && I# (poisoned 0#) > 0)
  let result = D# (Provider.nativeMath 1.0##)
  print (result > 0 && result < 1 && I# (Provider.process 0#) > 0)
