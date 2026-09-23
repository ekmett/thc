{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ByteArrayAudit where

import GHC.Exts
import Data.Word (Word8)
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

-- Keep the real public operation as an installed-library dependency. Repeated
-- uncons reconstructs every byte, including empty input, embedded NUL and 255.
{-# NOINLINE publicUncons #-}
publicUncons :: S.ShortByteString -> Maybe (Word8, S.ShortByteString)
publicUncons = S.uncons

{-# OPAQUE shortUncons #-}
shortUncons :: Int# -> Int#
shortUncons raw = case walk bytes of I# result -> result
  where
    seed = I# raw
    n = abs (seed `rem` 33)
    bytes = S.pack [fromIntegral (seed + i * 17) | i <- [0 .. n - 1]]
    walk remaining = case publicUncons remaining of
      Nothing -> 0
      Just (byte, rest) -> fromIntegral byte + 33 * walk rest

-- Distinct initialized source/destination arrays, dynamic contained subranges,
-- and a zero-length copy at both ends. Check unchanged source and destination
-- bytes outside the range as well as copied bytes.
{-# OPAQUE copiedBytes #-}
copiedBytes :: Int# -> Int#
copiedBytes seed = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case newByteArray# 6# s1 of { (# s2, b #) ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# seed)) s2 of { s3 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 17#))) s3 of { s4 ->
  case writeWord8Array# a 2# (wordToWord8# 0##) s4 of { s5 ->
  case writeWord8Array# a 3# (wordToWord8# 255##) s5 of { s6 ->
  case writeWord8Array# b 0# (wordToWord8# 11##) s6 of { s7 ->
  case writeWord8Array# b 1# (wordToWord8# 22##) s7 of { s8 ->
  case writeWord8Array# b 2# (wordToWord8# 33##) s8 of { s9 ->
  case writeWord8Array# b 3# (wordToWord8# 44##) s9 of { s10 ->
  case writeWord8Array# b 4# (wordToWord8# 55##) s10 of { s11 ->
  case writeWord8Array# b 5# (wordToWord8# 66##) s11 of { s12 ->
  case unsafeFreezeByteArray# a s12 of { (# s13, aa #) ->
  case copyByteArray# aa sourceOffset b destinationOffset count s13 of { s14 ->
  case copyByteArray# aa 4# b 6# 0# s14 of { s15 ->
  case unsafeFreezeByteArray# b s15 of { (# _, bb #) ->
    sizeofByteArray# aa +# sizeofByteArray# bb +# byte aa 0# +# 257# *# (byte aa 1# +# 257# *# (byte aa 2# +# 257# *# (byte aa 3# +#
      257# *# (byte bb 0# +# 257# *# (byte bb 1# +# 257# *# (byte bb 2# +# 257# *#
        (byte bb 3# +# 257# *# (byte bb 4# +# 257# *# byte bb 5#))))))))
  } } } } } } } } } } } } } } } })
  where
    key = word2Int# (and# (int2Word# seed) 1023##)
    sourceOffset = remInt# key 5#
    destinationOffset = remInt# (quotInt# key 5#) 7#
    requested = remInt# (quotInt# key 35#) 5#
    count = minInt requested (minInt (4# -# sourceOffset) (6# -# destinationOffset))
    minInt x y = case x <# y of 1# -> x; _ -> y
    byte a i = word2Int# (word8ToWord# (indexWord8Array# a i))
