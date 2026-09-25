-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalHandleReadinessAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.System.Posix.Internals (c_isatty)

-- Import the original GHC declaration so the exported Core carries its
-- real foreign-call descriptor and result representation.
{-# OPAQUE originalIsTerminal #-}
originalIsTerminal :: Int# -> Int#
originalIsTerminal descriptor = runRW# (\state ->
  case c_isatty (CInt (I32# (intToInt32# descriptor))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

{-# OPAQUE originalIsTerminalErrno #-}
originalIsTerminalErrno :: Int# -> Int#
originalIsTerminalErrno descriptor = runRW# (\state ->
  case c_isatty (CInt (I32# (intToInt32# descriptor))) of { IO action ->
  case action state of { (# next, CInt (I32# result) #) ->
  case int32ToInt# result ==# 0# of
    1# -> case getErrno of { IO observe ->
          case observe next of { (# _, Errno (CInt (I32# value)) #) -> int32ToInt# value } }
    _ -> 0# } })
