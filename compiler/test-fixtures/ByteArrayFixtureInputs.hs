-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module ByteArrayFixtureInputs (sizeInputs, resizeInputs, runFixture) where

import Data.Bits (finiteBitSize)
import qualified Data.Set as Set
import System.Environment (getArgs)

baseInputs :: [(Int, Int)]
baseInputs = [(code * 0x123456789abcdef, code) | code <- [0 .. 17 * 17 - 1]]

edgeSeeds :: [Int]
edgeSeeds = [minBound, -257, -1, 0, 255, 256, maxBound]

sizeInputs :: [(Int, Int)]
sizeInputs = Set.toAscList . Set.fromList $
  baseInputs ++ [(seed, 8 + 17 * 16) | seed <- [0 .. 255]] ++
  [(seed, code) | seed <- edgeSeeds,
                 code <- [minBound, -1, 0, 16, 17, 288, 1023, 4095, 4096, maxBound]]

resizeInputs :: [(Int, Int)]
resizeInputs = Set.toAscList . Set.fromList $
  baseInputs ++ [(seed, old + 17 * new) | seed <- [0 .. 255],
                 (old, new) <- [(0, 16), (16, 0), (16, 8), (8, 16), (8, 8)]] ++
  [(seed, code) | seed <- edgeSeeds, code <- [minBound, -1, 0, maxBound]]

-- Fixture production only: Kotlin owns the independent semantic model and
-- validates the complete ordered corpus before interpreting/compiling Core.
runFixture :: [(Int, Int)] -> [String] -> (String -> Int -> Int -> Int) -> IO ()
runFixture inputs names call = do
  if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int" else pure ()
  args <- getArgs
  case args of
    ["--inputs"] -> mapM_ (putStrLn . pair) inputs
    [] -> mapM_ (\name -> mapM_ (emit name) inputs) names
    _ -> error "Expected no arguments or --inputs"
  where
    pair (raw, code) = show raw ++ "\t" ++ show code
    emit name values@(raw, code) =
      putStrLn (name ++ "\t" ++ pair values ++ "\t" ++ show (call name raw code))
