-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module CompactSerializedAudit where

import GHC.Compact
import GHC.Compact.Serialized
import GHC.Exts (Int#, Int(..), isTrue#, reallyUnsafePtrEquality#, runRW#)
import GHC.IO (IO(..))
import Data.IORef
import Foreign.Marshal.Utils (copyBytes)

data Tree = Leaf Int | Branch Tree Tree
data Cycle = Cycle Int Cycle

run :: IO Int -> Int#
run (IO action) = runRW# (\s -> case action s of (# _, I# result #) -> result)

same :: a -> a -> Bool
same a b = isTrue# (reallyUnsafePtrEquality# a b)

importCopy :: Compact a -> IO (Maybe (Compact a), Int)
importCopy compacted = withSerializedCompact compacted $ \image -> do
  source <- newIORef (serializedCompactBlockList image)
  result <- importCompact image $ \to size -> do
    blocks <- readIORef source
    case blocks of
      (from, originalSize):rest | size == originalSize -> do
        copyBytes to from (fromIntegral size)
        writeIORef source rest
      _ -> error "compact block transfer changed size"
  pure (result, length (serializedCompactBlockList image))

treeSum :: Tree -> Int
treeSum (Leaf value) = value
treeSum (Branch left right) = treeSum left + treeSum right

{-# OPAQUE roundTrip #-}
roundTrip :: Int# -> Int#
roundTrip raw = run $ do
  let input = I# raw
  let child = Branch (Leaf input) (Leaf (input + 3))
  original <- compactWithSharing (Branch child child)
  (imported, _) <- importCopy original
  case imported of
    Nothing -> pure (-1)
    Just copy -> do
      let root = getCompact copy
      inside <- inCompact copy root
      oldOutside <- not <$> inCompact copy (getCompact original)
      compacted <- isCompact root
      let shared = case root of Branch left right -> same left right; _ -> False
      pure (treeSum root + if inside && oldOutside && compacted && shared && not (same root (getCompact original)) then 1000 else 0)

{-# OPAQUE cycleRoundTrip #-}
cycleRoundTrip :: Int# -> Int#
cycleRoundTrip raw = run $ do
  let input = I# raw
  let cyclic = Cycle input cyclic
  original <- compactWithSharing cyclic
  (imported, _) <- importCopy original
  case imported of
    Nothing -> pure (-1)
    Just copy -> case getCompact copy of
      root@(Cycle value next) -> pure (value + if same root next && not (same root (getCompact original)) then 100 else 0)

{-# OPAQUE multipleBlocks #-}
multipleBlocks :: Int# -> Int#
multipleBlocks raw = run $ do
  let input = I# raw
  original <- compactSized 4096 True [input .. input + 8191]
  (imported, blocks) <- importCopy original
  case imported of
    Nothing -> pure (-1)
    Just copy -> pure (sum (getCompact copy) + if blocks > 1 then 100 else 0)

{-# OPAQUE emptyRoundTrip #-}
emptyRoundTrip :: Int# -> Int#
emptyRoundTrip raw = run $ do
  let input = I# raw
  original <- compact ([] :: [Int])
  (imported, _) <- importCopy original
  pure (input + case imported of Just copy | null (getCompact copy) -> 100; _ -> 0)
