-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnboxedTuples #-}
module OriginalIconvAudit (originalLocale, originalIconvOpen, originalIconvClose, originalIconv) where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#), Int64(I64#))
import GHC.Internal.Word (Word64(W64#))
import GHC.Internal.Foreign.C.Types (CInt(..), CLong(..), CSize(..))

-- The preparer replaces these typed test-consumer FCallIds with the actual
-- installed Iconv FCallIds after checking GHC type equality. No target aliases.
foreign import ccall unsafe "localeEncoding" locale :: IO (Ptr a)
foreign import ccall unsafe "hs_iconv_open" open :: Ptr a -> Ptr b -> IO CLong
foreign import ccall unsafe "hs_iconv_close" close :: CLong -> IO CInt
foreign import ccall unsafe "hs_iconv" convert :: CLong -> Ptr a -> Ptr b -> Ptr c -> Ptr d -> IO CSize

originalLocale :: Int# -> Addr#
originalLocale offset = runRW# (\s -> case locale of { IO f ->
  case f s of { (# _, Ptr result #) -> plusAddr# result offset } })
originalIconvOpen :: Addr# -> Addr# -> Int#
originalIconvOpen to from = runRW# (\s -> case open (Ptr to) (Ptr from) of { IO f ->
  case f s of { (# _, CLong (I64# result) #) -> int64ToInt# result } })
originalIconvClose :: Int# -> Int#
originalIconvClose handle = runRW# (\s -> case close (CLong (I64# (intToInt64# handle))) of { IO f ->
  case f s of { (# _, CInt (I32# result) #) -> int32ToInt# result } })
originalIconv :: Int# -> Addr# -> Addr# -> Addr# -> Addr# -> Int#
originalIconv handle input inputCount output outputCount = runRW# (\s ->
  case convert (CLong (I64# (intToInt64# handle))) (Ptr input) (Ptr inputCount) (Ptr output) (Ptr outputCount) of { IO f ->
  case f s of { (# _, CSize (W64# result) #) -> word2Int# (word64ToWord# result) } })
