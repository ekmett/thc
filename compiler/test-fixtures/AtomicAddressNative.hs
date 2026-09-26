-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Word(W#))
import qualified AtomicAddressAudit as A
import Data.List (intercalate)

main :: IO ()
main = getContents >>= mapM_ answer . lines
  where
    answer row = case words row of
      ["numeric", op, initial, expected, desired] ->
        case (read op, read initial, read expected, read desired) of
          (I# operation, W# x, W# y, W# z) -> putStrLn (intercalate "\t"
            (["numeric",op,initial,expected,desired] ++
              [show (W# (A.atomicAddressNumeric operation x y z selector)) | I# selector <- [0..3]]))
      ["pointer", op, initial, expected, desired] ->
        case (read op, read initial, read expected, read desired) of
          (I# operation, I# x, I# y, I# z) -> putStrLn (intercalate "\t"
            (["pointer",op,initial,expected,desired] ++
              [show (I# (A.atomicAddressPointer operation x y z selector)) | I# selector <- [0..1]]))
      _ -> error "Expected kind, operation, initial, expected/operand, desired"
