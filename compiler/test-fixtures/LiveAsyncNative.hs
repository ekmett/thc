-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import Control.Concurrent
import Control.Exception
import System.Timeout
import GHC.Exts (Int(I#))
import qualified LiveAsyncAudit as A

force :: Int -> IO Int
force (I# token) = evaluate (I# (A.forceShared token))
ready :: IO Int
ready = evaluate (I# (A.takeReady 0#))
count :: IO Int
count = evaluate (I# (A.prefixCount 0#))
main :: IO ()
main = do
  result <- newEmptyMVar
  tid <- forkIO $ do
    x <- try (force 0) :: IO (Either SomeException Int)
    putMVar result x
  r <- timeout 5000000 ready
  ack <- newEmptyMVar
  _ <- forkIO $ do
    throwTo tid ThreadKilled
    putMVar ack ()
  a <- timeout 5000000 (takeMVar result)
  b <- timeout 5000000 (takeMVar ack)
  release <- evaluate (I# (A.releaseGate 0#))
  c <- timeout 5000000 (force 1)
  d <- count
  warm <- evaluate (I# (A.warmLoop 1024#))
  case (r,a,b,release,c,d,warm) of
    (Just 1007, Just (Right (-1)), Just (), 1, Just 10000008, 1, 1031) ->
      putStr "1007\n-1\n10000008\n1\n1031\n"
    _ -> error ("Native interrupted-thunk protocol failed: " ++ show (r,a,b,release,c,d,warm))
