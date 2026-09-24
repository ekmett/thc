-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import qualified WordFloatingAudit as P

emit :: String -> IO ()
emit input = case read input :: Word of
  W# x -> putStrLn (input ++ "\t" ++ show (W# (word32ToWord# (castFloatToWord32# (P.wordFloat x)))) ++
    "\t" ++ show (W# (word64ToWord# (castDoubleToWord64# (P.wordDouble x)))))
main :: IO ()
main = getContents >>= mapM_ emit . lines
