-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedTuples #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the text cbits fixture.
module Main (main) where

import GHC.Exts
import GHC.Word (Word(W#), Word8(W8#))
import Numeric (readHex, showHex)
import TextCbitsAudit

data Bytes = Bytes ByteArray#
bytesFromList :: [Word8] -> Bytes
bytesFromList values = case length values of
  I# size -> runRW# (\state -> case newByteArray# size state of
    (# next, bytes #) ->
      let fill [] _ s = case unsafeFreezeByteArray# bytes s of (# _, frozen #) -> Bytes frozen
          fill (W8# byte : rest) index s = fill rest (index +# 1#) (writeWord8Array# bytes index byte s)
      in fill values 0# next)

decode :: String -> [Word8]
decode "-" = []
decode [] = []
decode (a:b:rest) = case readHex [a,b] of [(value,"")] -> value : decode rest; _ -> error "hex"
decode _ = error "odd hex"

row :: String -> String
row input = case words input of
  [operation,hex,off,len,arg] -> case (bytesFromList (decode hex),read off,read len,read arg) of
    (Bytes bytes,W# offset,W# size,W# count) ->
      let value = case operation of
            "memchr" -> show (I# (textMemchr bytes offset size count))
            "measure" -> show (I# (textMeasure bytes offset size count))
            "reverse" -> case textReverse bytes offset size of
              result -> let byte i = let hex = showHex (W8# (indexWord8Array# result i)) ""
                                    in replicate (2 - length hex) '0' ++ hex
                            go i | isTrue# (i ==# sizeofByteArray# result) = ""
                                 | otherwise = byte i ++ go (i +# 1#)
                            encoded = go 0#
                        in if null encoded then "-" else encoded
            _ -> error "operation"
      in input ++ "\t" ++ value
  _ -> error "input"
main :: IO ()
main = interact (unlines . map row . lines)
