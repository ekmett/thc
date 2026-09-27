-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalPathStatAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type StatCall = Addr# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- The producer substitutes unchanged installed FCallIds after exact GHC type
-- equality, then both serializes and natively compiles those same declarations.
pathStat, pathLstat, unixPathLstat :: StatCall -> Addr# -> Addr# -> Int#
pathStat call path destination = case call path destination realWorld# of
  (# _, status #) -> int32ToInt# status
pathLstat = pathStat
unixPathLstat = pathStat

nativePathStat, nativePathLstat, nativeUnixPathLstat :: StatCall -> Ptr CChar -> Ptr () -> IO Int
nativePathStat call (Ptr path) (Ptr destination) = IO (\state ->
  case call path destination state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))
nativePathLstat = nativePathStat
nativeUnixPathLstat = nativePathStat
