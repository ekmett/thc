-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalStdioSeekAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#), Int64(I64#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Types (COff(..))
import GHC.Internal.System.Posix.Internals (c_lseek)

originalSeek :: Int# -> Int64# -> Int# -> Int64#
originalSeek fd displacement whence = runRW# (\state ->
  case c_lseek (CInt (I32# (intToInt32# fd))) (COff (I64# displacement))
               (CInt (I32# (intToInt32# whence))) of { IO action ->
  case action state of { (# _, COff (I64# result) #) -> result } })

-- The errno observation is deliberately meaningful only for failure.
originalSeekErrno :: Int# -> Int64# -> Int# -> Int#
originalSeekErrno fd displacement whence = runRW# (\state ->
  case c_lseek (CInt (I32# (intToInt32# fd))) (COff (I64# displacement))
               (CInt (I32# (intToInt32# whence))) of { IO action ->
  case action state of { (# next, COff (I64# result) #) ->
  case eqInt64# result (intToInt64# (-1#)) of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> -2# } })
