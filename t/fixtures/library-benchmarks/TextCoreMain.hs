-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import qualified TextBenchmarks as T
import GHC.Exts (Int(I#))
main :: IO ()
main = I# (T.textDecodeUtf8 10000#) `seq` I# (T.textSearch 10000#) `seq` pure ()
