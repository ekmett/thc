-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalPosixStatAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import qualified GHC.Internal.System.Posix.Internals as P

-- These are the unchanged installed declarations, not new foreign imports.
originalStatSize :: Int# -> Int#
originalStatSize extra = case P.sizeof_stat of I# size -> size +# extra

originalStatDev :: Addr# -> Int#
originalStatDev address = runRW# (\state -> case P.st_dev (Ptr address) of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Int of I# result -> result } })
originalStatIno :: Addr# -> Int#
originalStatIno address = runRW# (\state -> case P.st_ino (Ptr address) of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Int of I# result -> result } })
originalStatMode :: Addr# -> Int#
originalStatMode address = runRW# (\state -> case P.st_mode (Ptr address) of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Int of I# result -> result } })
originalStatLength :: Addr# -> Int#
originalStatLength address = runRW# (\state -> case P.st_size (Ptr address) of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Int of I# result -> result } })

originalStatTypes :: Int# -> Int#
originalStatTypes raw = case fromIntegral (I# raw) of
  mode -> case (fromIntegral (P.c_s_isreg mode) + 2 * fromIntegral (P.c_s_ischr mode) +
        4 * fromIntegral (P.c_s_isblk mode) + 8 * fromIntegral (P.c_s_isdir mode) +
        16 * fromIntegral (P.c_s_isfifo mode) + 32 * fromIntegral (P.c_s_issock mode)) :: Int of
    I# result -> result

originalFstat :: Int# -> Addr# -> Int#
originalFstat fd address = runRW# (\state ->
  case P.c_fstat (CInt (I32# (intToInt32# fd))) (Ptr address) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

originalFstatErrno :: Int# -> Addr# -> Int#
originalFstatErrno fd address = runRW# (\state ->
  case P.c_fstat (CInt (I32# (intToInt32# fd))) (Ptr address) of { IO action ->
  case action state of { (# next, _ #) ->
  case getErrno of { IO observe ->
  case observe next of { (# _, Errno (CInt (I32# result)) #) -> int32ToInt# result } } } })
