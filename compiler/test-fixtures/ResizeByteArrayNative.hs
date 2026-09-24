-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module ResizeByteArrayNative (main) where

import ByteArrayFixtureInputs (resizeInputs, runFixture)
import GHC.Exts (Int(I#))
import qualified ResizeByteArrayAudit as P

call :: String -> Int -> Int -> Int
call name (I# raw) (I# code) = I# (case name of
  "resizedBytes" -> P.resizedBytes raw code
  "resizedTwiceWrites" -> P.resizedTwiceWrites raw code
  _ -> error "Unknown mutable byte-array resize entry")

main :: IO ()
main = runFixture resizeInputs ["resizedBytes", "resizedTwiceWrites"] call
