-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalStdioCloseAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Internals (c_close)

-- Consume the installed ghc-internal declaration. The native driver supplies
-- only a private file descriptor, never one of its own result streams.
originalClose :: Int# -> Int#
originalClose fd = runRW# (\state ->
  case c_close (CInt (I32# (intToInt32# fd))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

-- Errno is meaningful only after failure. A successful close returns -2 so
-- its result cannot be mistaken for EBADF on either supported host.
originalCloseErrno :: Int# -> Int#
originalCloseErrno fd = runRW# (\state ->
  case c_close (CInt (I32# (intToInt32# fd))) of { IO action ->
  case action state of { (# next, CInt (I32# result) #) ->
  case int32ToInt# result ==# -1# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> -2# } })
