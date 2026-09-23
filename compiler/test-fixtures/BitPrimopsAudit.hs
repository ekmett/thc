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
