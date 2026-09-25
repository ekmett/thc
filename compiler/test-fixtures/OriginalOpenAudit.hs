-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalOpenAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Word (Word32(W32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.System.Posix.Types (CMode(..))
import qualified GHC.Internal.System.Posix.Internals as P

-- Unchanged installed declarations, including rejected safety controls. No FFI
-- import is introduced here, and neither wrapper changes flags or pathname bytes.
originalOpen :: Addr# -> Int# -> Word# -> Int#
originalOpen path flags mode = runRW# (\state ->
  case P.c_open (Ptr path) (CInt (I32# (intToInt32# flags))) (CMode (W32# (wordToWord32# mode))) of { IO action ->
  case action state of { (# _, CInt (I32# value) #) -> int32ToInt# value } })

originalOpenSafe :: Addr# -> Int# -> Word# -> Int#
originalOpenSafe path flags mode = runRW# (\state ->
  case P.c_safe_open_ (Ptr path) (CInt (I32# (intToInt32# flags))) (CMode (W32# (wordToWord32# mode))) of { IO action ->
  case action state of { (# _, CInt (I32# value) #) -> int32ToInt# value } })

originalOpenInterruptible :: Addr# -> Int# -> Word# -> Int#
originalOpenInterruptible path flags mode = runRW# (\state ->
  case P.c_interruptible_open_ (Ptr path) (CInt (I32# (intToInt32# flags))) (CMode (W32# (wordToWord32# mode))) of { IO action ->
  case action state of { (# _, CInt (I32# value) #) -> int32ToInt# value } })
