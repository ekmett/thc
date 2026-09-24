-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import qualified Control.Exception as E
import qualified IoMainPapAudit as P
import System.Exit (exitFailure)

-- This driver is native-only; none of the printing or exception library enters
-- the exported guest closure. Do not inspect the deliberately opaque payload.
check :: String -> Bool -> IO () -> IO ()
check name expectFailure action = do
  result <- E.try action :: IO (Either E.SomeException ())
  case result of
    Left _ | expectFailure -> putStrLn (name ++ "\tthrows")
    Right () | not expectFailure -> putStrLn (name ++ "\tcompleted")
    _ -> exitFailure

main :: IO ()
main = do
  check "goodMain" False P.goodMain
  check "badMain" True P.badMain
