-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#))
import ShowIntAudit
main :: IO ()
main = getContents >>= mapM_ (run . words) . lines
 where
  run ["showChecksum", input, index] = case (read input, read index) of
    (I# x, I# i) -> emit "showChecksum" (I# x) (I# i) (I# (showChecksum x))
  run ["showCharacter", input, index] = case (read input, read index) of
    (I# x, I# i) -> emit "showCharacter" (I# x) (I# i) (I# (showCharacter x i))
  run _ = error "malformed native Show input"
  emit name input index result = putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show index ++ "\t" ++ show result)
