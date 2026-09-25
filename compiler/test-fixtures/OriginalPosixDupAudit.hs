-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalPosixDupAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Internals (c_dup, c_dup2)

-- These are the installed original declarations, not new FFI imports. Native
-- and JVM callers supply private descriptors; no host descriptor is passed to THC.
originalDup :: Int# -> Int#
originalDup fd = runRW# (\state ->
  case c_dup (CInt (I32# (intToInt32# fd))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

originalDup2 :: Int# -> Int# -> Int#
originalDup2 fd target = runRW# (\state ->
  case c_dup2 (CInt (I32# (intToInt32# fd))) (CInt (I32# (intToInt32# target))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

originalDupErrno :: Int# -> Int#
originalDupErrno fd = runRW# (\state ->
  case c_dup (CInt (I32# (intToInt32# fd))) of { IO action ->
  case action state of { (# next, CInt (I32# result) #) ->
  case int32ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> -2# } })

originalDup2Errno :: Int# -> Int# -> Int#
originalDup2Errno fd target = runRW# (\state ->
  case c_dup2 (CInt (I32# (intToInt32# fd))) (CInt (I32# (intToInt32# target))) of { IO action ->
  case action state of { (# next, CInt (I32# result) #) ->
  case int32ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> -2# } })
