-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FloatingAddressAudit where

import GHC.Exts

-- One call retains both native-endian stores and all four typed reads. The
-- selected result is the raw IEEE bit pattern, not a numeric conversion.
{-# OPAQUE floatingAddressBits #-}
floatingAddressBits :: Word# -> Word# -> Int# -> Word#
floatingAddressBits floatBits doubleBits selector = runRW# (\s0 ->
  case newPinnedByteArray# 64# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case writeFloatOffAddr# base 4# (castWord32ToFloat# (wordToWord32# floatBits)) s3 of { s4 ->
    case writeDoubleOffAddr# base 3# (castWord64ToDouble# (wordToWord64# doubleBits)) s4 of { s5 ->
      case selector of {
        0# -> (# s5, word32ToWord# (castFloatToWord32# (indexFloatOffAddr# (plusAddr# base 20#) (-1#))) #);
        1# -> case readFloatOffAddr# (plusAddr# base 8#) 2# s5 of { (# s6, value #) ->
          (# s6, word32ToWord# (castFloatToWord32# value) #) };
        2# -> (# s5, word64ToWord# (castDoubleToWord64# (indexDoubleOffAddr# (plusAddr# base 32#) (-1#))) #);
        _  -> case readDoubleOffAddr# (plusAddr# base 32#) (-1#) s5 of { (# s6, value #) ->
          (# s6, word64ToWord# (castDoubleToWord64# value) #) }
      }
    } }) of { (# _, result #) -> result }
  } } })
