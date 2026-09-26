-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import System.Exit (die)
import Text.Read (readMaybe)

-- An independent native scalar-lane oracle, not native 512-bit instructions.
-- Select operand signs before the fused operation; NaN payloads are unspecified.
floatFma :: Int -> Float -> Float -> Float -> Float
floatFma operation (F# x) (F# y) (F# z) = F# (case operation of
  0 -> fmaddFloat# x y z; 1 -> fmsubFloat# x y z
  2 -> fnmaddFloat# x y z; _ -> fnmsubFloat# x y z)
doubleFma :: Int -> Double -> Double -> Double -> Double
doubleFma operation (D# x) (D# y) (D# z) = D# (case operation of
  0 -> fmaddDouble# x y z; 1 -> fmsubDouble# x y z
  2 -> fnmaddDouble# x y z; _ -> fnmsubDouble# x y z)

floatBits :: Int -> Word -> Word -> Word -> Int -> Word
floatBits operation (W# xb) (W# yb) (W# zb) lane =
  let x = F# (castWord32ToFloat# (wordToWord32# xb))
      y = F# (castWord32ToFloat# (wordToWord32# yb))
      z = F# (castWord32ToFloat# (wordToWord32# zb))
      base = [(x,y,z),(y,z,x),(z,x,y),(x,y,-z),(-x,y,z),(x,-y,z),(-y,z,-x),(z,-x,-y)]
      (a,b,c) = (base ++ map (\(u,v,w) -> (-u,-v,-w)) base) !! lane
  in case floatFma operation a b c of F# result -> W# (word32ToWord# (castFloatToWord32# result))
doubleBits :: Int -> Word -> Word -> Word -> Int -> Word
doubleBits operation (W# xb) (W# yb) (W# zb) lane =
  let x = D# (castWord64ToDouble# (wordToWord64# xb))
      y = D# (castWord64ToDouble# (wordToWord64# yb))
      z = D# (castWord64ToDouble# (wordToWord64# zb))
      base = [(x,y,z),(y,z,-x),(z,x,y),(-x,y,-z)]
      (a,b,c) = (base ++ map (\(u,v,w) -> (-u,-v,-w)) base) !! lane
  in case doubleFma operation a b c of D# result -> W# (word64ToWord# (castDoubleToWord64# result))

main :: IO ()
main = getContents >>= mapM_ row . lines
  where
    entries prefix = zip [prefix ++ operation ++ "Case" | operation <- ["Add","Sub","NegAdd","NegSub"]] [0..3]
    row line = case words line of
      [name,a,b,c,d] -> case (readMaybe a, readMaybe b, readMaybe c, readMaybe d) of
        (Just x, Just y, Just z, Just lane)
          | Just operation <- lookup name (entries "huge"), lane >= 0, lane < 16 ->
            putStrLn (unwords [name,a,b,c,d,show (floatBits operation x y z lane)])
          | Just operation <- lookup name (entries "doubleHuge"), lane >= 0, lane < 8 ->
            putStrLn (unwords [name,a,b,c,d,show (doubleBits operation x y z lane)])
        _ -> die "Malformed scalar-lane FMA request"
      _ -> die "Malformed scalar-lane FMA request"
