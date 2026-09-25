-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import SimdFloatFma
import System.Exit (die)
import Text.Read (readMaybe)

main :: IO ()
main = getContents >>= mapM_ row . lines
  where
    row line = case words line of
      [name,a,b,c,d] -> case (readMaybe a, readMaybe b, readMaybe c, readMaybe d) of
        (Just (W# x), Just (W# y), Just (W# z), Just (I# lane)) -> do
          let operation = case name of
                "addCase" -> addCase; "subCase" -> subCase
                "negAddCase" -> negAddCase; "negSubCase" -> negSubCase
                "wideAddCase" -> wideAddCase; "wideSubCase" -> wideSubCase
                "wideNegAddCase" -> wideNegAddCase; "wideNegSubCase" -> wideNegSubCase
                "doubleAddCase" -> doubleAddCase; "doubleSubCase" -> doubleSubCase
                "doubleNegAddCase" -> doubleNegAddCase; "doubleNegSubCase" -> doubleNegSubCase
                _ -> error "Unknown fused vector entry"
          putStrLn (unwords [name,a,b,c,d,show (W# (operation x y z lane))])
        _ -> die "Malformed fused vector operands"
      _ -> die "Malformed fused vector request"
