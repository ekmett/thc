-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module MutableByteArraySizeNative (main) where

import ByteArrayFixtureInputs (runFixture, sizeInputs)
import GHC.Exts (Int(I#))
import qualified MutableByteArraySizeAudit as P

call :: String -> Int -> Int -> Int
call name (I# raw) (I# code) = I# (case name of
  "freshSize" -> P.freshSize raw code
  "pureSize" -> P.pureSize raw code
  "resizedSizes" -> P.resizedSizes raw code
  "pureAfterResize" -> P.pureAfterResize raw code
  "orderedSize" -> P.orderedSize raw code
  _ -> error "Unknown mutable byte-array size entry")

main :: IO ()
main = runFixture sizeInputs
  ["freshSize", "pureSize", "resizedSizes", "pureAfterResize", "orderedSize"] call
