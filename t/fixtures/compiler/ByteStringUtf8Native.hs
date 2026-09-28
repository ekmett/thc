-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the byte string utf8 fixture.
module Main where

import Control.Exception (evaluate)
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray)
import Foreign.Ptr (plusPtr)
import GHC.Exts (Int(..))
import GHC.Ptr (Ptr(..))
import ByteStringUtf8Audit

type Request = (String,[Word8],Int,Int)

-- ASCII and all multibyte widths, overlong encodings, surrogates, out-of-range
-- scalars, stray continuations, truncated tails and word/block boundaries.
patterns :: [[Word8]]
patterns = [[],[0,127],[194,128],[223,191],[224,160,128],[237,159,191],
  [238,128,128],[239,191,191],[240,144,128,128],[244,143,191,191],
  [128],[192,128],[193,191],[224,159,191],[237,160,128],[240,143,191,191],
  [244,144,128,128],[245,128,128,128],[226,130],[240,144,128]]

requests :: [Request]
requests = [(entry, replicate offset 255 ++ payload ++ [255], offset, length payload)
  | entry <- ["validateUnsafe","validateSafe"], suffix <- patterns,
    prefix <- [0,1,7,8,15,16,31,32,63,64], offset <- [0,3],
    let payload = replicate prefix 65 ++ suffix]

main :: IO ()
main = mapM observe requests >>= print
  where
    observe row@(entry,bytes,offset,count) = withArray bytes $ \base ->
      case (base `plusPtr` offset, count) of
        (Ptr address, I# size) -> do
          result <- evaluate $ I# $ case entry of
            "validateUnsafe" -> validateUnsafe address size
            "validateSafe" -> validateSafe address size
            _ -> error "Unknown UTF-8 request"
          pure (row,result)
