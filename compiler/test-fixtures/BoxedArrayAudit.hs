-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, ScopedTypeVariables, UnboxedTuples #-}
module BoxedArrayAudit where

import GHC.Exts (Int(I#), Int#, runRW#, newArray#, readArray#, writeArray#, unsafeFreezeArray#, indexArray#, (+#))
import Control.Monad.ST (ST, runST)
import Data.Array (Array, array, listArray, (!))
import Data.Array.ST (STArray, runSTArray, newArray, readArray, writeArray)

-- This bottom is intentionally reachable from each lazy array's construction.
-- The exported reachable closure must retain its exception dependencies.
bottomElement :: Int
bottomElement = error "boxed array scope: unused bottom element"
{-# OPAQUE bottomElement #-}

boxedLiteral :: Int# -> Int#
boxedLiteral raw =
  let a = array (-1,2) [(-1,I# raw),(0,bottomElement),(1,I# raw+7),(2,2*I# raw)] :: Array Int Int
  in case (a!(-1))*7 + (a!1)*11 + (a!2)*13 of I# answer -> answer

boxedST :: Int# -> Int#
boxedST raw =
  let a :: Array Int Int
      a = runSTArray $ do
        m <- newArray (-1,2) bottomElement
        writeArray m (-1) (I# raw)
        x <- readArray m (-1)
        writeArray m 1 (x+7)
        y <- readArray m 1
        writeArray m 2 (3*y-I# raw)
        pure m
  in case (a!(-1))*7 + (a!1)*11 + (a!2)*13 of I# answer -> answer

-- Executing readArray must retrieve, but must not evaluate, the bottom value.
-- A later write replaces it before any requested value is forced.
boxedLazyRead :: Int# -> Int#
boxedLazyRead raw = case runST work of I# answer -> answer
  where
    work :: forall s. ST s Int
    work = do
      m <- newArray (0,1) bottomElement :: ST s (STArray s Int Int)
      ignored <- readArray m 0
      writeArray m 0 (I# raw+9)
      x <- readArray m 0
      pure (3*x)

-- Deliberately dynamic checked indexing keeps public out-of-range paths.
-- The native oracle only requests valid indices 0..3; no cold branches removed.
boxedChecked :: Int# -> Int# -> Int#
boxedChecked raw index =
  let a = listArray (0,3) [I# raw, I# raw+7, 2*I# raw, I# raw-11] :: Array Int Int
  in case a ! I# index of I# answer -> answer

-- Positive minimum: genuine recursive bottom, with no error/string machinery.
-- It remains a reachable CAF used to initialize every cell, and is read once
-- without being forced. Cell 0 stays bottom and is never selected by indexing.
boxedSTRecursive :: Int# -> Int#
boxedSTRecursive raw =
  let bottom :: Int
      bottom = bottom
      a :: Array Int Int
      a = runSTArray $ do
        m <- newArray (-1,2) bottom
        ignored <- readArray m 0
        writeArray m (-1) (I# raw)
        x <- readArray m (-1)
        writeArray m 1 (x+7)
        y <- readArray m 1
        writeArray m 2 (3*y-I# raw)
        pure m
  in case (a!(-1))*7 + (a!1)*11 + (a!2)*13 of I# answer -> answer


-- Zero elements still require a valid State token but never enter the initializer.
boxedZero :: Int# -> Int#
boxedZero raw = case runRW# (\s ->
  let bottom :: Int; bottom = bottom
  in case newArray# 0# bottom s of
    (# s1, a #) -> case unsafeFreezeArray# a s1 of
      (# _, _ #) -> I# raw) of I# answer -> answer

-- Read snapshots must not turn into delayed re-reads after the mutable write.
boxedSnapshot :: Int# -> Int#
boxedSnapshot raw = case runRW# (\s ->
  case newArray# 1# (I# raw + 1) s of
    (# s1, a #) -> case readArray# a 0# s1 of
      (# s2, old #) -> case writeArray# a 0# (I# raw + 17) s2 of
        s3 -> case readArray# a 0# s3 of
          (# s4, now #) -> case unsafeFreezeArray# a s4 of
            (# _, frozen #) -> case indexArray# frozen 0# of
              (# final #) -> old*3 + now*5 + final*7) of I# answer -> answer

boxedClosure :: Int# -> Int#
boxedClosure raw =
  let bottom :: Int -> Int
      bottom = bottom
      a :: Array Int (Int -> Int)
      a = runSTArray $ do
        m <- newArray (0,1) bottom
        writeArray m 0 (\n -> n + I# raw)
        pure m
  in case (a!0) (I# raw+1) of I# answer -> answer
