-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalPosixStatAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 internal library APIs
--
-- Compiler fixture for original posix stat audit Core and metadata.
module OriginalPosixStatAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import qualified GHC.Internal.System.Posix.Internals as P

originalFstat :: Int# -> Addr# -> Int#
originalFstat fd address = runRW# (\state ->
  case P.c_fstat (CInt (I32# (intToInt32# fd))) (Ptr address) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })

originalFstatErrno :: Int# -> Addr# -> Int#
originalFstatErrno fd address = runRW# (\state ->
  case P.c_fstat (CInt (I32# (intToInt32# fd))) (Ptr address) of { IO action ->
  case action state of { (# next, _ #) ->
  case getErrno of { IO observe ->
  case observe next of { (# _, Errno (CInt (I32# result)) #) -> int32ToInt# result } } } })
