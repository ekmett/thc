-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalTimeClockAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Int (Int32(..))
import System.Posix.Types (CClockId(..))

type ClockId = State# RealWorld -> (# State# RealWorld, Int32# #)
type ClockCall = Int32# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)

originalId :: ClockId -> Int# -> Int#
originalId call _ = case call realWorld# of (# _, value #) -> int32ToInt# value
{-# NOINLINE originalId #-}

originalConstant :: CClockId -> Int# -> Int#
originalConstant (CClockId (I32# value)) _ = int32ToInt# value
{-# NOINLINE originalConstant #-}

nativeConstant :: CClockId -> Int -> IO Int
nativeConstant (CClockId (I32# value)) _ = pure (I# (int32ToInt# value))
{-# NOINLINE nativeConstant #-}

originalTime, originalResolution :: ClockCall -> Int# -> Addr# -> Int#
originalTime call clock pointer = case call (intToInt32# clock) pointer realWorld# of
  (# _, value #) -> int32ToInt# value
originalResolution call clock pointer = case call (intToInt32# clock) pointer realWorld# of
  (# _, value #) -> int32ToInt# value
{-# NOINLINE originalTime #-}
{-# NOINLINE originalResolution #-}

nativeId :: ClockId -> Int -> IO Int
nativeId call _ = IO (\state -> case call state of (# next, value #) -> (# next, I# (int32ToInt# value) #))
nativeTime, nativeResolution :: ClockCall -> Int -> Ptr () -> IO Int
nativeTime call (I# clock) (Ptr pointer) = IO (\state -> case call (intToInt32# clock) pointer state of
  (# next, value #) -> (# next, I# (int32ToInt# value) #))
nativeResolution call (I# clock) (Ptr pointer) = IO (\state -> case call (intToInt32# clock) pointer state of
  (# next, value #) -> (# next, I# (int32ToInt# value) #))
{-# NOINLINE nativeId #-}
{-# NOINLINE nativeTime #-}
{-# NOINLINE nativeResolution #-}
