-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ForeignFunctionInterface #-}
module ScalarAgain (repeatInt32) where
import GHC.Exts
import GHC.Int (Int32(..))
import GHC.IO (IO(..))
foreign import ccall unsafe "thc_io_v1_close" repeated :: Int32 -> IO Int32
{-# NOINLINE repeatInt32 #-}
repeatInt32 :: Int# -> Int#
repeatInt32 x = case repeated (I32# (intToInt32# x)) of
  IO f -> case runRW# f of (# _, I32# y #) -> int32ToInt# y
