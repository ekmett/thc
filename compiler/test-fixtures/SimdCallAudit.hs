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
