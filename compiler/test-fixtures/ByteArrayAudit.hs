{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ByteArrayAudit where

import GHC.Exts
import qualified Data.ByteString.Short as S

-- The installed bytestring pack/unpack workers must execute, including the
-- genuine GHC.Internal.List length worker used by pack.
{-# OPAQUE shortBytes #-}
shortBytes :: Int# -> Int#
shortBytes raw = case S.length bytes + foldl (\a w -> a * 33 + fromIntegral w) 0 (S.unpack bytes) of
  I# result -> result
  where
    seed = I# raw
    n = abs (seed `rem` 33)
    bytes = S.pack [fromIntegral (seed + i * 17) | i <- [0 .. n - 1]]

-- Repeated writes to one location expose a stale final byte or wrong write order. A
-- separate allocation with different contents exposes accidental shared storage.
{-# OPAQUE orderedBytes #-}
orderedBytes :: Int# -> Int#
orderedBytes seed = runRW# (\s0 ->
  case newByteArray# 3# s0 of { (# s1, a #) ->
  case newByteArray# 1# s1 of { (# s2, b #) ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# seed)) s2 of { s3 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 1#))) s3 of { s4 ->
  case writeWord8Array# b 0# (wordToWord8# (int2Word# (seed +# 71#))) s4 of { s5 ->
  case writeWord8Array# a 2# (wordToWord8# (int2Word# (seed +# 2#))) s5 of { s6 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 17#))) s6 of { s7 ->
  case unsafeFreezeByteArray# a s7 of { (# s8, aa #) ->
  case unsafeFreezeByteArray# b s8 of { (# _, bb #) ->
    sizeofByteArray# aa +#
      word2Int# (word8ToWord# (indexWord8Array# aa 0#)) *# 1# +#
      word2Int# (word8ToWord# (indexWord8Array# aa 1#)) *# 257# +#
      word2Int# (word8ToWord# (indexWord8Array# aa 2#)) *# 65537# +#
      word2Int# (word8ToWord# (indexWord8Array# bb 0#)) *# 16777259#
  } } } } } } } } })
