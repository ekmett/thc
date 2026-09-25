-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnboxedTuples #-}
module OriginalFdReadyAudit (originalReadySafe, originalReadyUnsafe) where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#), Int64(I64#))
import GHC.Internal.Word (Word8(W8#))
import GHC.Internal.Foreign.C.Types (CInt(..), CBool(..))

-- Native controls call GHC's actual linked C implementation. For the guest
-- fixture, the Haskell preparer replaces these template FCallIds with the
-- genuine installed IO.FD FCallIds after GHC type/ABI equality checks. This is
-- an explicitly adapted scalar consumer, not an unchanged Handle/FD method.
foreign import ccall safe "fdReady"
  readySafe :: CInt -> CBool -> Int64 -> CBool -> IO CInt
foreign import ccall unsafe "fdReady"
  readyUnsafe :: CInt -> CBool -> Int64 -> CBool -> IO CInt

originalReadySafe :: Int# -> Int# -> Int# -> Int# -> Int#
originalReadySafe fd writing milliseconds socket = runRW# (\state ->
  case readySafe (CInt (I32# (intToInt32# fd)))
        (CBool (W8# (wordToWord8# (int2Word# writing)))) (I64# (intToInt64# milliseconds))
        (CBool (W8# (wordToWord8# (int2Word# socket)))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

originalReadyUnsafe :: Int# -> Int# -> Int# -> Int# -> Int#
originalReadyUnsafe fd writing milliseconds socket = runRW# (\state ->
  case readyUnsafe (CInt (I32# (intToInt32# fd)))
        (CBool (W8# (wordToWord8# (int2Word# writing)))) (I64# (intToInt64# milliseconds))
        (CBool (W8# (wordToWord8# (int2Word# socket)))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })
