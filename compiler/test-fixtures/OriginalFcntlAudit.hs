-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalFcntlAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int64(I64#))
import qualified GHC.Internal.System.Posix.Internals as P

-- Actual unchanged installed declarations supply every FCall and its width.
originalAppend, originalCreat, originalNoctty, originalNonblock,
  originalRdonly, originalRdwr, originalWronly, originalGetfl, originalSetfl :: Int# -> Int#
originalAppend extra = case fromIntegral P.o_APPEND :: Int of I# value -> value +# extra
originalCreat extra = case fromIntegral P.o_CREAT :: Int of I# value -> value +# extra
originalNoctty extra = case fromIntegral P.o_NOCTTY :: Int of I# value -> value +# extra
originalNonblock extra = case fromIntegral P.o_NONBLOCK :: Int of I# value -> value +# extra
originalRdonly extra = case fromIntegral P.o_RDONLY :: Int of I# value -> value +# extra
originalRdwr extra = case fromIntegral P.o_RDWR :: Int of I# value -> value +# extra
originalWronly extra = case fromIntegral P.o_WRONLY :: Int of I# value -> value +# extra
originalGetfl extra = case fromIntegral P.const_f_getfl :: Int of I# value -> value +# extra
originalSetfl extra = case fromIntegral P.const_f_setfl :: Int of I# value -> value +# extra

originalGetFlags :: Int# -> Int#
originalGetFlags fd = runRW# (\state ->
  case P.c_fcntl_read (fromIntegral (I# fd)) P.const_f_getfl of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Int of I# result -> result } })

originalSetFlags :: Int# -> Int64# -> Int#
originalSetFlags fd flags = runRW# (\state ->
  case P.c_fcntl_write (fromIntegral (I# fd)) P.const_f_setfl (fromIntegral (I64# flags)) of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Int of I# result -> result } })
