-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module UnixLibcAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type DescriptorCall = Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type EnvironmentCall = Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)

-- The producer supplies genuine installed unix FCallIds after checking exact
-- GHC type equality. No replacement foreign import or implementation is used.
unixClose, unixDup, unixIsatty :: DescriptorCall -> Int# -> Int#
unixClose call fd = case call (intToInt32# fd) realWorld# of
  (# _, result #) -> int32ToInt# result
unixDup = unixClose
unixIsatty = unixClose

unixGetenv :: EnvironmentCall -> Addr# -> Addr#
unixGetenv call name = case call name realWorld# of (# _, result #) -> result

nativeUnixClose, nativeUnixDup, nativeUnixIsatty :: DescriptorCall -> Int -> IO Int
nativeUnixClose call (I# fd) = IO (\s -> case call (intToInt32# fd) s of
  (# next, result #) -> (# next, I# (int32ToInt# result) #))
nativeUnixDup = nativeUnixClose
nativeUnixIsatty = nativeUnixClose

nativeUnixGetenv :: EnvironmentCall -> Ptr CChar -> IO (Ptr CChar)
nativeUnixGetenv call (Ptr name) = IO (\s -> case call name s of
  (# next, result #) -> (# next, Ptr result #))
