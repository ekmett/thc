-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalRtsLocksAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Word (Word64(W64#))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))

-- Ordinary higher-order consumers: NO foreign declaration or substitute body.
-- The preparer applies these functions to the actual private installed FCallId,
-- checks exact GHC type equality, then runs GHC's own simplifier/serializer.
type LockCall = Word64# -> Word64# -> Word64# -> Int32# -> State# RealWorld
  -> (# State# RealWorld, Int32# #)
type UnlockCall = Word64# -> State# RealWorld -> (# State# RealWorld, Int32# #)

originalLock :: LockCall -> Word# -> Word# -> Word# -> Int# -> Int#
originalLock call key dev ino writing = runRW# (\s ->
  case call (wordToWord64# key) (wordToWord64# dev) (wordToWord64# ino) (intToInt32# writing) s of
    (# _, result #) -> int32ToInt# result)

originalUnlock :: UnlockCall -> Word# -> Int#
originalUnlock call key = runRW# (\s ->
  case call (wordToWord64# key) s of (# _, result #) -> int32ToInt# result)

nativeLock :: LockCall -> Word64 -> Word64 -> Word64 -> CInt -> IO CInt
nativeLock call (W64# key) (W64# dev) (W64# ino) (CInt (I32# writing)) = IO (\s ->
  case call key dev ino writing s of (# next, result #) -> (# next, CInt (I32# result) #))

nativeUnlock :: UnlockCall -> Word64 -> IO CInt
nativeUnlock call (W64# key) = IO (\s ->
  case call key s of (# next, result #) -> (# next, CInt (I32# result) #))
