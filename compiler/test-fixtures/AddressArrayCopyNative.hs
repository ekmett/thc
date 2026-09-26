-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import Data.List (intercalate)
import qualified AddressArrayCopyAudit as A

emit :: (Int# -> Int# -> Int# -> Int# -> Int# -> Int#) -> Int -> Int -> Int -> Int -> [Int]
emit f (I# seed) (I# from) (I# to) (I# count) = [I# (f seed from to count field) | I# field <- [0..15]]

main :: IO ()
main = getContents >>= mapM_ row . lines
  where
    row line = case words line of
      [name,seed,from,to,count] -> do
        let function = case name of
              "addrToArray" -> A.addrToArray
              "arrayToAddr" -> A.arrayToAddr
              "mutableArrayToAddr" -> A.mutableArrayToAddr
              _ -> error "Unknown address/array copy root"
        putStrLn (intercalate "\t" ([name,seed,from,to,count] ++
          map show (emit function (read seed) (read from) (read to) (read count))))
      _ -> error "Malformed address/array copy request"
