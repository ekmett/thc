-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import GHC.Exts
import GHC.Stable (StablePtr(..), newStablePtr, freeStablePtr)
import qualified AlignedScalarMemoryAudit as Audit
import Data.List (intercalate)

main :: IO ()
main = do
  first@(StablePtr first#) <- newStablePtr (17 :: Int)
  second@(StablePtr second#) <- newStablePtr (29 :: Int)
  input <- getContents
  let answer row = case words row of
        [kind,rawText,offsetText] -> case (read rawText, read offsetText) of
          (I# raw,I# offset) ->
            let at (I# selector) = I# (case kind of {
                  "WideChar" -> Audit.alignedWideChar raw offset selector;
                  "StablePtr" -> Audit.alignedStablePtr first# second# offset selector;
                  _ -> 0# })
            in putStrLn (intercalate "\t" (kind : rawText : offsetText : map (show . at) [0..5]))
        _ -> error "Expected scalar type, raw bits, element index"
  mapM_ answer (lines input)
  freeStablePtr first
  freeStablePtr second
