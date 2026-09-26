-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import NativeLeft
import NativeRight
import System.IO (hFlush, stdout)

main :: IO ()
main = do
  mapM_ (\value -> print (value, leftHeader value, rightHeader value,
    first value, firstAgain value, second value)) [0, 1, 42, 18446744073709551615]
  hFlush stdout
