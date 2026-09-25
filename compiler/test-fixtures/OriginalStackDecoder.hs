-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-cse #-}
module OriginalStackDecoder (captureNamed, observeSnapshot) where

import Data.Char (ord)
import Data.List (intercalate)
import Data.Maybe (catMaybes, isJust)
import GHC.Exts
import GHC.Internal.Stack.CloneStack (StackSnapshot(..), cloneMyStack)
import GHC.Internal.Stack.Decode (decodeStackWithIpe, prettyStackFrameWithIpe)
import System.IO.Unsafe (unsafePerformIO)

{-# NOINLINE captureLeaf #-}
captureLeaf :: Int# -> StackSnapshot
captureLeaf token = unsafePerformIO $ do
  snapshot <- cloneMyStack
  case token >=# 0# of
    1# -> pure snapshot
    _ -> error "negative stack capture token"

{-# NOINLINE captureNamed #-}
captureNamed :: Int# -> StackSnapshot
captureNamed token = case captureLeaf token of snapshot@(StackSnapshot _) -> snapshot

-- Negative probes inspect the same captured stack twice. Nonnegative probes
-- return actual original formatter characters, never a replacement JVM renderer.
{-# NOINLINE observeSnapshot #-}
observeSnapshot :: StackSnapshot -> Int# -> Int#
observeSnapshot snapshot probe# = case unsafePerformIO observation of I# result# -> result#
  where
    observation = do
      first <- decodeStackWithIpe snapshot
      second <- decodeStackWithIpe snapshot
      let rendered = map prettyStackFrameWithIpe first
          text = intercalate "\n" (catMaybes rendered)
          stable = length first == length second && map snd first == map snd second &&
                   rendered == map prettyStackFrameWithIpe second
      pure $ case I# probe# of
        -1 -> length first
        -2 -> if stable then 1 else 0
        -3 -> length (filter (isJust . snd) first)
        -4 -> length (catMaybes rendered)
        -5 -> length text
        index -> if index >= 0 && index < length text then ord (text !! index) else -1
