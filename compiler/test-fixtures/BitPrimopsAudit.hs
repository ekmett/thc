{-# LANGUAGE MagicHash #-}
module BitPrimopsAudit where

import GHC.Exts

-- Keep the original primitive in Core; no output mask hides THC carrier bits.
-- The native driver masks only GHC-defined result bits for narrow swaps/reversal.

{-# OPAQUE popCnt8 #-}
popCnt8 :: Int# -> Int#
popCnt8 x = word2Int# (popCnt8# (int2Word# x))

{-# OPAQUE popCnt16 #-}
popCnt16 :: Int# -> Int#
popCnt16 x = word2Int# (popCnt16# (int2Word# x))

{-# OPAQUE popCnt32 #-}
popCnt32 :: Int# -> Int#
popCnt32 x = word2Int# (popCnt32# (int2Word# x))

{-# OPAQUE popCnt64 #-}
popCnt64 :: Int# -> Int#
popCnt64 x = word2Int# (popCnt64# (wordToWord64# (int2Word# x)))

{-# OPAQUE clz8 #-}
clz8 :: Int# -> Int#
clz8 x = word2Int# (clz8# (int2Word# x))

{-# OPAQUE clz16 #-}
clz16 :: Int# -> Int#
clz16 x = word2Int# (clz16# (int2Word# x))

{-# OPAQUE clz32 #-}
clz32 :: Int# -> Int#
clz32 x = word2Int# (clz32# (int2Word# x))

{-# OPAQUE clz64 #-}
clz64 :: Int# -> Int#
clz64 x = word2Int# (clz64# (wordToWord64# (int2Word# x)))

{-# OPAQUE ctz8 #-}
ctz8 :: Int# -> Int#
ctz8 x = word2Int# (ctz8# (int2Word# x))

{-# OPAQUE ctz16 #-}
ctz16 :: Int# -> Int#
ctz16 x = word2Int# (ctz16# (int2Word# x))

{-# OPAQUE ctz32 #-}
ctz32 :: Int# -> Int#
ctz32 x = word2Int# (ctz32# (int2Word# x))

{-# OPAQUE ctz64 #-}
ctz64 :: Int# -> Int#
ctz64 x = word2Int# (ctz64# (wordToWord64# (int2Word# x)))

{-# OPAQUE byteSwap16 #-}
byteSwap16 :: Int# -> Int#
byteSwap16 x = word2Int# (byteSwap16# (int2Word# x))

{-# OPAQUE byteSwap32 #-}
byteSwap32 :: Int# -> Int#
byteSwap32 x = word2Int# (byteSwap32# (int2Word# x))

{-# OPAQUE byteSwap64 #-}
byteSwap64 :: Int# -> Int#
byteSwap64 x = word2Int# (word64ToWord# (byteSwap64# (wordToWord64# (int2Word# x))))

{-# OPAQUE byteSwapWord #-}
byteSwapWord :: Int# -> Int#
byteSwapWord x = word2Int# (byteSwap# (int2Word# x))

{-# OPAQUE bitReverse8 #-}
bitReverse8 :: Int# -> Int#
bitReverse8 x = word2Int# (bitReverse8# (int2Word# x))

{-# OPAQUE bitReverse16 #-}
bitReverse16 :: Int# -> Int#
bitReverse16 x = word2Int# (bitReverse16# (int2Word# x))

{-# OPAQUE bitReverse32 #-}
bitReverse32 :: Int# -> Int#
bitReverse32 x = word2Int# (bitReverse32# (int2Word# x))

{-# OPAQUE bitReverse64 #-}
bitReverse64 :: Int# -> Int#
bitReverse64 x = word2Int# (word64ToWord# (bitReverse64# (wordToWord64# (int2Word# x))))

{-# OPAQUE bitReverseWord #-}
bitReverseWord :: Int# -> Int#
bitReverseWord x = word2Int# (bitReverse# (int2Word# x))

-- Run every bit operation in one compiled function. Each mismatch sets its own
-- bit, so failures cannot cancel and the caller can identify the operation.
{-# OPAQUE bitPrimops #-}
bitPrimops :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
bitPrimops x e0 e1 e2 e3 e4 e5 e6 e7 e8 e9 e10 e11 e12 e13 e14 e15 e16 e17 e18 e19 e20 =
  let c0 = uncheckedIShiftL# ((word2Int# (popCnt8# (int2Word# x))) /=# e0) 0#
      c1 = uncheckedIShiftL# ((word2Int# (popCnt16# (int2Word# x))) /=# e1) 1#
      c2 = uncheckedIShiftL# ((word2Int# (popCnt32# (int2Word# x))) /=# e2) 2#
      c3 = uncheckedIShiftL# ((word2Int# (popCnt64# (wordToWord64# (int2Word# x)))) /=# e3) 3#
      c4 = uncheckedIShiftL# ((word2Int# (clz8# (int2Word# x))) /=# e4) 4#
      c5 = uncheckedIShiftL# ((word2Int# (clz16# (int2Word# x))) /=# e5) 5#
      c6 = uncheckedIShiftL# ((word2Int# (clz32# (int2Word# x))) /=# e6) 6#
      c7 = uncheckedIShiftL# ((word2Int# (clz64# (wordToWord64# (int2Word# x)))) /=# e7) 7#
      c8 = uncheckedIShiftL# ((word2Int# (ctz8# (int2Word# x))) /=# e8) 8#
      c9 = uncheckedIShiftL# ((word2Int# (ctz16# (int2Word# x))) /=# e9) 9#
      c10 = uncheckedIShiftL# ((word2Int# (ctz32# (int2Word# x))) /=# e10) 10#
      c11 = uncheckedIShiftL# ((word2Int# (ctz64# (wordToWord64# (int2Word# x)))) /=# e11) 11#
      c12 = uncheckedIShiftL# ((word2Int# (byteSwap16# (int2Word# x))) /=# e12) 12#
      c13 = uncheckedIShiftL# ((word2Int# (byteSwap32# (int2Word# x))) /=# e13) 13#
      c14 = uncheckedIShiftL# ((word2Int# (word64ToWord# (byteSwap64# (wordToWord64# (int2Word# x))))) /=# e14) 14#
      c15 = uncheckedIShiftL# ((word2Int# (byteSwap# (int2Word# x))) /=# e15) 15#
      c16 = uncheckedIShiftL# ((word2Int# (bitReverse8# (int2Word# x))) /=# e16) 16#
      c17 = uncheckedIShiftL# ((word2Int# (bitReverse16# (int2Word# x))) /=# e17) 17#
      c18 = uncheckedIShiftL# ((word2Int# (bitReverse32# (int2Word# x))) /=# e18) 18#
      c19 = uncheckedIShiftL# ((word2Int# (word64ToWord# (bitReverse64# (wordToWord64# (int2Word# x))))) /=# e19) 19#
      c20 = uncheckedIShiftL# ((word2Int# (bitReverse# (int2Word# x))) /=# e20) 20#
  in c0 `orI#` c1 `orI#` c2 `orI#` c3 `orI#` c4 `orI#` c5 `orI#` c6
     `orI#` c7 `orI#` c8 `orI#` c9 `orI#` c10 `orI#` c11 `orI#` c12 `orI#` c13
     `orI#` c14 `orI#` c15 `orI#` c16 `orI#` c17 `orI#` c18 `orI#` c19 `orI#` c20
