-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module WideCharAddressAudit (wideCharRoundtrip) where

import GHC.Exts

-- The same four-byte cell is reached with a negative element offset from an
-- interior Addr# and with a positive index from the allocation base.
{-# OPAQUE wideCharRoundtrip #-}
wideCharRoundtrip :: Int# -> Int#
wideCharRoundtrip code = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, bytes #) ->
  case mutableByteArrayContents# bytes of { base ->
  case plusAddr# base 12# of { interior ->
  case writeWideCharOffAddr# interior (-1#) (chr# code) s1 of { s2 ->
  case readWideCharOffAddr# interior (-1#) s2 of { (# s3, readBack #) ->
  case indexWideCharOffAddr# base 2# of { indexed ->
    ord# readBack *# 4294967296# +# ord# indexed
  } } } } } })
