-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import UnsafeEqualityAudit
import GHC.Exts
main :: IO ()
main = mapM_ run [(name, x) | name <- ["primitiveCase", "liftedCase", "tupleCase", "lazyCase", "unusedCase", "unusedBottomCase", "nestedCase"],
                             x <- [minBound, -4097, -1, 0, 1, 4097, maxBound]]
 where
  run (name, I# x) = let value = case name of
                          "primitiveCase" -> primitiveCase x
                          "liftedCase" -> liftedCase x
                          "tupleCase" -> tupleCase x
                          "lazyCase" -> lazyCase x
                          "unusedCase" -> unusedCase x
                          "unusedBottomCase" -> unusedBottomCase x
                          "nestedCase" -> nestedCase x
                          _ -> 0#
                     in putStrLn (name ++ "\t" ++ show (I# x) ++ "\t" ++ show (I# value))
