-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalSigprocmaskAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 internal library APIs
--
-- Compiler fixture for original sigprocmask audit Core and metadata.
module OriginalSigprocmaskAudit where

import GHC.Exts
import GHC.IO (IO(..))
import qualified GHC.Internal.System.Posix.Internals as P

-- Exact installed original declaration, not a substitute foreign import.
originalSigprocmask :: Int# -> Addr# -> Addr# -> Int#
originalSigprocmask how set old = runRW# (\state -> case P.c_sigprocmask (fromIntegral (I# how)) (Ptr set) (Ptr old) of { IO action ->
  case action state of { (# _, result #) -> case fromIntegral result :: Int of I# value -> value } })
