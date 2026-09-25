-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ShrinkMutableByteArrayAudit where

import GHC.Exts

{-# OPAQUE shrinkWorker #-}
shrinkWorker :: MutableByteArray# s -> Int# -> State# s -> State# s
shrinkWorker array size state = shrinkMutableByteArray# array size state

initialize :: MutableByteArray# s -> Int# -> State# s -> State# s
initialize array seed = go 0#
 where
  go index state
    | isTrue# (index <# 16#) = case writeWord8Array# array index
        (wordToWord8# (int2Word# (seed +# 17# *# index))) state of
        next -> go (index +# 1#) next
    | otherwise = state

observe :: ByteArray# -> Int#
observe array = go 0# 0#
 where
  go index total
    | isTrue# (index <# sizeofByteArray# array) =
        let byte = word2Int# (word8ToWord# (indexWord8Array# array index)) in
        go (index +# 1#) (total +# byte *# (index +# 1#))
    | otherwise = total

-- Query and freeze the original reference after shrinking. No replacement is
-- returned by the primitive; the same allocation must carry the new length.
{-# OPAQUE shrinkBytes #-}
shrinkBytes :: Int# -> Int# -> Int#
shrinkBytes seed size = runRW# (\state ->
  case newByteArray# 16# state of { (# s1, array #) ->
  case initialize array seed s1 of { s2 ->
  case shrinkWorker array size s2 of { s3 ->
  case getSizeofMutableByteArray# array s3 of { (# s4, actual #) ->
  case unsafeFreezeByteArray# array s4 of { (# _, frozen #) ->
  actual *# 256# +# observe frozen }}}}})

-- The Addr# is created before shrink; a surviving prefix remains readable.
{-# OPAQUE shrinkPinned #-}
shrinkPinned :: Int# -> Int# -> Int#
shrinkPinned seed size = runRW# (\state ->
  case newPinnedByteArray# 16# state of { (# s1, array #) ->
  case initialize array seed s1 of { s2 ->
  case mutableByteArrayContents# array of { address ->
  case shrinkWorker array size s2 of { s3 ->
  case readWord8OffAddr# address 0# s3 of { (# s4, byte #) ->
  case touch# array s4 of { s5 ->
  case getSizeofMutableByteArray# array s5 of { (# _, actual #) ->
  actual *# 256# +# word2Int# (word8ToWord# byte) }}}}}}})
