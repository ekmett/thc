-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main (main) where

import FourWayAggregateFields
import GHC.Exts (Int(I#), Int#, Word64#)
import GHC.Word (Word64(W64#))

entries :: [(String, Int# -> Word64# -> Word64#)]
entries =
  [ ("roundtripPayload", roundtripPayload), ("roundtripTag", roundtripTag)
  , ("formatTag", formatTag), ("defaultArm", defaultArm)
  , ("retainedFirst", retainedFirst), ("retainedSecond", retainedSecond)
  , ("retainedTags", retainedTags), ("residualProducer", residualProducer)
  , ("residualConsumer", residualConsumer)
  ]

-- Include noncanonical selectors and repeated alternatives in one process.
selectors :: [Int]
selectors = [-5, -1, 0, 1, 2, 3, 4, 5, 6, 7, 0, 3, 1, 2, 2, 0]

payloads :: [Word64]
payloads = [0, 1, 4294967295, 4294967296, 9223372036854775808, 18446744073709551615]

main :: IO ()
main = mapM_ observe entries
  where
    observe :: (String, Int# -> Word64# -> Word64#) -> IO ()
    observe (name, entry) = mapM_ (withSelector name entry) (zip [0 :: Int ..] selectors)
    withSelector :: String -> (Int# -> Word64# -> Word64#) -> (Int, Int) -> IO ()
    withSelector name entry (ordinal, selector@(I# selector#)) =
      mapM_ (withPayload name entry ordinal selector selector#) payloads
    withPayload :: String -> (Int# -> Word64# -> Word64#) -> Int -> Int -> Int# -> Word64 -> IO ()
    withPayload name entry ordinal selector selector# bits@(W64# bits#) =
      putStrLn (name ++ "\t" ++ show ordinal ++ "\t" ++ show selector ++ "\t" ++
        show bits ++ "\t" ++ show (W64# (entry selector# bits#)))
