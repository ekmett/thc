-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module ShowWordListAudit where
import Data.Char (ord)
import Data.List (foldl')
import GHC.Exts (Int(I#), Int#, Word(W#), int2Word#)

checksum :: String -> Int#
checksum text = case foldl' (\acc c -> acc * 33 + ord c) 5381 text of
  I# result -> result

character :: Int# -> String -> Int#
character index text = case at (I# index) text of I# result -> result
 where
  at n _ | n < 0 = -1
  at _ [] = -1
  at 0 (c:_) = ord c
  at n (_:cs) = at (n-1) cs

-- Observe the actual public unsigned Show implementation, including values whose
-- high bit is set. No custom digit formatter substitutes for the library body.
{-# OPAQUE wordChecksum #-}
wordChecksum :: Int# -> Int#
wordChecksum input = checksum (show (W# (int2Word# input)))
{-# OPAQUE wordCharacter #-}
wordCharacter :: Int# -> Int# -> Int#
wordCharacter input index = character index (show (W# (int2Word# input)))

payload :: Int# -> Int# -> [Int]
payload input shape = case I# shape of
  0 -> []
  1 -> [I# input]
  _ -> let n = I# input in [n, n + 1, negate n]

-- Empty, singleton and multiple elements exercise the public Show [Int] list
-- worker and its lazy ShowS tail as well as the original signed digit worker.
{-# OPAQUE listChecksum #-}
listChecksum :: Int# -> Int# -> Int#
listChecksum input shape = checksum (show (payload input shape))
{-# OPAQUE listCharacter #-}
listCharacter :: Int# -> Int# -> Int# -> Int#
listCharacter input shape index = character index (show (payload input shape))
