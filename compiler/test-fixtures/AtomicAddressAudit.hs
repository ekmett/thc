-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module AtomicAddressAudit where
import GHC.Exts

-- Direct address consumers exercise managed, raw and native carriers through
-- exactly the same exported Core as the native-width composite below.
{-# OPAQUE atomicAddressNumericAt #-}
atomicAddressNumericAt :: Addr# -> Int# -> Word# -> Word# -> Word#
atomicAddressNumericAt cell operation operand replacement = runRW# (\s ->
  case (case operation of {
    0# -> atomicReadWordAddr# cell s;
    1# -> case atomicWriteWordAddr# cell operand s of { s1 -> (# s1, 0## #) };
    2# -> atomicExchangeWordAddr# cell operand s;
    3# -> atomicCasWordAddr# cell operand replacement s;
    4# -> case atomicCasWord8Addr# cell (wordToWord8# operand) (wordToWord8# replacement) s of { (# s1, old #) -> (# s1, word8ToWord# old #) };
    5# -> case atomicCasWord16Addr# cell (wordToWord16# operand) (wordToWord16# replacement) s of { (# s1, old #) -> (# s1, word16ToWord# old #) };
    6# -> case atomicCasWord32Addr# cell (wordToWord32# operand) (wordToWord32# replacement) s of { (# s1, old #) -> (# s1, word32ToWord# old #) };
    7# -> case atomicCasWord64Addr# cell (wordToWord64# operand) (wordToWord64# replacement) s of { (# s1, old #) -> (# s1, word64ToWord# old #) };
    8# -> fetchAddWordAddr# cell operand s;
    9# -> fetchSubWordAddr# cell operand s;
    10# -> fetchAndWordAddr# cell operand s;
    11# -> fetchNandWordAddr# cell operand s;
    12# -> fetchOrWordAddr# cell operand s;
    13# -> fetchXorWordAddr# cell operand s;
    _ -> atomicReadWordAddr# cell s
  }) of { (# _, old #) -> old })

{-# OPAQUE atomicAddressPointerAt #-}
atomicAddressPointerAt :: Addr# -> Int# -> Addr# -> Addr# -> Addr#
atomicAddressPointerAt cell operation expected desired = runRW# (\s ->
  case (case operation of {
    0# -> atomicExchangeAddrAddr# cell desired s;
    _ -> atomicCasAddrAddr# cell expected desired s
  }) of { (# _, old #) -> old })

-- All operations use an interior aligned location surrounded by sentinels.
-- The selector observes both the returned old value and the entire final word,
-- so narrow CAS must preserve the neighboring bytes.
{-# OPAQUE atomicAddressNumeric #-}
atomicAddressNumeric :: Int# -> Word# -> Word# -> Word# -> Int# -> Word#
atomicAddressNumeric operation initial operand replacement selector = runRW# (\s0 ->
  case newPinnedByteArray# 24# s0 of { (# s1, bytes #) ->
  case mutableByteArrayContents# bytes of { base ->
  case keepAlive# bytes s1 (\s2 ->
    case writeWordOffAddr# base 0# 81985529216486895## s2 of { s3 ->
    case writeWordOffAddr# base 2# 18364758544493064720## s3 of { sx ->
    case writeWordOffAddr# base 1# initial sx of { s4 ->
    case plusAddr# base 8# of { cell ->
    case (case operation of {
      0# -> atomicReadWordAddr# cell s4;
      1# -> case atomicWriteWordAddr# cell operand s4 of { s5 -> (# s5, 0## #) };
      2# -> atomicExchangeWordAddr# cell operand s4;
      3# -> atomicCasWordAddr# cell operand replacement s4;
      4# -> case atomicCasWord8Addr# cell (wordToWord8# operand) (wordToWord8# replacement) s4 of { (# s5, old #) -> (# s5, word8ToWord# old #) };
      5# -> case atomicCasWord16Addr# cell (wordToWord16# operand) (wordToWord16# replacement) s4 of { (# s5, old #) -> (# s5, word16ToWord# old #) };
      6# -> case atomicCasWord32Addr# cell (wordToWord32# operand) (wordToWord32# replacement) s4 of { (# s5, old #) -> (# s5, word32ToWord# old #) };
      7# -> case atomicCasWord64Addr# cell (wordToWord64# operand) (wordToWord64# replacement) s4 of { (# s5, old #) -> (# s5, word64ToWord# old #) };
      8# -> fetchAddWordAddr# cell operand s4;
      9# -> fetchSubWordAddr# cell operand s4;
      10# -> fetchAndWordAddr# cell operand s4;
      11# -> fetchNandWordAddr# cell operand s4;
      12# -> fetchOrWordAddr# cell operand s4;
      13# -> fetchXorWordAddr# cell operand s4;
      _ -> atomicReadWordAddr# cell s4
    }) of { (# s5, old #) ->
    case readWordOffAddr# cell 0# s5 of { (# s6, final #) ->
    case readWordOffAddr# base 0# s6 of { (# s7, before #) ->
    case readWordOffAddr# base 2# s7 of { (# s8, after #) ->
      (# s8, case selector of { 0# -> old; 1# -> final; 2# -> before; _ -> after } #)
    } } } } } } } }) of { (# _, result #) -> result }
  } })

{-# OPAQUE atomicAddressPointer #-}
atomicAddressPointer :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
atomicAddressPointer operation initial expected desired selector = runRW# (\s0 ->
  case newPinnedByteArray# 64# s0 of { (# s1, bytes #) ->
  case mutableByteArrayContents# bytes of { base ->
  case keepAlive# bytes s1 (\s2 ->
    case (case initial of { -1# -> nullAddr#; _ -> plusAddr# base initial }) of { initialAddr ->
    case (case expected of { -1# -> nullAddr#; _ -> plusAddr# base expected }) of { expectedAddr ->
    case (case desired of { -1# -> nullAddr#; _ -> plusAddr# base desired }) of { desiredAddr ->
    case writeAddrOffAddr# base 1# initialAddr s2 of { s3 ->
    case (case operation of {
      0# -> atomicExchangeAddrAddr# (plusAddr# base 8#) desiredAddr s3;
      _ -> atomicCasAddrAddr# (plusAddr# base 8#) expectedAddr desiredAddr s3
    }) of { (# s4, old #) ->
    case readAddrOffAddr# base 1# s4 of { (# s5, final #) ->
    case (case selector of { 0# -> old; _ -> final }) of { value ->
      -- Observe pointer identity using only the addresses in this fixture.
      -- Unknown values remain a distinct failure, not an assumed offset.
      (# s5, case eqAddr# value nullAddr# of {
        1# -> -1#;
        _ -> case eqAddr# value initialAddr of {
          1# -> initial;
          _ -> case eqAddr# value expectedAddr of {
            1# -> expected;
            _ -> case eqAddr# value desiredAddr of { 1# -> desired; _ -> -2# }
          }
        }
      } #)
    } } } } } } }) of { (# _, result #) -> result }
  } })
