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
-- Native GHC observer for the ghc bco fixture.
module Main where
import GhcBCO
import GHC.Exts (Int(I#))
import System.IO (BufferMode(LineBuffering), hSetBuffering, stdout)
main :: IO ()
main = hSetBuffering stdout LineBuffering >> mapM_ row [-2, 0, 7]
  where
    row (I# n) = mapM_ print
      [I# (bcoConstant n), I# (bcoApply n), I# (bcoApplyTwo n), I# (bcoFunction n),
       I# (bcoArithmetic n), I# (bcoBranch n), I# (bcoLargeOperand n), I# (bcoSharing n), I# (bcoCase n),
       I# (bcoCaseNested n), I# (bcoCasePointer n), I# (bcoCaseFloat n), I# (bcoCaseDouble n), I# (bcoCaseLong n), I# (bcoCaseVoid n), I# (bcoPacked8 n), I# (bcoPacked16 n), I# (bcoPacked32 n), I# (bcoCaseTuple n), I# (bcoCaseTupleCall n), I# (bcoCaseTupleOverapply n), I# (bcoCapturedPap n), I# (bcoCapturedAp n), I# (bcoCapturedNoUpd n), I# (bcoCapturedApChain n), I# (bcoCapturedRecursive n), I# (bcoCapturedFloat n), I# (bcoCapturedDouble n), I# (bcoCapturedLong n), I# (bcoCapturedNoUpdEscape n), I# (bcoApplyIntCore n), I# (bcoApplyFloatCore n), I# (bcoApplyDoubleCore n), I# (bcoApplyLongCore n), I# (bcoApplyVoidCore n)]
