-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#), Word(W#))
import qualified Int32ByteOffsetAudit as Audit
import Data.List (intercalate)

main :: IO ()
main = getContents >>= mapM_ answer . lines
  where
    answer row = case words row of
      [signedText,unsignedText] -> case (read signedText, read unsignedText) of
        (I# signed,W# unsigned) ->
          putStrLn (signedText ++ "\t" ++ unsignedText ++ "\t" ++
            intercalate "\t" [show (I# (Audit.int32ByteOffsetValues signed unsigned selector)) |
              I# selector <- [0..3]])
      _ -> error "Expected signed and unsigned integer inputs"
