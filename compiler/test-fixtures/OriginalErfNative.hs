-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Monad (forM_)
import Data.List (intercalate)
import Data.Number.Erf (erf, erfc)
import Data.Word (Word32, Word64)
import GHC.Float (castDoubleToWord64, castFloatToWord32, castWord32ToFloat, castWord64ToDouble)

-- Invoke unchanged erf-2.0.0.0 through its public API. Record bit patterns so
-- signed zero, subnormals, infinities, and the native library's NaNs survive.
main :: IO ()
main = do
  let doubles :: [Word64]
      doubles = [0, 0x8000000000000000, 1, 0x8000000000000001,
        0x0010000000000000, 0x8010000000000000, 0x7ff0000000000000,
        0xfff0000000000000, 0x7ff8000000000042] ++
        map castDoubleToWord64 [-30,-10,-6,-2,-1,-0.5,0.125,0.5,1,2,6,10,30]
      floats :: [Word32]
      floats = [0, 0x80000000, 1, 0x80000001, 0x00800000, 0x80800000,
        0x7f800000, 0xff800000, 0x7fc00042] ++
        map castFloatToWord32 [-30,-10,-6,-2,-1,-0.5,0.125,0.5,1,2,6,10,30]
  forM_ [("erf",erf),("erfc",erfc)] $ \(symbol,function) ->
    forM_ doubles $ \bits -> putStrLn $ intercalate "\t"
      [symbol,show bits,show (castDoubleToWord64 (function (castWord64ToDouble bits)))]
  forM_ [("erff",erf),("erfcf",erfc)] $ \(symbol,function) ->
    forM_ floats $ \bits -> putStrLn $ intercalate "\t"
      [symbol,show bits,show (castFloatToWord32 (function (castWord32ToFloat bits)))]
