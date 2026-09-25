-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalGmpAudit where

import GHC.Exts
import GHC.IO (IO(..))
import qualified GHC.Internal.Bignum.Backend.GMP as G

-- These calls import the installed original declarations. No replacement FFI
-- or reconstructed foreign Id is used. runRW# supplies the original State
-- operand while keeping the test's host result scalar. The native oracle uses
-- the original IO declarations directly to sequence writes and observations.
originalAdd :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> ByteArray# -> Int#
  -> Word#
originalAdd out left nl right nr = runRW# (\state -> case G.c_mpn_add out left nl right nr of
  IO action -> case action state of (# _, W# value #) -> value)

originalAddWord :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> Word#
  -> Word#
originalAddWord out input count word = runRW# (\state -> case G.c_mpn_add_1 out input count word of
  IO action -> case action state of (# _, W# value #) -> value)

originalCmp :: ByteArray# -> ByteArray# -> Int# -> Int#
originalCmp = G.c_mpn_cmp

originalDivWord :: MutableByteArray# RealWorld -> Int# -> ByteArray# -> Int# -> Word#
  -> Word#
originalDivWord out fractional input count word = runRW# (\state -> case G.c_mpn_divrem_1 out fractional input count word of
  IO action -> case action state of (# _, W# value #) -> value)

originalModWord :: ByteArray# -> Int# -> Word# -> Word#
originalModWord = G.c_mpn_mod_1

originalMul :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> ByteArray# -> Int#
  -> Word#
originalMul out left nl right nr = runRW# (\state -> case G.c_mpn_mul out left nl right nr of
  IO action -> case action state of (# _, W# value #) -> value)

originalMulWord :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> Word#
  -> Word#
originalMulWord out input count word = runRW# (\state -> case G.c_mpn_mul_1 out input count word of
  IO action -> case action state of (# _, W# value #) -> value)

originalSub :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> ByteArray# -> Int#
  -> Word#
originalSub out left nl right nr = runRW# (\state -> case G.c_mpn_sub out left nl right nr of
  IO action -> case action state of (# _, W# value #) -> value)

originalQuotRem :: MutableByteArray# RealWorld -> MutableByteArray# RealWorld -> Int#
  -> ByteArray# -> Int# -> ByteArray# -> Int# -> Int#
originalQuotRem quotient remainder fractional numerator nn divisor dn = runRW# (\state ->
  case G.c_mpn_tdiv_qr quotient remainder fractional numerator nn divisor dn of
    IO action -> case action state of (# _, () #) -> 0#)

originalQuot :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> ByteArray# -> Int#
  -> Int#
originalQuot quotient numerator nn divisor dn = runRW# (\state -> case G.c_mpn_tdiv_q quotient numerator nn divisor dn of
  IO action -> case action state of (# _, () #) -> 0#)

originalRem :: MutableByteArray# RealWorld -> ByteArray# -> Int# -> ByteArray# -> Int#
  -> Int#
originalRem remainder numerator nn divisor dn = runRW# (\state -> case G.c_mpn_tdiv_r remainder numerator nn divisor dn of
  IO action -> case action state of (# _, () #) -> 0#)
