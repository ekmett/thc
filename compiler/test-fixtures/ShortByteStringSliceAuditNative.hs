-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#))
import qualified ShortByteStringSliceAudit as P
call name (I# seed) (I# count) (I# side) (I# selector) = I# (case name of
  "takeCase" -> P.takeCase seed count side selector
  "dropCase" -> P.dropCase seed count side selector
  "splitCase" -> P.splitCase seed count side selector
  _ -> error "unknown slice entry")
emit [name,seed,count,side,selector] = putStrLn
  (unwordsTab [name,seed,count,side,selector,show (call name (read seed) (read count) (read side) (read selector))])
emit _ = error "invalid slice row"
unwordsTab [] = ""
unwordsTab [x] = x
unwordsTab (x:xs) = x ++ "\t" ++ unwordsTab xs
main = getContents >>= mapM_ (emit . words) . lines
