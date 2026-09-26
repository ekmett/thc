-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import GHC.Exts
import GHC.Stable (StablePtr(..), newStablePtr, freeStablePtr)
import qualified UnalignedScalarMemoryAudit as Audit
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
                  "Char" -> Audit.unalignedChar raw offset selector;
                  "WideChar" -> Audit.unalignedWideChar raw offset selector;
                  "Int" -> Audit.unalignedInt raw offset selector;
                  "Word" -> Audit.unalignedWord raw offset selector;
                  "Float" -> Audit.unalignedFloat raw offset selector;
                  "Double" -> Audit.unalignedDouble raw offset selector;
                  "Int16" -> Audit.unalignedInt16 raw offset selector;
                  "Int32" -> Audit.unalignedInt32 raw offset selector;
                  "Int64" -> Audit.unalignedInt64 raw offset selector;
                  "Word16" -> Audit.unalignedWord16 raw offset selector;
                  "Word32" -> Audit.unalignedWord32 raw offset selector;
                  "Word64" -> Audit.unalignedWord64 raw offset selector;
                  "Addr" -> Audit.unalignedAddr "first"# "second"# offset selector;
                  "StablePtr" -> Audit.unalignedStablePtr first# second# offset selector;
                  _ -> 0# })
            in putStrLn (intercalate "\t" (kind : rawText : offsetText : map (show . at) [0..5]))
        _ -> error "Expected scalar type, raw bits, byte offset"
  mapM_ answer (lines input)
  freeStablePtr first
  freeStablePtr second
