-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Monad (forM_)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Short as BSS
import Data.Digest.Adler32
import Data.Digest.CRC32
import Data.Digest.CRC32C
import Data.List (intercalate)
import Data.Word

-- All observations use the original digest package's public Haskell API.
-- The JVM test calls its retained, typed C adapters directly; this isolates
-- native-source acquisition from unrelated whole-program Core obligations.
main :: IO ()
main = forM_ [0, 1, 3, 4, 15, 16, 255, 256, 5552] $ \count ->
  forM_ [0, 1, 3] $ \offset -> do
    let values = [fromIntegral (i * 37 + 11) :: Word8 | i <- [0 .. count + offset - 1]]
        bytes = BS.drop offset (BS.pack values)
        short = BSS.pack (drop offset values)
        row symbol carrier seed result = putStrLn $ intercalate "\t"
          [symbol,carrier,show offset,show count,show seed,show (result :: Word32)]
    forM_ [0, maxBound :: Word32] $ \seed -> do
      row "crc32" "AddrRep" seed (crc32Update seed bytes)
      row "crc32c_extend" "AddrRep" seed (crc32cUpdate seed bytes)
      row "crc32c_extend" "ByteArray#" seed (crc32cUpdate seed short)
    forM_ [1, 0x12345678 :: Word32] $ \seed ->
      row "adler32" "AddrRep" seed (adler32Update seed bytes)
    row "crc32c_value" "AddrRep" (0 :: Word32) (crc32c bytes)
    row "crc32c_value" "ByteArray#" (0 :: Word32) (crc32c short)
