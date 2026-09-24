-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module MutableByteArrayAudit where
import GHC.Exts
import GHC.Word (Word8(W8#))
import qualified Data.ByteString.Short as S

{-# INLINE seedArray #-}
seedArray :: MutableByteArray# s -> Int# -> Int# -> State# s -> State# s
seedArray a seed step state =
  case writeWord8Array# a 0# (wordToWord8# (int2Word# (seed +# 0# *# step))) state of { s1 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 1# *# step))) s1 of { s2 ->
  case writeWord8Array# a 2# (wordToWord8# (int2Word# (seed +# 2# *# step))) s2 of { s3 ->
  case writeWord8Array# a 3# (wordToWord8# (int2Word# (seed +# 3# *# step))) s3 of { s4 ->
  case writeWord8Array# a 4# (wordToWord8# (int2Word# (seed +# 4# *# step))) s4 of { s5 ->
  case writeWord8Array# a 5# (wordToWord8# (int2Word# (seed +# 5# *# step))) s5 of { s6 ->
  case writeWord8Array# a 6# (wordToWord8# (int2Word# (seed +# 6# *# step))) s6 of { s7 ->
  writeWord8Array# a 7# (wordToWord8# (int2Word# (seed +# 7# *# step))) s7 } } } } } } }

{-# INLINE fingerprint #-}
fingerprint :: ByteArray# -> Int#
fingerprint a = word2Int# (word8ToWord# (indexWord8Array# a 0#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 1#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 2#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 3#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 4#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 5#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 6#)) +# 257# *# (word2Int# (word8ToWord# (indexWord8Array# a 7#)))))))))

-- Full-width Int# fill value: GHC's memset semantics select the low byte.
{-# OPAQUE filledBytes #-}
filledBytes :: Int# -> Int# -> Int#
filledBytes raw code = runRW# (\s ->
  case newByteArray# 8# s of { (# s1, a #) ->
  case seedArray a raw 17# s1 of { s2 ->
  case setByteArray# a offset count raw s2 of { s3 ->
  case setByteArray# a 8# 0# (negateInt# raw) s3 of { s4 ->
  case unsafeFreezeByteArray# a s4 of { (# _, frozen #) -> fingerprint frozen }
  }}}})
 where
  key = andI# code 1023#
  offset = remInt# key 9#
  requested = remInt# (quotInt# key 9#) 9#
  remaining = 8# -# offset
  count = if isTrue# (requested <# remaining) then requested else remaining

-- Same-array move covers both overlap directions, identical ranges and empty
-- ranges; all reads observe the source bytes from before the move.
{-# OPAQUE movedBytes #-}
movedBytes :: Int# -> Int# -> Int#
movedBytes raw code = runRW# (\s ->
  case newByteArray# 8# s of { (# s1, a #) ->
  case seedArray a raw 17# s1 of { s2 ->
  case copyMutableByteArray# a from a to count s2 of { s3 ->
  case copyMutableByteArray# a 8# a 8# 0# s3 of { s4 ->
  case unsafeFreezeByteArray# a s4 of { (# _, frozen #) -> fingerprint frozen }
  }}}})
 where
  key = andI# code 1023#
  from = remInt# key 9#
  to = remInt# (quotInt# key 9#) 9#
  requested = remInt# (quotInt# key 81#) 9#
  left = 8# -# from
  right = 8# -# to
  remaining = if isTrue# (left <# right) then left else right
  count = if isTrue# (requested <# remaining) then requested else remaining

-- One array, disjoint subranges in either direction; adjacency is permitted.
{-# OPAQUE disjointBytes #-}
disjointBytes :: Int# -> Int# -> Int#
disjointBytes raw code = runRW# (\s ->
  case newByteArray# 8# s of { (# s1, a #) ->
  case seedArray a raw 17# s1 of { s2 ->
  case copyMutableByteArrayNonOverlapping# a from a to count s2 of { s3 ->
  case copyMutableByteArrayNonOverlapping# a 8# a 8# 0# s3 of { s4 ->
  case unsafeFreezeByteArray# a s4 of { (# _, frozen #) -> fingerprint frozen }
  }}}})
 where
  key = andI# code 1023#
  lo = remInt# key 5#
  hi = 4# +# remInt# (quotInt# key 5#) 5#
  requested = remInt# (quotInt# key 25#) 5#
  left = 4# -# lo
  right = 8# -# hi
  remaining = if isTrue# (left <# right) then left else right
  count = if isTrue# (requested <# remaining) then requested else remaining
  reverse = remInt# (quotInt# key 125#) 2#
  from = if isTrue# (reverse ==# 0#) then lo else hi
  to = if isTrue# (reverse ==# 0#) then hi else lo

-- Distinct storage; a later source write cannot change bytes already copied.
{-# OPAQUE copiedMutableBytes #-}
copiedMutableBytes :: Int# -> Int# -> Int#
copiedMutableBytes raw code = runRW# (\s ->
  case newByteArray# 8# s of { (# s1, a #) ->
  case newByteArray# 8# s1 of { (# s2, b #) ->
  case seedArray a raw 17# s2 of { s3 ->
  case seedArray b (raw +# 101#) 29# s3 of { s4 ->
  case copyMutableByteArray# a from b to count s4 of { s5 ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# (raw +# 93#))) s5 of { s6 ->
  case copyMutableByteArray# a 8# b 8# 0# s6 of { s7 ->
  case unsafeFreezeByteArray# a s7 of { (# s8, frozenA #) ->
  case unsafeFreezeByteArray# b s8 of { (# _, frozenB #) -> fingerprint frozenA +# 65537# *# fingerprint frozenB }
  }}}}}}}})
 where
  key = andI# code 1023#
  from = remInt# key 9#
  to = remInt# (quotInt# key 9#) 9#
  requested = remInt# (quotInt# key 81#) 9#
  left = 8# -# from
  right = 8# -# to
  remaining = if isTrue# (left <# right) then left else right
  count = if isTrue# (requested <# remaining) then requested else remaining

-- Distinct storage; a later source write cannot change bytes already copied.
{-# OPAQUE copiedDisjointBytes #-}
copiedDisjointBytes :: Int# -> Int# -> Int#
copiedDisjointBytes raw code = runRW# (\s ->
  case newByteArray# 8# s of { (# s1, a #) ->
  case newByteArray# 8# s1 of { (# s2, b #) ->
  case seedArray a raw 17# s2 of { s3 ->
  case seedArray b (raw +# 101#) 29# s3 of { s4 ->
  case copyMutableByteArrayNonOverlapping# a from b to count s4 of { s5 ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# (raw +# 93#))) s5 of { s6 ->
  case copyMutableByteArrayNonOverlapping# a 8# b 8# 0# s6 of { s7 ->
  case unsafeFreezeByteArray# a s7 of { (# s8, frozenA #) ->
  case unsafeFreezeByteArray# b s8 of { (# _, frozenB #) -> fingerprint frozenA +# 65537# *# fingerprint frozenB }
  }}}}}}}})
 where
  key = andI# code 1023#
  from = remInt# key 9#
  to = remInt# (quotInt# key 9#) 9#
  requested = remInt# (quotInt# key 81#) 9#
  left = 8# -# from
  right = 8# -# to
  remaining = if isTrue# (left <# right) then left else right
  count = if isTrue# (requested <# remaining) then requested else remaining

-- Genuine public bytestring construction and fold, including the empty branch.
-- The installed implementation retains setByteArray# in both Core stages.
{-# OPAQUE publicReplicate #-}
publicReplicate :: Int# -> Int# -> Int#
publicReplicate raw code = case S.foldl' (\acc byte -> acc*257+fromIntegral byte) (S.length bytes) bytes of I# n -> n
 where bytes = S.replicate (I# (andI# code 15#)) (W8# (wordToWord8# (int2Word# raw)))
