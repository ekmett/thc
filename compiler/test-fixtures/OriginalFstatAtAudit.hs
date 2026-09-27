-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalFstatAtAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type FstatAtCall = Int32# -> Addr# -> Addr# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- The actual installed safe CAPI declaration is substituted after exact type
-- equality. Only the two outer Haskell Int arguments undergo CInt narrowing.
pathFstatAt :: FstatAtCall -> Int# -> Addr# -> Addr# -> Int# -> Int#
pathFstatAt call descriptor path destination flags =
  case call (intToInt32# descriptor) path destination (intToInt32# flags) realWorld# of
    (# _, status #) -> int32ToInt# status

nativePathFstatAt :: FstatAtCall -> Int -> Ptr CChar -> Ptr () -> Int -> IO Int
nativePathFstatAt call (I# descriptor) (Ptr path) (Ptr destination) (I# flags) = IO (\state ->
  case call (intToInt32# descriptor) path destination (intToInt32# flags) state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))
