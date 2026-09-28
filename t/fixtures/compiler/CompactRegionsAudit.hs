-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ScopedTypeVariables #-}

-- |
-- Module      : CompactRegionsAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for compact regions audit Core and metadata.
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
data AsyncBox = AsyncBox Int#
data AsyncThread = AsyncThread ThreadId#
data AsyncCells = AsyncCells (MVar# RealWorld AsyncBox) (MVar# RealWorld AsyncBox)
  (MVar# RealWorld AsyncBox) (MVar# RealWorld AsyncThread) (MutVar# RealWorld AsyncBox)
data SharedCompact = SharedCompact Tree

{-# OPAQUE blockedTree #-}
blockedTree :: AsyncCells -> Tree
blockedTree (AsyncCells ready gate _ _ counter) =
  case readMutVar# counter realWorld# of { (# s1, AsyncBox before #) ->
  case writeMutVar# counter (AsyncBox (before +# 1#)) s1 of { s2 ->
  case putMVar# ready (AsyncBox 1#) s2 of { s3 ->
  case takeMVar# gate s3 of { (# _, _ #) -> Leaf 37 } } } }

{-# OPAQUE makeSharedCompact #-}
makeSharedCompact :: Int# -> Compact# -> AsyncCells -> SharedCompact
makeSharedCompact mode region cells = SharedCompact $
  let source = Branch (Leaf 5) (blockedTree cells)
  in case mode of
    0# -> case compactAdd# region source realWorld# of (# _, value #) -> value
    _ -> case compactAddWithSharing# region source realWorld# of (# _, value #) -> value

{-# OPAQUE compactChild #-}
compactChild :: SharedCompact -> AsyncCells -> State# RealWorld -> (# State# RealWorld, () #)
compactChild shared (AsyncCells _ _ done identity _) s0 =
  case myThreadId# s0 of { (# s1, tid #) ->
  case putMVar# identity (AsyncThread tid) s1 of { s2 ->
  case catch# (\s -> case shared of { SharedCompact value ->
                      case total value of I# n -> (# s, AsyncBox n #) })
              (\_ s -> (# s, AsyncBox (-1#) #)) s2 of { (# s3, result #) ->
  case putMVar# done result s3 of { s4 -> (# s4, () #) } } } }

-- The compaction itself is shared, not just its source child. Delivery must
-- retain the copier's unfinished work; the handler never forces its payload.
{-# OPAQUE interruptedCompact #-}
interruptedCompact :: Int# -> Int# -> Int#
interruptedCompact mode token = runRW# $ \s0 ->
  case compactNew# 4096## s0 of { (# s1, region #) ->
  case newMVar# s1 of { (# s2, ready #) ->
  case newMVar# s2 of { (# s3, gate #) ->
  case newMVar# s3 of { (# s4, done #) ->
  case newMVar# s4 of { (# s5, identity #) ->
  case newMutVar# (AsyncBox 0#) s5 of { (# s6, counter #) ->
  let cells = AsyncCells ready gate done identity counter
      shared = makeSharedCompact mode region cells
  in case fork# (compactChild shared cells) s6 of { (# s7, _ #) ->
  case takeMVar# identity s7 of { (# s8, AsyncThread tid #) ->
  case takeMVar# ready s8 of { (# s9, AsyncBox signalled #) ->
  case killThread# tid (raise# (AsyncBox 99#) :: AsyncBox) s9 of { s10 ->
  case takeMVar# done s10 of { (# s11, AsyncBox caught #) ->
  case putMVar# gate (AsyncBox 1#) s11 of { s12 ->
  case shared of { SharedCompact value ->
  case value of { evaluated@(Branch _ _) ->
  case total evaluated of { I# answer ->
  case compactContains# region evaluated s12 of { (# s13, inside #) ->
  case readMutVar# counter s13 of { (# _, AsyncBox prefixes #) ->
    case (signalled ==# 1#) `andI#` (caught ==# -1#) `andI#`
         (answer ==# 42#) `andI#` (inside ==# 1#) `andI#` (prefixes ==# 1#) of
      1# -> token +# 43#
      _ -> -999#
  } } } } } } } } } } } } } } } } }

{-# OPAQUE interruptedPlain #-}
interruptedPlain :: Int# -> Int#
interruptedPlain = interruptedCompact 0#
{-# OPAQUE interruptedSharing #-}
interruptedSharing :: Int# -> Int#
interruptedSharing = interruptedCompact 1#

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
