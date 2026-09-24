-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import BigNatLiteralAudit
import GHC.Exts (Int(I#))
runEntry :: String -> Int -> Int -> Int
runEntry name (I# x) (I# index) = case name of
  "integerRoundTrip" -> I# (integerRoundTrip x index)
  "naturalRoundTrip" -> I# (naturalRoundTrip x index)
  "integerLiteral" -> I# (integerLiteral x index)
  "naturalLiteral" -> I# (naturalLiteral x index)
  "magnitudeSize" -> I# (magnitudeSize x index)
  "magnitudeByte" -> I# (magnitudeByte x index)
  "magnitudeWord" -> I# (magnitudeWord x index)
  "magnitudeSign" -> I# (magnitudeSign x index)
  _ -> error "entry"
main :: IO ()
main = getContents >>= mapM_ run . lines
 where
  run line = case words line of
   [name,s,i] -> putStrLn (name ++ "\t" ++ s ++ "\t" ++ i ++ "\t" ++ show (runEntry name (read s) (read i)))
   _ -> error "request"
