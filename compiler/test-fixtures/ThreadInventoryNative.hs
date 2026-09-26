-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Concurrent (forkIO, newEmptyMVar, putMVar, takeMVar)
import Control.Exception (SomeException, evaluate, try)
import GHC.Exts (Int(I#))
import qualified ThreadInventory as Audit

main :: IO ()
main = do
  done <- newEmptyMVar
  -- Native -threaded main is bound. Compare unbound forkIO with THC's admitted
  -- unbound-only runtime, explicitly, not by assuming main-thread equivalence.
  _ <- forkIO $ do
    results <- try $ mapM evaluate
      [I# (Audit.selfInventory 0#), I# (Audit.boundQuery 0#),
       I# (Audit.forkSnapshot 0#), if I# (Audit.snapshotSize 0#) >= 1 then 1 else 0]
    putMVar done (results :: Either SomeException [Int])
  result <- takeMVar done
  case result of
    Right [10, 0, 111, 1] -> mapM_ print [10, 0, 111, 1 :: Int]
    _ -> error ("thread inventory contract disagreed: " ++ show result)
