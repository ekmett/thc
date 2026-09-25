-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module StablePointerAudit where

import GHC.Exts (Int(I#), Int#, touch#)
import GHC.IO (IO(..))
import GHC.Internal.Stable (newStablePtr, deRefStablePtr, freeStablePtr)
import System.IO.Unsafe (unsafePerformIO)

{-# OPAQUE stableComposite #-}
stableComposite :: Int# -> Int#
stableComposite raw = case unsafePerformIO $ do
  let value = I# raw
  first <- newStablePtr value
  second <- newStablePtr value
  alias <- pure first
  readBack <- deRefStablePtr first
  let score = (if first == alias then 17 else 0) +
              (if first /= second then 31 else 0)
  score `seq` pure ()
  freeStablePtr first
  freeStablePtr second
  pure (readBack + score) of I# result -> result

{-# OPAQUE bottom #-}
bottom :: Int
bottom = bottom

-- touch# keeps the dereferenced thunk live without evaluating it.
{-# OPAQUE lazyStable #-}
lazyStable :: Int# -> Int#
lazyStable raw = case unsafePerformIO $ do
  pointer <- newStablePtr bottom
  value <- deRefStablePtr pointer
  IO (\state -> case touch# value state of next -> (# next, () #))
  freeStablePtr pointer
  pure (I# raw + 73) of I# result -> result
