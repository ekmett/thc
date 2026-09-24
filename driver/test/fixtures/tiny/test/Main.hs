-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main where

import Greeting (greeting)

main :: IO ()
main = if greeting `elem` ["hello Cabal", "HELLO Cabal"] then pure () else fail greeting
