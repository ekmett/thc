-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE BangPatterns, MagicHash #-}
module Main (main) where

import Control.Exception (evaluate)
import Data.Bits ((.&.))
import Data.Word (Word64)
import GHC.Clock (getMonotonicTimeNSec)
import GHC.Exts (Int(I#), Int#)
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)
import qualified THC.Fixtures as T
import qualified THC.MapWorkload as M

entries :: [(String, Int# -> Int#)]
entries =
  [ ("sumLoop", T.sumLoop), ("fib", T.fib), ("captured", T.captured)
  , ("exact", T.exact), ("under", T.under), ("over", T.over)
  , ("unknown", T.unknown), ("shared", T.shared)
  , ("lazyArgument", T.lazyArgument), ("lazyField", T.lazyField)
  , ("recursiveCaf", T.recursiveCaf), ("caseList", T.caseList)
  , ("multiModule", T.multiModule)
  , ("cacheSaturation", T.cacheSaturation), ("mutualTail", T.mutualTail)
  , ("selfMutualTail", T.selfMutualTail)
  , ("capturedChangingEnv", T.capturedChangingEnv)
  , ("localMutualClosures", T.localMutualClosures)
  , ("nestedCaptureThunk", T.nestedCaptureThunk)
  , ("mapAggregate", M.mapAggregate)
  ]

row :: String -> (Int# -> Int#) -> Int -> String
row name f input@(I# n) = name ++ "\t" ++ show input ++ "\t" ++ show (I# (f n))

-- Vary the primitive input while choosing the target only once. The accumulator
-- uses native machine-Int wraparound, matching JVM long addition on this 64-bit host.
bench :: (Int# -> Int#) -> Int -> Int -> Int
bench f count base = go 0 0 where
  go !i !acc
    | i == count = acc
    | otherwise = case base + (i .&. 15) of
        I# n -> case f n of
          r -> go (i + 1) (acc + I# r)

runBenchmark :: String -> (Int# -> Int#) -> Int -> Int -> IO ()
runBenchmark name !f !count !base = do
  start <- getMonotonicTimeNSec
  checksum <- evaluate (bench f count base)
  end <- getMonotonicTimeNSec
  putStrLn (name ++ "\t" ++ show count ++ "\t" ++ show base
            ++ "\t" ++ show checksum ++ "\t" ++ show (end - start))

-- Keep each timed batch as a fresh dynamic computation. In particular, do not
-- let full laziness hoist the checksum for the repeated 16-input cycle into a
-- shared thunk. The changing global index crosses this opaque call boundary.
{-# OPAQUE steadyBatch #-}
steadyBatch :: (Int# -> Int#) -> Int -> Int -> Int
steadyBatch f base initialIndex = go 0 0 where
  go !i !acc
    | i == 256 = acc
    | otherwise = case base + ((initialIndex + i) .&. 15) of
        I# n -> case f n of
          r -> go (i + 1) (acc + I# r)

-- Durations are restricted to less than half the unsigned counter range, so
-- subtraction from the deadline also handles a wrapping Word64 clock.
deadlineReached :: Word64 -> Word64 -> Bool
deadlineReached now deadline = now - deadline < 9223372036854775808

steadyWindow :: (Int# -> Int#) -> Int -> Word64 -> Int
             -> IO (Int, Int, Int, Word64)
steadyWindow !f !base !duration !initialIndex = do
  start <- getMonotonicTimeNSec
  let !deadline = start + duration
      loop !index !repetitions !checksum = do
        batchChecksum <- evaluate (steadyBatch f base index)
        let !nextIndex = index + 256
            !nextRepetitions = repetitions + 256
            !nextChecksum = checksum + batchChecksum
        end <- getMonotonicTimeNSec
        if deadlineReached end deadline
          then pure (nextIndex, nextRepetitions, nextChecksum, end - start)
          else loop nextIndex nextRepetitions nextChecksum
  loop initialIndex 0 0

secondsToNanoseconds :: String -> Maybe Word64
secondsToNanoseconds input = do
  seconds <- readMaybe input :: Maybe Double
  if isNaN seconds || isInfinite seconds || seconds <= 0 || seconds >= 9223372036.854776
    then Nothing
    else let nanoseconds = round (seconds * 1000000000) :: Integer
         in if nanoseconds > 0 && nanoseconds < 9223372036854775808
              then Just (fromInteger nanoseconds)
              else Nothing

runSteadyBenchmark :: String -> (Int# -> Int#) -> Word64 -> Word64
                   -> Int -> Int -> IO ()
runSteadyBenchmark name !f !warmDuration !sampleDuration !samples !base = do
  (firstIndex, _, _, _) <- steadyWindow f base warmDuration 0
  let sample !number !index
        | number > samples = pure ()
        | otherwise = do
            (nextIndex, repetitions, checksum, elapsed) <-
              steadyWindow f base sampleDuration index
            putStrLn (name ++ "\t" ++ show number ++ "\t" ++ show repetitions
                      ++ "\t" ++ show base ++ "\t" ++ show checksum
                      ++ "\t" ++ show elapsed)
            sample (number + 1) nextIndex
  sample 1 firstIndex

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--bench-steady", name, warmSeconds, sampleSeconds, sampleCount, inputBase] ->
      case (lookup name entries, secondsToNanoseconds warmSeconds,
            secondsToNanoseconds sampleSeconds, readMaybe sampleCount, readMaybe inputBase) of
        (Just f, Just warm, Just duration, Just samples, Just base) | samples > 0 ->
          runSteadyBenchmark name f warm duration samples base
        _ -> die "unknown entry or invalid steady benchmark parameters (positive finite seconds and sample count required)"
    ["--bench", name, repetitions, inputBase] ->
      case (lookup name entries, readMaybe repetitions, readMaybe inputBase) of
        (Just f, Just count, Just base) | count >= 0 -> runBenchmark name f count base
        _ -> die "unknown entry, invalid Int, or negative repetition count"
    [] -> mapM_ (\(name, f) -> mapM_ (putStrLn . row name f) [-3, 0, 1, 2, 7, 10, 20]) (filter ((/= "mapAggregate") . fst) entries)
    [name, input] -> case (lookup name entries, readMaybe input) of
      (Just f, Just n) -> putStrLn (row name f n)
      _ -> die "unknown entry or invalid machine Int"
    _ -> die "usage: native-oracle [ENTRY INPUT | --bench ENTRY REPETITIONS INPUTBASE | --bench-steady ENTRY WARM_SECONDS SAMPLE_SECONDS SAMPLES INPUTBASE]"
