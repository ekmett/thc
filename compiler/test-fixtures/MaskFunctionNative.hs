-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import MaskFunctionAudit

main :: IO ()
main = mapM_ emit
  [("maskedFunction", \(I# n) -> I# (maskedFunction n)),
   ("unmaskedFunction", \(I# n) -> I# (unmaskedFunction n)),
   ("uninterruptibleFunction", \(I# n) -> I# (uninterruptibleFunction n)),
   ("lazyFunctions", \(I# n) -> I# (lazyFunctions n)),
   ("bareMasks", \(I# n) -> I# (bareMasks n))]
  where
    emit (name, run) = mapM_ (\n -> putStrLn (name ++ "\t" ++ show n ++ "\t" ++ show (run n)))
      [-17, 0, 23]
