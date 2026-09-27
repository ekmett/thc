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
-- Native GHC observer for the core continuation fixture.
module Main where

import CoreContinuationAudit
import GHC.Exts

main :: IO ()
main = do
  print sharedAnswer
  print applicationAnswer
  print catchActionAnswer
  print catchActionFailure
  print nestedCatchAction
  print tupleApplicationAnswer
  print tupleCompactAnswer
  print catchHandlerAnswer
  print maskedCheckpointAnswer
  print unmaskedCheckpointAnswer
  print uninterruptibleCheckpointAnswer
  print forceNonlocalAnswer
  print compactScalarAnswer
  print typedScalarAnswer
  print overapplicationThunk
  print overapplicationTail
  case directOverapplicationTailThunk of Box value -> print (I# value)
  print tupleOverapplicationThunk
  print tupleTailOverapplicationThunk
