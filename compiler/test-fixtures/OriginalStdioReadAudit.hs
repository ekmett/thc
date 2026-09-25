-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalStdioReadAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#), Int64(I64#))
import GHC.Internal.Word (Word64(W64#))
import GHC.Internal.Foreign.C.Types (CInt(..), CSize(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Types (CSsize(..))
import GHC.Internal.System.Posix.Internals (c_read, c_safe_read)

-- These are consumers of the installed GHC bindings, not replacement imports.
originalRead :: Int# -> Addr# -> Int# -> Word# -> Int#
originalRead fd base offset count = runRW# (\state ->
  case c_read (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# _, CSsize (I64# result) #) -> int64ToInt# result } })

originalSafeRead :: Int# -> Addr# -> Int# -> Word# -> Int#
originalSafeRead fd base offset count = runRW# (\state ->
  case c_safe_read (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# _, CSsize (I64# result) #) -> int64ToInt# result } })

originalReadErrno :: Int# -> Addr# -> Int# -> Word# -> Int#
originalReadErrno fd base offset count = runRW# (\state ->
  case c_read (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# next, CSsize (I64# result) #) ->
  case int64ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> negateInt# (int64ToInt# result +# 2#) } })

originalSafeReadErrno :: Int# -> Addr# -> Int# -> Word# -> Int#
originalSafeReadErrno fd base offset count = runRW# (\state ->
  case c_safe_read (CInt (I32# (intToInt32# fd))) (Ptr (plusAddr# base offset))
         (CSize (W64# (wordToWord64# count))) of { IO action ->
  case action state of { (# next, CSsize (I64# result) #) ->
  case int64ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> negateInt# (int64ToInt# result +# 2#) } })
