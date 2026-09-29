-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- | Native observer using the same steady timing driver as the Core fixtures.
module Main (main) where
import NativeTiming (mainFor)
import qualified ByteStringBenchmarks as B
main :: IO ()
main = mainFor [("bytestringReadInt", B.bytestringReadInt)]
