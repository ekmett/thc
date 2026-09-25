-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where
import ForeignExportManaged
main :: IO ()
main = do
  print (addOne (-19))
  print (floatValue 1.25)
  print (doubleValue (-3.5))
  print constant
  next 3 >>= print
  next 4 >>= print
  unit
  print (wordValue 9223372036854775827)
