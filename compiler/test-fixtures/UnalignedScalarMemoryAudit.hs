-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module UnalignedScalarMemoryAudit where

import GHC.Exts

-- Each entry retains all six primops in original GHC Core. OffAddrAs uses an
-- interior address and a negative byte displacement; ArrayAs uses the base.
-- Two distinct stores expose state ordering; untouched bytes are sentinels.

{-# OPAQUE unalignedChar #-}
unalignedChar :: Int# -> Int# -> Int# -> Int#
unalignedChar raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsChar# mutable offset (chr# (andI# raw 255#)) s2 of { s3 ->
  case readWord8OffAddrAsChar# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsChar# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsChar# end (offset -# 96#) (chr# (andI# next 255#)) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> ord# first;
    1# -> ord# second;
    2# -> ord# (indexWord8ArrayAsChar# bytes offset);
    3# -> ord# (indexWord8OffAddrAsChar# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 1#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedWideChar #-}
unalignedWideChar :: Int# -> Int# -> Int# -> Int#
unalignedWideChar raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsWideChar# mutable offset (chr# (andI# raw 1114111#)) s2 of { s3 ->
  case readWord8OffAddrAsWideChar# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsWideChar# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsWideChar# end (offset -# 96#) (chr# (andI# next 1114111#)) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> ord# first;
    1# -> ord# second;
    2# -> ord# (indexWord8ArrayAsWideChar# bytes offset);
    3# -> ord# (indexWord8OffAddrAsWideChar# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 4#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedInt #-}
unalignedInt :: Int# -> Int# -> Int# -> Int#
unalignedInt raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsInt# mutable offset raw s2 of { s3 ->
  case readWord8OffAddrAsInt# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsInt# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsInt# end (offset -# 96#) next s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> first;
    1# -> second;
    2# -> (indexWord8ArrayAsInt# bytes offset);
    3# -> (indexWord8OffAddrAsInt# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedWord #-}
unalignedWord :: Int# -> Int# -> Int# -> Int#
unalignedWord raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsWord# mutable offset (int2Word# raw) s2 of { s3 ->
  case readWord8OffAddrAsWord# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsWord# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsWord# end (offset -# 96#) (int2Word# next) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> word2Int# first;
    1# -> word2Int# second;
    2# -> word2Int# (indexWord8ArrayAsWord# bytes offset);
    3# -> word2Int# (indexWord8OffAddrAsWord# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedFloat #-}
unalignedFloat :: Int# -> Int# -> Int# -> Int#
unalignedFloat raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsFloat# mutable offset (castWord32ToFloat# (wordToWord32# (int2Word# raw))) s2 of { s3 ->
  case readWord8OffAddrAsFloat# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsFloat# mutable offset s4 of { (# s5, second #) ->
  case xorI# raw -2147483648# of { next ->
  case writeWord8OffAddrAsFloat# end (offset -# 96#) (castWord32ToFloat# (wordToWord32# (int2Word# next))) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> word2Int# (word32ToWord# (castFloatToWord32# first));
    1# -> word2Int# (word32ToWord# (castFloatToWord32# second));
    2# -> word2Int# (word32ToWord# (castFloatToWord32# (indexWord8ArrayAsFloat# bytes offset)));
    3# -> word2Int# (word32ToWord# (castFloatToWord32# (indexWord8OffAddrAsFloat# end (offset -# 96#))));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 4#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedDouble #-}
unalignedDouble :: Int# -> Int# -> Int# -> Int#
unalignedDouble raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsDouble# mutable offset (castWord64ToDouble# (wordToWord64# (int2Word# raw))) s2 of { s3 ->
  case readWord8OffAddrAsDouble# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsDouble# mutable offset s4 of { (# s5, second #) ->
  case xorI# raw -9223372036854775808# of { next ->
  case writeWord8OffAddrAsDouble# end (offset -# 96#) (castWord64ToDouble# (wordToWord64# (int2Word# next))) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> word2Int# (word64ToWord# (castDoubleToWord64# first));
    1# -> word2Int# (word64ToWord# (castDoubleToWord64# second));
    2# -> word2Int# (word64ToWord# (castDoubleToWord64# (indexWord8ArrayAsDouble# bytes offset)));
    3# -> word2Int# (word64ToWord# (castDoubleToWord64# (indexWord8OffAddrAsDouble# end (offset -# 96#))));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedInt16 #-}
unalignedInt16 :: Int# -> Int# -> Int# -> Int#
unalignedInt16 raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsInt16# mutable offset (intToInt16# raw) s2 of { s3 ->
  case readWord8OffAddrAsInt16# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsInt16# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsInt16# end (offset -# 96#) (intToInt16# next) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> int16ToInt# first;
    1# -> int16ToInt# second;
    2# -> int16ToInt# (indexWord8ArrayAsInt16# bytes offset);
    3# -> int16ToInt# (indexWord8OffAddrAsInt16# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 2#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedInt32 #-}
unalignedInt32 :: Int# -> Int# -> Int# -> Int#
unalignedInt32 raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsInt32# mutable offset (intToInt32# raw) s2 of { s3 ->
  case readWord8OffAddrAsInt32# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsInt32# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsInt32# end (offset -# 96#) (intToInt32# next) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> int32ToInt# first;
    1# -> int32ToInt# second;
    2# -> int32ToInt# (indexWord8ArrayAsInt32# bytes offset);
    3# -> int32ToInt# (indexWord8OffAddrAsInt32# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 4#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedInt64 #-}
unalignedInt64 :: Int# -> Int# -> Int# -> Int#
unalignedInt64 raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsInt64# mutable offset (intToInt64# raw) s2 of { s3 ->
  case readWord8OffAddrAsInt64# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsInt64# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsInt64# end (offset -# 96#) (intToInt64# next) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> int64ToInt# first;
    1# -> int64ToInt# second;
    2# -> int64ToInt# (indexWord8ArrayAsInt64# bytes offset);
    3# -> int64ToInt# (indexWord8OffAddrAsInt64# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedWord16 #-}
unalignedWord16 :: Int# -> Int# -> Int# -> Int#
unalignedWord16 raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsWord16# mutable offset (wordToWord16# (int2Word# raw)) s2 of { s3 ->
  case readWord8OffAddrAsWord16# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsWord16# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsWord16# end (offset -# 96#) (wordToWord16# (int2Word# next)) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> word2Int# (word16ToWord# first);
    1# -> word2Int# (word16ToWord# second);
    2# -> word2Int# (word16ToWord# (indexWord8ArrayAsWord16# bytes offset));
    3# -> word2Int# (word16ToWord# (indexWord8OffAddrAsWord16# end (offset -# 96#)));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 2#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedWord32 #-}
unalignedWord32 :: Int# -> Int# -> Int# -> Int#
unalignedWord32 raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsWord32# mutable offset (wordToWord32# (int2Word# raw)) s2 of { s3 ->
  case readWord8OffAddrAsWord32# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsWord32# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsWord32# end (offset -# 96#) (wordToWord32# (int2Word# next)) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> word2Int# (word32ToWord# first);
    1# -> word2Int# (word32ToWord# second);
    2# -> word2Int# (word32ToWord# (indexWord8ArrayAsWord32# bytes offset));
    3# -> word2Int# (word32ToWord# (indexWord8OffAddrAsWord32# end (offset -# 96#)));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 4#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedWord64 #-}
unalignedWord64 :: Int# -> Int# -> Int# -> Int#
unalignedWord64 raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsWord64# mutable offset (wordToWord64# (int2Word# raw)) s2 of { s3 ->
  case readWord8OffAddrAsWord64# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsWord64# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWord8OffAddrAsWord64# end (offset -# 96#) (wordToWord64# (int2Word# next)) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> word2Int# (word64ToWord# first);
    1# -> word2Int# (word64ToWord# second);
    2# -> word2Int# (word64ToWord# (indexWord8ArrayAsWord64# bytes offset));
    3# -> word2Int# (word64ToWord# (indexWord8OffAddrAsWord64# end (offset -# 96#)));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE unalignedAddr #-}
unalignedAddr :: Addr# -> Addr# -> Int# -> Int# -> Int#
unalignedAddr initial replacement offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsAddr# mutable offset initial s2 of { s3 ->
  case readWord8OffAddrAsAddr# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsAddr# mutable offset s4 of { (# s5, second #) ->
  case writeWord8OffAddrAsAddr# end (offset -# 96#) replacement s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> eqAddr# initial first;
    1# -> eqAddr# initial second;
    2# -> eqAddr# replacement (indexWord8ArrayAsAddr# bytes offset);
    3# -> eqAddr# replacement (indexWord8OffAddrAsAddr# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } })

{-# OPAQUE unalignedStablePtr #-}
unalignedStablePtr :: StablePtr# Int -> StablePtr# Int -> Int# -> Int# -> Int#
unalignedStablePtr initial replacement offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 96# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 96# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 96# of { end ->
  case writeWord8ArrayAsStablePtr# mutable offset initial s2 of { s3 ->
  case readWord8OffAddrAsStablePtr# end (offset -# 96#) s3 of { (# s4, first #) ->
  case readWord8ArrayAsStablePtr# mutable offset s4 of { (# s5, second #) ->
  case writeWord8OffAddrAsStablePtr# end (offset -# 96#) replacement s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> eqStablePtr# initial first;
    1# -> eqStablePtr# initial second;
    2# -> eqStablePtr# replacement (indexWord8ArrayAsStablePtr# bytes offset);
    3# -> eqStablePtr# replacement (indexWord8OffAddrAsStablePtr# end (offset -# 96#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (offset -# 1#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset +# 8#) 96#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } })
