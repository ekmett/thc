-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalDirectoryPathsAudit where
import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Word (Word64(W64#))
import Foreign.C.Types (CChar)

type RemoveCall = Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type ReadCall = Addr# -> Addr# -> Word64# -> State# RealWorld -> (# State# RealWorld, Int32# #)

pathRemoveDirectory :: RemoveCall -> Addr# -> Int#
pathRemoveDirectory call path = case call path realWorld# of (# _, status #) -> int32ToInt# status

executableReadlink :: ReadCall -> Addr# -> Addr# -> Word64# -> Int#
executableReadlink call path output capacity = case call path output capacity realWorld# of
  (# _, status #) -> int32ToInt# status

nativePathRemoveDirectory :: RemoveCall -> Ptr CChar -> IO Int
nativePathRemoveDirectory call (Ptr path) = IO (\state ->
  case call path state of (# next, status #) -> (# next, I# (int32ToInt# status) #))

nativeExecutableReadlink :: ReadCall -> Ptr CChar -> Ptr () -> Word64 -> IO Int
nativeExecutableReadlink call (Ptr path) (Ptr output) (W64# capacity) = IO (\state ->
  case call path output capacity state of (# next, status #) -> (# next, I# (int32ToInt# status) #))
