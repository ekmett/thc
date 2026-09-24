-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#), Word(W#))
import qualified FloatingAddressAudit as Audit
import Data.List (intercalate)

main :: IO ()
main = getContents >>= mapM_ answer . lines
  where
    answer row = case words row of
      [floatText,doubleText] -> case (read floatText, read doubleText) of
        (W# floatBits,W# doubleBits) ->
          putStrLn (floatText ++ "\t" ++ doubleText ++ "\t" ++
            intercalate "\t" [show (W# (Audit.floatingAddressBits floatBits doubleBits selector)) |
              I# selector <- [0..3]])
      _ -> error "Expected two unsigned bit patterns"
