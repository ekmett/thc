-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module WordArrayAudit where

import GHC.Exts

-- WordArray# indices select native machine words (8 bytes here). Byte writes
-- at 7/8 cross the element boundary. Keep both pre/post-write observations.
aliasWordBytes :: Int# -> Int#
aliasWordBytes seed = runRW# (\s0 ->
  case newByteArray# 16# s0 of { (# s1, a #) ->
  case writeWordArray# a 0# (int2Word# seed) s1 of { s2 ->
  case writeWordArray# a 1# (int2Word# (seed `xorI#` 0x55aa55aa55aa55aa#)) s2 of { s3 ->
  case readWordArray# a 0# s3 of { (# s4, before #) ->
  case writeWord8Array# a 7# (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case writeWord8Array# a 8# (wordToWord8# (int2Word# (seed +# 37#))) s5 of { s6 ->
  case readWordArray# a 0# s6 of { (# s7, first #) ->
  case readWordArray# a 1# s7 of { (# s8, second #) ->
  case unsafeFreezeByteArray# a s8 of { (# _, frozen #) ->
    word2Int# (before `timesWord#` 3## `plusWord#`
      (first `timesWord#` 5##) `plusWord#` (second `timesWord#` 7##) `plusWord#`
      (indexWordArray# frozen 0# `timesWord#` 11##) `plusWord#`
      (indexWordArray# frozen 1# `timesWord#` 13##) `plusWord#`
      (word8ToWord# (indexWord8Array# frozen 0#) `timesWord#` 17##) `plusWord#`
      (word8ToWord# (indexWord8Array# frozen 7#) `timesWord#` 19##) `plusWord#`
      (word8ToWord# (indexWord8Array# frozen 8#) `timesWord#` 23##) `plusWord#`
      (word8ToWord# (indexWord8Array# frozen 15#) `timesWord#` 29##))
  } } } } } } } } })
