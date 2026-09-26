-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import GHC.Clock (getMonotonicTimeNSec)
import GHC.Exts (Int(I#))
import qualified ThreadScheduling as S

main :: IO ()
main = do
  -- Observe the empty pool before issuing any speculative hints.
  values <- mapM evaluate [I# (S.emptySpark 0#), I# (S.lazyPar 0#), I# (S.lazySpark 0#), I# (S.sparkValue 0#),
    I# (S.currentCounter 4096#), I# (S.negativeCounter 0#), I# (S.pinnedFork 0#), I# (S.otherCounter 0#)]
  before <- getMonotonicTimeNSec
  delayed <- evaluate (I# (S.timedDelay 2000#))
  after <- getMonotonicTimeNSec
  if values == [1,1,1,1,1,1,11,11] && delayed == 2000 && after - before >= 2000000
    then mapM_ print (values ++ [delayed])
    else error ("Scheduling observations disagreed: " ++ show (values, delayed, after - before))
