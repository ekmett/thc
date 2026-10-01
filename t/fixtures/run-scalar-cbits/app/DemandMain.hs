-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- | Native GHC and THC must observe one constructor-backed C state.
module Main (main) where

import Demand

main :: IO ()
main = do
  raw >>= print
  rawSafe >>= print
  raw >>= print
