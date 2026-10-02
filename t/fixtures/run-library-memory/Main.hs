{-# LANGUAGE ScopedTypeVariables #-}
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell with array
--
-- Executable for the @run-library-memory@ integration fixture.
module Main (main) where

import Control.Monad.ST (ST, runST)
import Data.Array.ST (STUArray, freeze, getElems, newListArray, thaw, writeArray)
import Data.Array.Unboxed (UArray, elems)
import Data.Char (chr, ord)
import qualified Data.ByteString as Bytes
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray)
import Numeric.Natural (Natural)

arrayCopies :: ([Word8], [Word8])
arrayCopies = runST action
  where
    action :: forall s. ST s ([Word8], [Word8])
    action = do
      original <- newListArray (0, 3) [2, 3, 5, 7] :: ST s (STUArray s Int Word8)
      frozen <- freeze original :: ST s (UArray Int Word8)
      writeArray original 0 99
      copied <- thaw frozen :: ST s (STUArray s Int Word8)
      writeArray copied 1 88
      result <- getElems copied
      pure (elems frozen, result)

main :: IO ()
main = do
  print arrayCopies
  -- bytestring's original CString import returns CSize, not GHC's Int# variant.
  bytes <- withArray [65, 66, 0, 67] Bytes.packCString
  print (Bytes.unpack bytes)
  -- The CString result supplies a runtime seed; none of this arithmetic is a
  -- constant expression for GHC to evaluate while compiling the fixture.
  let seed = toInteger (Bytes.head bytes)
      carry = 18446744073709551615 + seed
      negative = negate (carry * carry + seed)
      naturalCarry = 18446744073709551615 + fromInteger seed :: Natural
  print (carry, negative `quotRem` carry, naturalCarry)
  -- Runtime-dependent inputs exercise signed boundaries and low-word truncation.
  let offset = seed - 65
      signed = map (+ offset)
        [ -9223372036854775809, -9223372036854775808, -1, 0
        , 9223372036854775807, 9223372036854775808
        , 340282366920938463463374607431768211521
        , -340282366920938463463374607431768211521
        ]
      naturals = map (fromInteger . (+ offset))
        [0, 18446744073709551615, 18446744073709551616,
         340282366920938463463374607431768211521] :: [Natural]
  print (map (toInteger . (fromInteger :: Integer -> Int)) signed,
    map (fromIntegral :: Natural -> Word) naturals)
  let text = Text.pack (map (chr . fromIntegral) (Bytes.unpack bytes) ++ "\x03bb\x1f600\x00e9")
      backwards = Text.reverse text
      middle = Text.take 3 (Text.drop 1 backwards)
  print (map ord (Text.unpack backwards), map ord (Text.unpack middle),
    Text.decodeUtf8 (Text.encodeUtf8 text) == text)
