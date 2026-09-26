-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import Control.Exception (bracket)
import GHC.Exts
import GHC.Stable (StablePtr(..), newStablePtr, deRefStablePtr, freeStablePtr)

-- Slot one starts eight bytes from the base on the pinned 64-bit target.
-- The returned handle remains owned by the caller: the array does not free it.
{-# OPAQUE stableCell #-}
stableCell :: StablePtr a -> StablePtr a
stableCell (StablePtr handle) = runRW# (\s0 ->
  case newPinnedByteArray# 16# s0 of { (# s1, cells #) ->
  case writeStablePtrArray# cells 1# handle s1 of { s2 ->
  case unsafeFreezeByteArray# cells s2 of { (# _, frozen #) ->
  StablePtr (indexStablePtrArray# frozen 1#)
  } } })

-- A WideChar slot is four bytes, including for a character outside the BMP.
{-# OPAQUE wideCell #-}
wideCell :: Char -> Char
wideCell (C# character) = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, cells #) ->
  case writeWideCharArray# cells 1# character s1 of { s2 ->
  case unsafeFreezeByteArray# cells s2 of { (# _, frozen #) ->
  C# (indexWideCharArray# frozen 1#)
  } } })

main :: IO ()
main = bracket (newStablePtr ("retained opaque handle" :: String)) freeStablePtr $ \handle -> do
  deRefStablePtr (stableCell handle) >>= putStrLn
  print (codepoint (wideCell '\x1f642'))
  where codepoint (C# c) = I# (ord# c)
