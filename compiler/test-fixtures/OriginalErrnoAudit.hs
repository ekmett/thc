-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalErrnoAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno, resetErrno)

-- Import the installed wrappers: resetErrno retains its original private
-- __hscore_set_errno FCallId. No replacement foreign import enters guest Core.
-- The dynamic input prevents a shared CAF at this small runRW# test boundary.
originalResetErrno :: Int# -> Int#
originalResetErrno input = runRW# (\state ->
  case resetErrno of { IO reset ->
  case reset state of { (# next, () #) ->
  case getErrno of { IO observe ->
  case observe next of { (# _, Errno (CInt (I32# value)) #) ->
    input +# int32ToInt# value } } } })
