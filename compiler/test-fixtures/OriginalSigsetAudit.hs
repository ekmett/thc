-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalSigsetAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import qualified GHC.Internal.System.Posix.Internals as P

-- Original installed CAPI declarations only; no foreign imports or masks.
originalSigEmpty :: Addr# -> Int#
originalSigEmpty address = runRW# (\state -> case P.c_sigemptyset (Ptr address) of { IO action ->
  case action state of { (# _, result #) -> case fromIntegral result :: Int of I# value -> value } })

originalSigAdd :: Addr# -> Int# -> Int#
originalSigAdd address signal = runRW# (\state -> case P.c_sigaddset (Ptr address) (fromIntegral (I# signal)) of { IO action ->
  case action state of { (# _, result #) -> case fromIntegral result :: Int of I# value -> value } })
