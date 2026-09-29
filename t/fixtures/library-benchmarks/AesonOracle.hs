-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import NativeTiming (mainFor)
import qualified AesonBenchmarks as A
main :: IO ()
main = mainFor [("aesonDecodeValue", A.aesonDecodeValue)]
