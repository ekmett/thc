-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
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
