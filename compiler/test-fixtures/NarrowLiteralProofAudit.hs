-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module NarrowLiteralProofAudit where

import GHC.Exts

-- Direct ByteArray write operands retain genuine narrow literal syntax in
-- both export stages. Tests project only their representation metadata; these
-- sources and the native oracle remain unchanged.

writeInt8Literal :: Int# -> Int#
writeInt8Literal selector = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case writeInt8Array# a 0# (intToInt8# (-128#)) s1 of { s2 ->
  case writeInt8Array# a 1# (intToInt8# (-1#)) s2 of { s3 ->
  case writeInt8Array# a 2# (intToInt8# (0#)) s3 of { s4 ->
  case writeInt8Array# a 3# (intToInt8# (127#)) s4 of { s5 ->
  case unsafeFreezeByteArray# a s5 of { (# _, frozen #) ->
    int8ToInt# (indexInt8Array# frozen (andI# selector 3#))
  } } } } } })

writeWord8Literal :: Int# -> Int#
writeWord8Literal selector = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case writeWord8Array# a 0# (wordToWord8# 0##) s1 of { s2 ->
  case writeWord8Array# a 1# (wordToWord8# 127##) s2 of { s3 ->
  case writeWord8Array# a 2# (wordToWord8# 128##) s3 of { s4 ->
  case writeWord8Array# a 3# (wordToWord8# 255##) s4 of { s5 ->
  case unsafeFreezeByteArray# a s5 of { (# _, frozen #) ->
    word2Int# (word8ToWord# (indexWord8Array# frozen (andI# selector 3#)))
  } } } } } })

writeInt16Literal :: Int# -> Int#
writeInt16Literal selector = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case writeInt16Array# a 0# (intToInt16# (-32768#)) s1 of { s2 ->
  case writeInt16Array# a 1# (intToInt16# (-1#)) s2 of { s3 ->
  case writeInt16Array# a 2# (intToInt16# (0#)) s3 of { s4 ->
  case writeInt16Array# a 3# (intToInt16# (32767#)) s4 of { s5 ->
  case unsafeFreezeByteArray# a s5 of { (# _, frozen #) ->
    int16ToInt# (indexInt16Array# frozen (andI# selector 3#))
  } } } } } })

writeWord16Literal :: Int# -> Int#
writeWord16Literal selector = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case writeWord16Array# a 0# (wordToWord16# 0##) s1 of { s2 ->
  case writeWord16Array# a 1# (wordToWord16# 32767##) s2 of { s3 ->
  case writeWord16Array# a 2# (wordToWord16# 32768##) s3 of { s4 ->
  case writeWord16Array# a 3# (wordToWord16# 65535##) s4 of { s5 ->
  case unsafeFreezeByteArray# a s5 of { (# _, frozen #) ->
    word2Int# (word16ToWord# (indexWord16Array# frozen (andI# selector 3#)))
  } } } } } })

writeInt32Literal :: Int# -> Int#
writeInt32Literal selector = runRW# (\s0 ->
  case newByteArray# 16# s0 of { (# s1, a #) ->
  case writeInt32Array# a 0# (intToInt32# (-2147483648#)) s1 of { s2 ->
  case writeInt32Array# a 1# (intToInt32# (-1#)) s2 of { s3 ->
  case writeInt32Array# a 2# (intToInt32# (0#)) s3 of { s4 ->
  case writeInt32Array# a 3# (intToInt32# (2147483647#)) s4 of { s5 ->
  case unsafeFreezeByteArray# a s5 of { (# _, frozen #) ->
    int32ToInt# (indexInt32Array# frozen (andI# selector 3#))
  } } } } } })

writeWord32Literal :: Int# -> Int#
writeWord32Literal selector = runRW# (\s0 ->
  case newByteArray# 16# s0 of { (# s1, a #) ->
  case writeWord32Array# a 0# (wordToWord32# 0##) s1 of { s2 ->
  case writeWord32Array# a 1# (wordToWord32# 2147483647##) s2 of { s3 ->
  case writeWord32Array# a 2# (wordToWord32# 2147483648##) s3 of { s4 ->
  case writeWord32Array# a 3# (wordToWord32# 4294967295##) s4 of { s5 ->
  case unsafeFreezeByteArray# a s5 of { (# _, frozen #) ->
    word2Int# (word32ToWord# (indexWord32Array# frozen (andI# selector 3#)))
  } } } } } })
