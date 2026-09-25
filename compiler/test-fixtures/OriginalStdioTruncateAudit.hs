-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalStdioTruncateAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#), Int64(I64#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Types (COff(..))
import GHC.Internal.System.Posix.Internals (c_ftruncate)

originalTruncate :: Int# -> Int64# -> Int#
originalTruncate fd length = runRW# (\state ->
  case c_ftruncate (CInt (I32# (intToInt32# fd))) (COff (I64# length)) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

originalTruncateErrno :: Int# -> Int64# -> Int#
originalTruncateErrno fd length = runRW# (\state ->
  case c_ftruncate (CInt (I32# (intToInt32# fd))) (COff (I64# length)) of { IO action ->
  case action state of { (# next, CInt (I32# result) #) ->
  case int32ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> -2# } })
