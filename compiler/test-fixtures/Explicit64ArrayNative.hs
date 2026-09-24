-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import qualified Explicit64ArrayAudit as Audit
import Data.List (intercalate)

main :: IO ()
main = getContents >>= mapM_ answer . lines
  where
    answer row = case read row of
      I# bits -> putStrLn (row ++ "\t" ++ intercalate "\t"
        [show (I# (Audit.explicit64ArrayBits bits selector)) | I# selector <- [0..3]])
