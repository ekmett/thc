-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#))
import ParkedControl (observe)
main :: IO ()
main = mapM_ (\(I# depth) -> print (I# (observe depth))) [0, 96]
