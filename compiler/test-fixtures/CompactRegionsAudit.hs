-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ScopedTypeVariables #-}
module CompactRegionsAudit where

import Control.Exception (CompactionFailed, catch)
import Data.IORef (newIORef)
import GHC.Compact
import GHC.Exts
import GHC.IO (IO(..))

data Tree = Leaf Int | Branch Tree Tree
data Cycle = Cycle Int Cycle
data BoxedArray = BoxedArray (Array# Int)
data MutableArray = MutableArray (MutableArray# RealWorld Int)
data Bytes = Bytes ByteArray#

{-# OPAQUE tree #-}
tree :: Int -> Tree
tree n = Branch (Leaf (n + 1)) (Leaf (n + 2))
{-# OPAQUE total #-}
total :: Tree -> Int
total (Leaf n) = n
total (Branch a b) = total a + total b

run :: IO Int -> Int#
run (IO action) = runRW# (\s -> case action s of (# _, I# result #) -> result)
bit :: Bool -> Int
bit False = 0
bit True = 1

{-# OPAQUE ordinary #-}
ordinary :: Int# -> Int#
ordinary n = run $ do
  let original = tree (I# n)
  region <- compactSized 4096 False original
  inside <- inCompact region (getCompact region)
  outside <- inCompact region original
  anywhere <- isCompact (getCompact region)
  bytes <- compactSize region
  compactResize region 8192
  grown <- compactSize region
  added <- compactAdd region (Branch (getCompact region) (tree (I# n)))
  member <- inCompact region (getCompact added)
  pure (total (getCompact added) + 100 * bit (inside && not outside && anywhere && member && grown > bytes))

{-# OPAQUE sharing #-}
sharing :: Int# -> Int#
sharing n = run $ do
  let child = tree (I# n)
      original = Branch child child
  yes <- compactWithSharing original
  no <- compact original
  let shared = case getCompact yes of
        Branch a b -> isTrue# (reallyUnsafePtrEquality# a b)
        _ -> False
      separate = case getCompact no of
        Branch a b -> not (isTrue# (reallyUnsafePtrEquality# a b))
        _ -> False
  pure (total (getCompact yes) + 100 * bit (shared && separate))

{-# OPAQUE cycleCase #-}
cycleCase :: Int# -> Int#
cycleCase n = run $ do
  let original = Cycle (I# n) original
  region <- compactWithSharing original
  case getCompact region of
    value@(Cycle x next) -> do
      member <- inCompact region next
      pure (x + 100 * bit (member && isTrue# (reallyUnsafePtrEquality# value next)))

failure :: IO (Compact a) -> IO Int
failure action = catch (action >> pure 0) (\(_ :: CompactionFailed) -> pure 1)

{-# OPAQUE rejectedObjects #-}
rejectedObjects :: Int# -> Int#
rejectedObjects n = run $ do
  function <- failure (compact ((+ I# n) :: Int -> Int))
  reference <- newIORef (I# n)
  mutable <- failure (compact reference)
  array <- IO $ \s -> case newArray# 2# (I# n) s of
    (# s1, a #) -> (# s1, MutableArray a #)
  mutableArray <- failure (compact array)
  pinned <- IO $ \s -> case newPinnedByteArray# 8# s of
    (# s1, a #) -> case writeWord8Array# a 0# (wordToWord8# 7##) s1 of
      s2 -> case unsafeFreezeByteArray# a s2 of (# s3, b #) -> (# s3, Bytes b #)
  pinnedBytes <- failure (compact pinned)
  pure (I# n + function + 10 * mutable + 100 * mutableArray + 1000 * pinnedBytes)

{-# OPAQUE frozenArray #-}
frozenArray :: Int# -> Int#
frozenArray n = run $ do
  original <- IO $ \s -> case newArray# 2# (I# n) s of
    (# s1, a #) -> case unsafeFreezeArray# a s1 of (# s2, b #) -> (# s2, BoxedArray b #)
  region <- compactWithSharing original
  case getCompact region of
    BoxedArray a -> case indexArray# a 0# of
      (# I# x #) -> case indexArray# a 1# of
        (# I# y #) -> pure (I# (x +# y))
