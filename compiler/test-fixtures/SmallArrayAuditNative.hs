-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import SmallArrayAudit (smallComposite, safeSliceComposite)

main :: IO ()
main = getContents >>= mapM_ emit . lines
  where
    emit text = case read text of
      I# input -> putStrLn (text ++ "\t" ++ show (I# (smallComposite input)) ++
        "\t" ++ show (I# (safeSliceComposite input)))
