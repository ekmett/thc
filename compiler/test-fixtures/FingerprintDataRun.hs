-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- Execute the installed GHC implementation; this fixture does not replace MD5.
module FingerprintDataRun (main) where

import GHC.Internal.Fingerprint (fingerprintData)
import GHC.Internal.Fingerprint.Type (Fingerprint(..))
import GHC.Internal.Ptr (Ptr(..))

-- A wrong digest enters a genuine recursive thunk. Native GHC reports
-- NonTermination and THC reports a blackhole, so a mismatch cannot pass.
mismatch :: ()
mismatch = mismatch

main :: IO ()
main = do
  Fingerprint high low <- fingerprintData (Ptr "abc"#) 3
  if high == 0x900150983cd24fb0 && low == 0xd6963f7d28e17f72
    then pure ()
    else mismatch `seq` pure ()
