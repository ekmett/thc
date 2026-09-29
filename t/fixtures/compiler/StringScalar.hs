-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where
import Data.Char (ord)
main :: IO ()
main = print (map ord "Aé😀")
