-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module AtomicIntArrayAudit where

import GHC.Exts

-- Each entry observes two returned old values, the final value, and guard
-- bytes immediately outside element one. All inputs stay live in native Core.

{-# OPAQUE fetchAddResult #-}
fetchAddResult :: Int# -> Int# -> Int# -> Int#
fetchAddResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchAddIntArray# array 1# expected s3 of { (# s4, old1 #) ->
  case fetchAddIntArray# array 1# replacement s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE fetchSubResult #-}
fetchSubResult :: Int# -> Int# -> Int# -> Int#
fetchSubResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchSubIntArray# array 1# expected s3 of { (# s4, old1 #) ->
  case fetchSubIntArray# array 1# replacement s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE fetchAndResult #-}
fetchAndResult :: Int# -> Int# -> Int# -> Int#
fetchAndResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchAndIntArray# array 1# expected s3 of { (# s4, old1 #) ->
  case fetchAndIntArray# array 1# replacement s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE fetchNandResult #-}
fetchNandResult :: Int# -> Int# -> Int# -> Int#
fetchNandResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchNandIntArray# array 1# expected s3 of { (# s4, old1 #) ->
  case fetchNandIntArray# array 1# replacement s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE fetchOrResult #-}
fetchOrResult :: Int# -> Int# -> Int# -> Int#
fetchOrResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchOrIntArray# array 1# expected s3 of { (# s4, old1 #) ->
  case fetchOrIntArray# array 1# replacement s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE fetchXorResult #-}
fetchXorResult :: Int# -> Int# -> Int# -> Int#
fetchXorResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchXorIntArray# array 1# expected s3 of { (# s4, old1 #) ->
  case fetchXorIntArray# array 1# replacement s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE casIntResult #-}
casIntResult :: Int# -> Int# -> Int# -> Int#
casIntResult initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case casIntArray# array 1# expected replacement s3 of { (# s4, old1 #) ->
  case casIntArray# array 1# expected initial s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}})

{-# OPAQUE casInt8Result #-}
casInt8Result :: Int# -> Int# -> Int# -> Int#
casInt8Result initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeInt8Array# array 1# (intToInt8# initial) s2 of { s3 ->
  case casInt8Array# array 1# (intToInt8# expected) (intToInt8# replacement) s3 of { (# s4, old1 #) ->
  case casInt8Array# array 1# (intToInt8# expected) (intToInt8# initial) s4 of { (# s5, old2 #) ->
  case readInt8Array# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 0# s6 of { (# s7, before #) ->
  case readWord8Array# array 2# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then (int8ToInt# old1) +# 17# *# (int8ToInt# old2) +# 31# *# (int8ToInt# final)
    else -1# }}}}}}}})

{-# OPAQUE casInt16Result #-}
casInt16Result :: Int# -> Int# -> Int# -> Int#
casInt16Result initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeInt16Array# array 1# (intToInt16# initial) s2 of { s3 ->
  case casInt16Array# array 1# (intToInt16# expected) (intToInt16# replacement) s3 of { (# s4, old1 #) ->
  case casInt16Array# array 1# (intToInt16# expected) (intToInt16# initial) s4 of { (# s5, old2 #) ->
  case readInt16Array# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 1# s6 of { (# s7, before #) ->
  case readWord8Array# array 4# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then (int16ToInt# old1) +# 17# *# (int16ToInt# old2) +# 31# *# (int16ToInt# final)
    else -1# }}}}}}}})

{-# OPAQUE casInt32Result #-}
casInt32Result :: Int# -> Int# -> Int# -> Int#
casInt32Result initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeInt32Array# array 1# (intToInt32# initial) s2 of { s3 ->
  case casInt32Array# array 1# (intToInt32# expected) (intToInt32# replacement) s3 of { (# s4, old1 #) ->
  case casInt32Array# array 1# (intToInt32# expected) (intToInt32# initial) s4 of { (# s5, old2 #) ->
  case readInt32Array# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 3# s6 of { (# s7, before #) ->
  case readWord8Array# array 8# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then (int32ToInt# old1) +# 17# *# (int32ToInt# old2) +# 31# *# (int32ToInt# final)
    else -1# }}}}}}}})

{-# OPAQUE casInt64Result #-}
casInt64Result :: Int# -> Int# -> Int# -> Int#
casInt64Result initial expected replacement = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 32# 53# s1 of { s2 ->
  case writeInt64Array# array 1# (intToInt64# initial) s2 of { s3 ->
  case casInt64Array# array 1# (intToInt64# expected) (intToInt64# replacement) s3 of { (# s4, old1 #) ->
  case casInt64Array# array 1# (intToInt64# expected) (intToInt64# initial) s4 of { (# s5, old2 #) ->
  case readInt64Array# array 1# s5 of { (# s6, final #) ->
  case readWord8Array# array 7# s6 of { (# s7, before #) ->
  case readWord8Array# array 16# s7 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then (int64ToInt# old1) +# 17# *# (int64ToInt# old2) +# 31# *# (int64ToInt# final)
    else -1# }}}}}}}})

{-# OPAQUE atomicLoadStore #-}
atomicLoadStore :: Int# -> Int# -> Int# -> Int#
atomicLoadStore initial first second = runRW# (\s0 ->
  case newByteArray# 24# s0 of { (# s1, array #) ->
  case setByteArray# array 0# 24# 53# s1 of { s2 ->
  case atomicWriteIntArray# array 1# initial s2 of { s3 ->
  case atomicReadIntArray# array 1# s3 of { (# s4, old1 #) ->
  case atomicWriteIntArray# array 1# first s4 of { s5 ->
  case atomicReadIntArray# array 1# s5 of { (# s6, old2 #) ->
  case atomicWriteIntArray# array 1# second s6 of { s7 ->
  case atomicReadIntArray# array 1# s7 of { (# s8, final #) ->
  case readWord8Array# array 7# s8 of { (# s9, before #) ->
  case readWord8Array# array 16# s9 of { (# _, after #) ->
  if isTrue# (eqWord8# before (wordToWord8# 53##)) &&
     isTrue# (eqWord8# after (wordToWord8# 53##))
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}}}}})
