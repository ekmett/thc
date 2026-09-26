-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module UnixWaitStatusAudit where

import GHC.Exts

type WaitMacro = Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- The producer specializes these with the actual installed unix FCallIds,
-- then compiles those same original declarations with native GHC as the oracle.
testWait :: WaitMacro -> Int# -> Int#
testWait call value = case call (intToInt32# value) realWorld# of
  (# _, result #) -> int32ToInt# result

waitWCOREDUMP, waitWSTOPSIG, waitWIFSTOPPED, waitWTERMSIG,
  waitWIFSIGNALED, waitWEXITSTATUS, waitWIFEXITED :: WaitMacro -> Int# -> Int#
waitWCOREDUMP = testWait
waitWSTOPSIG = testWait
waitWIFSTOPPED = testWait
waitWTERMSIG = testWait
waitWIFSIGNALED = testWait
waitWEXITSTATUS = testWait
waitWIFEXITED = testWait

nativeWaitWCOREDUMP, nativeWaitWSTOPSIG, nativeWaitWIFSTOPPED, nativeWaitWTERMSIG,
  nativeWaitWIFSIGNALED, nativeWaitWEXITSTATUS, nativeWaitWIFEXITED :: WaitMacro -> Int -> Int
nativeWaitWCOREDUMP call (I# value) = I# (waitWCOREDUMP call value)
nativeWaitWSTOPSIG call (I# value) = I# (waitWSTOPSIG call value)
nativeWaitWIFSTOPPED call (I# value) = I# (waitWIFSTOPPED call value)
nativeWaitWTERMSIG call (I# value) = I# (waitWTERMSIG call value)
nativeWaitWIFSIGNALED call (I# value) = I# (waitWIFSIGNALED call value)
nativeWaitWEXITSTATUS call (I# value) = I# (waitWEXITSTATUS call value)
nativeWaitWIFEXITED call (I# value) = I# (waitWIFEXITED call value)
