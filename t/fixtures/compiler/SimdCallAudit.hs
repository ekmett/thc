-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}

-- |
-- Module      : SimdCallAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for simd call audit Core and metadata.
module SimdCallAudit where

import GHC.Exts

data Heap = Heap Int16X8# Int#

{-# OPAQUE consumeHeap #-}
consumeHeap :: Heap -> Int#
consumeHeap (Heap vector bias) = vectorWorker vector bias

{-# OPAQUE applyHeapPartial #-}
applyHeapPartial :: (Int# -> Heap) -> Int#
applyHeapPartial partial = consumeHeap (partial 13#)

{-# OPAQUE heapCase #-}
heapCase :: Int# -> Int#
heapCase x = consumeHeap (Heap (vectorReturn x) 13#)

{-# OPAQUE heapPapCase #-}
heapPapCase :: Int# -> Int#
heapPapCase x = applyHeapPartial (Heap (vectorReturn x))

{-# OPAQUE applyCaptured #-}
applyCaptured :: (Int# -> Int#) -> Int#
applyCaptured f = f 13#

{-# OPAQUE capturedCase #-}
capturedCase :: Int# -> Int#
capturedCase x = case vectorReturn x of
  vector -> applyCaptured (\bias -> case bias of
    0# -> 0#
    _ -> vectorWorker vector bias)

{-# OPAQUE selectBox #-}
selectBox :: Int# -> Int -> Int#
selectBox gate boxed = case gate of
  0# -> 13#
  _ -> case boxed of I# value -> value

{-# OPAQUE thunkCase #-}
thunkCase :: Int# -> Int#
thunkCase x = case vectorReturn x of
  vector -> let boxed = I# (vectorWorker vector 13#)
            in selectBox x boxed

{-# OPAQUE vectorReturn #-}
vectorReturn :: Int# -> Int16X8#
vectorReturn x = broadcastInt16X8# (intToInt16# x)

{-# OPAQUE vectorWorker #-}
vectorWorker :: Int16X8# -> Int# -> Int#
vectorWorker vector bias = case unpackInt16X8# vector of
  (# first, _, _, _, _, _, _, _ #) -> int16ToInt# first +# bias

{-# OPAQUE directCase #-}
directCase :: Int# -> Int#
directCase x = vectorWorker (vectorReturn x) 13#

{-# OPAQUE keepAliveCase #-}
keepAliveCase :: Int# -> Int#
keepAliveCase x = runRW# (\s ->
  vectorWorker (keepAlive# (I# x) s (\_ -> vectorReturn x)) 13#)

{-# OPAQUE keepAliveThrowVector #-}
keepAliveThrowVector :: Int -> State# RealWorld -> Int16X8#
keepAliveThrowVector payload s = keepAlive# payload s (\_ -> raise# payload)

-- catch# returns a lifted Int; the keepAlive# continuation itself has a direct
-- vector result, including on its exceptional exit. Observe the original payload.
{-# OPAQUE keepAliveThrowCase #-}
keepAliveThrowCase :: Int# -> Int#
keepAliveThrowCase x = runRW# (\s ->
  case catch#
    (\s1 -> case keepAliveThrowVector (I# x) s1 of vector -> (# s1, I# (vectorWorker vector 0#) #))
    (\payload s2 -> (# s2, payload #)) s of
      (# _, I# answer #) -> answer)

{-# OPAQUE keepAliveThrowSum #-}
keepAliveThrowSum :: Int -> State# RealWorld -> (# Int# | Int# #)
keepAliveThrowSum payload s = keepAlive# payload s (\_ -> raise# payload)

{-# OPAQUE keepAliveThrowSumCase #-}
keepAliveThrowSumCase :: Int# -> Int#
keepAliveThrowSumCase x = runRW# (\s ->
  case catch#
    (\s1 -> case keepAliveThrowSum (I# x) s1 of
      (# value | #) -> (# s1, I# value #)
      (# | value #) -> (# s1, I# value #))
    (\payload s2 -> (# s2, payload #)) s of
      (# _, I# answer #) -> answer)

{-# OPAQUE papCase #-}
papCase :: Int# -> Int#
papCase x = papApply (vectorWorker (vectorReturn x))

{-# OPAQUE papApply #-}
papApply :: (Int# -> Int#) -> Int#
papApply partial = partial 13#

{-# OPAQUE nestedWorker #-}
nestedWorker :: (# Int16X8#, Int# #) -> Int#
nestedWorker pair = case pair of
  (# vector, bias #) -> vectorWorker vector bias

{-# OPAQUE nestedTupleCase #-}
nestedTupleCase :: Int# -> Int#
nestedTupleCase x = nestedWorker (# vectorReturn x, 13# #)

{-# OPAQUE joinCase #-}
joinCase :: Int# -> Int#
joinCase x = case vectorReturn x of vector -> go vector 3#
  where
    go :: Int16X8# -> Int# -> Int#
    go value remaining = case remaining of
      0# -> vectorWorker value 13#
      _ -> go value (remaining -# 1#)

{-# OPAQUE suffix17 #-}
suffix17 :: Int# -> Int#
suffix17 x = x +# 17#

{-# OPAQUE suffix31 #-}
suffix31 :: Int# -> Int#
suffix31 x = x +# 31#

{-# OPAQUE overMaker #-}
overMaker :: Int16X8# -> (Int# -> Int#)
overMaker vector = case unpackInt16X8# vector of
  (# first, _, _, _, _, _, _, _ #) -> case int16ToInt# first of
    0# -> suffix17
    _ -> suffix31

{-# OPAQUE overCase #-}
overCase :: Int# -> Int#
overCase x = overMaker (vectorReturn x) 13#

-- Distinct lanes and a checksum that demands all of them keep these useful
-- for inspecting vector flow between operations, rather than just one lane.
{-# INLINE vectorSeed #-}
vectorSeed :: Int# -> Int16X8#
vectorSeed x = packInt16X8#
  (# intToInt16# x, intToInt16# (x +# 17#), intToInt16# (x +# 34#),
     intToInt16# (x +# 51#), intToInt16# (x +# 68#), intToInt16# (x +# 85#),
     intToInt16# (x +# 102#), intToInt16# (x +# 119#) #)

{-# INLINE vectorChecksum #-}
vectorChecksum :: Int16X8# -> Int#
vectorChecksum value = case unpackInt16X8# value of
  (# a, b, c, d, e, f, g, h #) ->
    int16ToInt# a +# int16ToInt# b *# 2# +# int16ToInt# c *# 3# +#
    int16ToInt# d *# 4# +# int16ToInt# e *# 5# +# int16ToInt# f *# 6# +#
    int16ToInt# g *# 7# +# int16ToInt# h *# 8#

{-# OPAQUE chainCase #-}
chainCase :: Int# -> Int#
chainCase x = case vectorSeed x of
  seed -> vectorChecksum (minusInt16X8#
    (timesInt16X8# (plusInt16X8# seed (broadcastInt16X8# (intToInt16# 7#)))
      (broadcastInt16X8# (intToInt16# 3#))) seed)

{-# OPAQUE loopCase #-}
loopCase :: Int# -> Int#
loopCase x = go (vectorSeed x) (andI# x 7# +# 1#)
  where
    go :: Int16X8# -> Int# -> Int#
    go value remaining = case remaining of
      0# -> vectorChecksum value
      _ -> go (timesInt16X8#
        (plusInt16X8# value (broadcastInt16X8# (intToInt16# remaining)))
        (broadcastInt16X8# (intToInt16# 3#))) (remaining -# 1#)

-- Return unpacked scalar lanes across a real call, with signed and unsigned
-- narrowing and distinct lane weights observable in the caller.
{-# OPAQUE signedLaneTuple #-}
signedLaneTuple :: Int# -> (# Int32#, Int32#, Int32#, Int32# #)
signedLaneTuple x = unpackInt32X4# (packInt32X4#
  (# intToInt32# x, intToInt32# (x +# 2147483647#),
     intToInt32# (0# -# x -# 1#), intToInt32# (x *# 65537#) #))

signedLaneTupleCase :: Int# -> Int#
signedLaneTupleCase x = case signedLaneTuple x of
  (# a, b, c, d #) -> int32ToInt# a *# 3# +# int32ToInt# b *# 5# +#
    int32ToInt# c *# 7# +# int32ToInt# d *# 11#

{-# OPAQUE unsignedLaneTuple #-}
unsignedLaneTuple :: Int# -> (# Word8#, Word8#, Word8#, Word8#, Word8#, Word8#, Word8#, Word8#,
                              Word8#, Word8#, Word8#, Word8#, Word8#, Word8#, Word8#, Word8# #)
unsignedLaneTuple x = unpackWord8X16# (packWord8X16#
  (# wordToWord8# (int2Word# (x)),
     wordToWord8# (int2Word# (x +# 17#)),
     wordToWord8# (int2Word# (x +# 34#)),
     wordToWord8# (int2Word# (x +# 51#)),
     wordToWord8# (int2Word# (x +# 68#)),
     wordToWord8# (int2Word# (x +# 85#)),
     wordToWord8# (int2Word# (x +# 102#)),
     wordToWord8# (int2Word# (x +# 119#)),
     wordToWord8# (int2Word# (x +# 136#)),
     wordToWord8# (int2Word# (x +# 153#)),
     wordToWord8# (int2Word# (x +# 170#)),
     wordToWord8# (int2Word# (x +# 187#)),
     wordToWord8# (int2Word# (x +# 204#)),
     wordToWord8# (int2Word# (x +# 221#)),
     wordToWord8# (int2Word# (x +# 238#)),
     wordToWord8# (int2Word# (x +# 255#)) #))

unsignedLaneTupleCase :: Int# -> Int#
unsignedLaneTupleCase x = case unsignedLaneTuple x of
  (# a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p #) ->
    word2Int# (word8ToWord# a) *# 1# +#
    word2Int# (word8ToWord# b) *# 2# +#
    word2Int# (word8ToWord# c) *# 3# +#
    word2Int# (word8ToWord# d) *# 4# +#
    word2Int# (word8ToWord# e) *# 5# +#
    word2Int# (word8ToWord# f) *# 6# +#
    word2Int# (word8ToWord# g) *# 7# +#
    word2Int# (word8ToWord# h) *# 8# +#
    word2Int# (word8ToWord# i) *# 9# +#
    word2Int# (word8ToWord# j) *# 10# +#
    word2Int# (word8ToWord# k) *# 11# +#
    word2Int# (word8ToWord# l) *# 12# +#
    word2Int# (word8ToWord# m) *# 13# +#
    word2Int# (word8ToWord# n) *# 14# +#
    word2Int# (word8ToWord# o) *# 15# +#
    word2Int# (word8ToWord# p) *# 16#
