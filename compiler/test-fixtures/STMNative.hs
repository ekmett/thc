-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where
import GHC.Exts
import GHC.IO (IO(..))
import Control.Concurrent
import Control.Exception (evaluate)
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
