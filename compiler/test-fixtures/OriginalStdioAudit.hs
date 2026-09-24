-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalStdioAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#), Int64(I64#))
import GHC.Internal.Word (Word64(W64#))
import GHC.Internal.Foreign.C.Types (CInt(..), CSize(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Types (CSsize(..))
import GHC.Internal.System.Posix.Internals (c_write, c_safe_write)

-- These consumers import the ORIGINAL installed GHC bindings, not fresh foreign
-- imports or a replacement IO implementation. Their unfoldings must retain the
-- original capi wrapper targets and __hscore_get_errno in every exported copy.
-- The explicit LP64 constructors intentionally reject other native ABIs.
-- runRW# provides a small scalar fixture boundary, not a public pure write API.
originalWrite :: Int# -> Addr# -> Int# -> Word# -> Int#
originalWrite fd base offset count = runRW# (\state ->
  case c_write (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# _, CSsize (I64# result) #) -> int64ToInt# result } })

originalSafeWrite :: Int# -> Addr# -> Int# -> Word# -> Int#
originalSafeWrite fd base offset count = runRW# (\state ->
  case c_safe_write (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# _, CSsize (I64# result) #) -> int64ToInt# result } })

-- Errno is meaningful only on failure. A successful write returns a negative
-- sentinel -(count+2), so accidentally succeeding cannot masquerade as EBADF.
originalWriteErrno :: Int# -> Addr# -> Int# -> Word# -> Int#
originalWriteErrno fd base offset count = runRW# (\state ->
  case c_write (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# next, CSsize (I64# result) #) ->
  case int64ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> negateInt# (int64ToInt# result +# 2#) } })

originalSafeWriteErrno :: Int# -> Addr# -> Int# -> Word# -> Int#
originalSafeWriteErrno fd base offset count = runRW# (\state ->
  case c_safe_write (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# next, CSsize (I64# result) #) ->
  case int64ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> negateInt# (int64ToInt# result +# 2#) } })
