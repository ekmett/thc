-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module ShowIntAudit where
import Data.Char (ord)
import Data.List (foldl')
import GHC.Exts (Int(I#), Int#)

-- These consume the ordinary public Show Int implementation. The installed
-- recursive digit worker must come from the complete original Show source.
{-# OPAQUE showChecksum #-}
showChecksum :: Int# -> Int#
showChecksum input = case foldl' (\acc c -> acc * 33 + ord c) 5381 (show (I# input)) of
  I# result -> result

-- Observe every character, order, and the end of the string independently of
-- the wrapping checksum. Negative and past-end indices return a sentinel.
{-# OPAQUE showCharacter #-}
showCharacter :: Int# -> Int# -> Int#
showCharacter input index = case at (I# index) (show (I# input)) of
  I# result -> result
 where
  at n _ | n < 0 = -1
  at _ [] = -1
  at 0 (c:_) = ord c
  at n (_:cs) = at (n-1) cs
