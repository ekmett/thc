-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalTermiosAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import qualified GHC.Internal.System.Posix.Internals as P

-- Only unchanged installed declarations. No new foreign imports, terminal
-- syscalls, file descriptors, or replacement Haskell implementations.
originalTermiosSize :: Int# -> Int#
originalTermiosSize extra = case P.sizeof_termios of I# value -> value +# extra
originalEcho :: Int# -> Int#
originalEcho extra = case fromIntegral P.const_echo :: Int of I# value -> value +# extra
originalIcanon :: Int# -> Int#
originalIcanon extra = case fromIntegral P.const_icanon :: Int of I# value -> value +# extra
originalVmin :: Int# -> Int#
originalVmin extra = case fromIntegral P.const_vmin :: Int of I# value -> value +# extra
originalVtime :: Int# -> Int#
originalVtime extra = case fromIntegral P.const_vtime :: Int of I# value -> value +# extra
originalTcsanow :: Int# -> Int#
originalTcsanow extra = case fromIntegral P.const_tcsanow :: Int of I# value -> value +# extra
originalSigsetSize :: Int# -> Int#
originalSigsetSize extra = case P.sizeof_sigset_t of I# value -> value +# extra
originalSigttou :: Int# -> Int#
originalSigttou extra = case fromIntegral P.const_sigttou :: Int of I# value -> value +# extra
originalSigBlock :: Int# -> Int#
originalSigBlock extra = case fromIntegral P.const_sig_block :: Int of I# value -> value +# extra
originalSigSetmask :: Int# -> Int#
originalSigSetmask extra = case fromIntegral P.const_sig_setmask :: Int of I# value -> value +# extra

originalLflag :: Addr# -> Word#
originalLflag address = runRW# (\state -> case P.c_lflag (Ptr address) of { IO action ->
  case action state of { (# _, value #) -> case fromIntegral value :: Word of W# result -> result } })
originalPokeLflag :: Addr# -> Word# -> Int#
originalPokeLflag address value = runRW# (\state -> case P.poke_c_lflag (Ptr address) (fromIntegral (W# value)) of { IO action ->
  case action state of { (# _, () #) -> 0# } })
originalCC :: Addr# -> Addr#
originalCC address = runRW# (\state -> case P.ptr_c_cc (Ptr address) of { IO action ->
  case action state of { (# _, Ptr result #) -> result } })
