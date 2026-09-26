-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where
import GHC.Exts
import GHC.IO (IO(..))
import Control.Concurrent
import Control.Exception (evaluate, AsyncException(ThreadKilled))
import Control.Monad (forM_, replicateM_)
import qualified STMAudit as P

emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit name f x@(I# a) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f a)))
data Cell = Cell (TVar# RealWorld P.Box)
new :: Int -> IO Cell
new (I# x) = IO (\s -> case newTVar# (P.Box x) s of (# t, v #) -> (# t, Cell v #))
bump :: Cell -> Int -> IO Int
bump (Cell v) (I# x) = IO (\s -> case atomically# (P.bumpAction v x) s of
  (# t, P.Box result #) -> (# t, I# result #))
readCell :: Cell -> IO Int
readCell (Cell v) = evaluate (I# (P.readCell v))

-- Invoke the fixture once per IO execution, not as a native-driver CAF shared
-- between the interrupted call and its later observation.
{-# OPAQUE invoke #-}
invoke :: (Int# -> Int#) -> Int -> IO Int
invoke f (I# n) = IO (\s -> case f n of result -> (# s, I# result #))

main :: IO ()
main = do
  forM_ [-31,-1,0,1,17,63,4097] $ \x -> do
    emit "basic" P.basic x
    emit "rollback" P.rollback x
    emit "alternative" P.alternative x
    emit "lazyPayload" P.lazyPayload x
    emit "nestedAtomic" P.nestedAtomic x
    emit "unliftedPayload" P.unliftedPayload x
  cell <- new 0
  gate <- newEmptyMVar
  done <- newEmptyMVar
  let worker = takeMVar gate >> replicateM_ 64 (bump cell 1) >> putMVar done ()
  _ <- forkIO worker
  _ <- forkIO worker
  putMVar gate () >> putMVar gate ()
  takeMVar done >> takeMVar done
  value <- readCell cell
  putStrLn ("concurrent\t128\t" ++ show value)
  forM_ [0,1] $ \side -> do
    a@(Cell av) <- new 0
    b@(Cell bv) <- new 0
    result <- newEmptyMVar
    _ <- forkIO (evaluate (I# (P.awaitEither av bv)) >>= putMVar result)
    _ <- bump (if side == 0 then a else b) 7
    answer <- takeMVar result
    putStrLn ("either\t" ++ show side ++ "\t" ++ show answer)
  let observation name action = action >>= \n -> putStrLn (name ++ "\t0\t" ++ show n)
      call = invoke
  forM_ [("retry",P.forceRetry),("inner",P.forceInner)] $ \(name,force) -> do
    result <- newEmptyMVar
    target <- forkIO (call force 0 >>= putMVar result)
    _ <- call P.asyncReady 0
    threadDelay 20000
    throwTo target ThreadKilled
    observation (name ++ "-caught") (takeMVar result)
    observation (name ++ "-aborted") (call P.asyncValue 0)
    observation (name ++ "-prefix") (call P.asyncPrefixes 0)
    _ <- call P.asyncSet (if name == "retry" then 4 else 9)
    if name == "inner" then call P.asyncRelease 0 >> pure () else pure ()
    observation (name ++ "-resumed") (call force 0)
    observation (name ++ "-committed") (call P.asyncValue 0)
    observation (name ++ "-prefix-after") (call P.asyncPrefixes 0)
    if name == "retry" then call P.asyncReady 0 >> pure () else pure ()
