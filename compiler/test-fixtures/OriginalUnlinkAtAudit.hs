-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalUnlinkAtAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type UnlinkAtCall = Int32# -> Addr# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- Specialize the actual installed safe FCallId without changing its declaration.
-- Both outer Ints narrow exactly as GHC's original CInt wrapper does.
pathUnlinkAt :: UnlinkAtCall -> Int# -> Addr# -> Int# -> Int#
pathUnlinkAt call descriptor path flags =
  case call (intToInt32# descriptor) path (intToInt32# flags) realWorld# of
    (# _, status #) -> int32ToInt# status

nativePathUnlinkAt :: UnlinkAtCall -> Int -> Ptr CChar -> Int -> IO Int
nativePathUnlinkAt call (I# descriptor) (Ptr path) (I# flags) = IO (\state ->
  case call (intToInt32# descriptor) path (intToInt32# flags) state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))
