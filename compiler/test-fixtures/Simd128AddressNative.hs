-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts (Int(I#), Int#)
import Simd128AddressAudit
import System.Exit (die)
import Text.Read (readMaybe)

entries :: [(String, Int# -> Int# -> Int#)]
entries = [("int8X16IndexPacked", int8X16IndexPacked)
  , ("int8X16IndexScalar", int8X16IndexScalar)
  , ("int8X16ReadPacked", int8X16ReadPacked)
  , ("int8X16ReadScalar", int8X16ReadScalar)
  , ("int8X16WritePacked", int8X16WritePacked)
  , ("int8X16WriteScalar", int8X16WriteScalar)
  , ("word8X16IndexPacked", word8X16IndexPacked)
  , ("word8X16IndexScalar", word8X16IndexScalar)
  , ("word8X16ReadPacked", word8X16ReadPacked)
  , ("word8X16ReadScalar", word8X16ReadScalar)
  , ("word8X16WritePacked", word8X16WritePacked)
  , ("word8X16WriteScalar", word8X16WriteScalar)
  , ("int16X8IndexPacked", int16X8IndexPacked)
  , ("int16X8IndexScalar", int16X8IndexScalar)
  , ("int16X8ReadPacked", int16X8ReadPacked)
  , ("int16X8ReadScalar", int16X8ReadScalar)
  , ("int16X8WritePacked", int16X8WritePacked)
  , ("int16X8WriteScalar", int16X8WriteScalar)
  , ("word16X8IndexPacked", word16X8IndexPacked)
  , ("word16X8IndexScalar", word16X8IndexScalar)
  , ("word16X8ReadPacked", word16X8ReadPacked)
  , ("word16X8ReadScalar", word16X8ReadScalar)
  , ("word16X8WritePacked", word16X8WritePacked)
  , ("word16X8WriteScalar", word16X8WriteScalar)
  , ("int64X2IndexPacked", int64X2IndexPacked)
  , ("int64X2IndexScalar", int64X2IndexScalar)
  , ("int64X2ReadPacked", int64X2ReadPacked)
  , ("int64X2ReadScalar", int64X2ReadScalar)
  , ("int64X2WritePacked", int64X2WritePacked)
  , ("int64X2WriteScalar", int64X2WriteScalar)
  , ("word64X2IndexPacked", word64X2IndexPacked)
  , ("word64X2IndexScalar", word64X2IndexScalar)
  , ("word64X2ReadPacked", word64X2ReadPacked)
  , ("word64X2ReadScalar", word64X2ReadScalar)
  , ("word64X2WritePacked", word64X2WritePacked)
  , ("word64X2WriteScalar", word64X2WriteScalar)]

main :: IO ()
main = getContents >>= mapM_ emit . lines
  where
    emit row = case words row of
      [name, rawSeed, rawOffset] -> case (lookup name entries, readMaybe rawSeed, readMaybe rawOffset) of
        (Just f, Just (I# seed), Just (I# offset)) ->
          putStrLn (name ++ "\t" ++ rawSeed ++ "\t" ++ rawOffset ++ "\t" ++ show (I# (f seed offset)))
        _ -> die "Invalid SIMD128 oracle row"
      _ -> die "Expected ENTRY SEED OFFSET"
