-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import CoreContinuationAudit
import GHC.Exts

main :: IO ()
main = do
  print sharedAnswer
  print applicationAnswer
  print catchActionAnswer
  print catchActionFailure
