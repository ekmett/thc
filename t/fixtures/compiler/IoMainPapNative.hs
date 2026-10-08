-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; corresponding compiled fixture module
--
-- Native GHC observer for the io main pap fixture.
module Main where

import qualified Control.Exception as E
import qualified IoMainPapAudit as P
import System.Exit (exitFailure)

-- This driver is native-only; none of the printing or exception library enters
-- the exported guest closure. Do not inspect the deliberately opaque payload.
check :: String -> Bool -> IO a -> IO ()
check name expectFailure action = do
  result <- E.try (action >> pure ()) :: IO (Either E.SomeException ())
  case result of
    Left _ | expectFailure -> putStrLn (name ++ "\tthrows")
    Right () | not expectFailure -> putStrLn (name ++ "\tcompleted")
    _ -> exitFailure

main :: IO ()
main = do
  check "goodMain" False P.goodMain
  check "badMain" True P.badMain
  check "nonUnitMain" False P.nonUnitMain
  check "unitBottomMain" False P.unitBottomMain
  check "functionMain" False P.functionMain
  check "lazyMain" False P.lazyMain
