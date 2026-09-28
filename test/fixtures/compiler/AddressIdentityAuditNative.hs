-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the address identity audit fixture.
module Main (main) where

import AddressIdentityAudit (probe)
import GHC.Exts (Int (I#))

main :: IO ()
main = mapM_ (\i -> case i of I# raw -> print (I# (probe raw))) [0 .. 4]
