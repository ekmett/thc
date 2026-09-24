-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import Control.Exception (evaluate)
import Control.Monad (unless)
import Data.Maybe (catMaybes, isJust)
import OriginalStackAudit

-- Frame contents/counts depend on the native compiler and RTS. Compare repeated
-- decoding of the SAME native snapshot, never native frames against JVM frames.
main :: IO ()
main = do
  snapshot <- captureOriginal
  first <- decodeOriginal snapshot
  second <- decodeOriginal snapshot
  let count = length first
      rendered = catMaybes (map renderOriginal first)
      provenance = length (filter (isJust . snd) first)
  unless (count > 0) (fail "original native stack must not be empty")
  unless (length second == count && map snd first == map snd second &&
          map renderOriginal first == map renderOriginal second)
    (fail "original native snapshot changed between decodes")
  unless (length rendered <= count && provenance <= count)
    (fail "original native rendering/provenance count exceeds frame count")
  characters <- evaluate (sum (map length rendered))
  putStrLn ("native-shape\t" ++ show count ++ "\t" ++ show provenance ++
            "\t" ++ show (length rendered) ++ "\t" ++ show characters)
