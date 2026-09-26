-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module AddressArrayCopyAudit where
import GHC.Exts

-- All native inputs use distinct live eight-byte allocations. Observe each
-- byte of both arrays: source preservation and destination sentinels are exact.
{-# INLINE initialize #-}
initialize :: MutableByteArray# s -> Int# -> Int# -> State# s -> State# s
initialize a seed step s0 =
  case writeWord8Array# a 0# (byte seed) s0 of { s1 ->
  case writeWord8Array# a 1# (byte (seed +# step)) s1 of { s2 ->
  case writeWord8Array# a 2# (byte (seed +# 2# *# step)) s2 of { s3 ->
  case writeWord8Array# a 3# (byte (seed +# 3# *# step)) s3 of { s4 ->
  case writeWord8Array# a 4# (byte (seed +# 4# *# step)) s4 of { s5 ->
  case writeWord8Array# a 5# (byte (seed +# 5# *# step)) s5 of { s6 ->
  case writeWord8Array# a 6# (byte (seed +# 6# *# step)) s6 of { s7 ->
  writeWord8Array# a 7# (byte (seed +# 7# *# step)) s7 } } } } } } }
  where byte x = wordToWord8# (int2Word# x)

{-# INLINE observe #-}
observe :: MutableByteArray# s -> MutableByteArray# s -> Int# -> State# s -> Int#
observe a b field s0 =
  case touch# a s0 of { s1 -> case touch# b s1 of { s2 ->
  case unsafeFreezeByteArray# a s2 of { (# s3, source #) ->
  case unsafeFreezeByteArray# b s3 of { (# _, destination #) ->
  case field <# 8# of
    1# -> word2Int# (word8ToWord# (indexWord8Array# source field))
    _ -> word2Int# (word8ToWord# (indexWord8Array# destination (field -# 8#))) } } } }

{-# OPAQUE addrToArray #-}
addrToArray :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
addrToArray seed from to count field = runRW# (\s0 ->
  case newPinnedByteArray# 8# s0 of { (# s1, a #) ->
  case newByteArray# 8# s1 of { (# s2, b #) ->
  case initialize a seed 17# s2 of { s3 ->
  case initialize b (seed *# 3# +# 91#) 29# s3 of { s4 ->
  case copyAddrToByteArray# (plusAddr# (mutableByteArrayContents# a) from) b to count s4 of { s5 ->
  observe a b field s5 } } } } })

{-# OPAQUE arrayToAddr #-}
arrayToAddr :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
arrayToAddr seed from to count field = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case newPinnedByteArray# 8# s1 of { (# s2, b #) ->
  case initialize a seed 17# s2 of { s3 ->
  case initialize b (seed *# 3# +# 91#) 29# s3 of { s4 ->
  case unsafeFreezeByteArray# a s4 of { (# s5, source #) ->
  case copyByteArrayToAddr# source from (plusAddr# (mutableByteArrayContents# b) to) count s5 of { s6 ->
  observe a b field s6 } } } } } })

{-# OPAQUE mutableArrayToAddr #-}
mutableArrayToAddr :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
mutableArrayToAddr seed from to count field = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case newPinnedByteArray# 8# s1 of { (# s2, b #) ->
  case initialize a seed 17# s2 of { s3 ->
  case initialize b (seed *# 3# +# 91#) 29# s3 of { s4 ->
  case copyMutableByteArrayToAddr# a from (plusAddr# (mutableByteArrayContents# b) to) count s4 of { s5 ->
  observe a b field s5 } } } } })
