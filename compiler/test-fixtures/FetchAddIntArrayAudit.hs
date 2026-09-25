-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FetchAddIntArrayAudit where

import GHC.Exts

{-# OPAQUE fetchWorker #-}
fetchWorker :: MutableByteArray# s -> Int# -> Int# -> State# s -> (# State# s, Int# #)
fetchWorker array index amount state = fetchAddIntArray# array index amount state

-- The second element catches byte-offset interpretation of the word index.
-- Both old values and the final value contribute to the result.
{-# OPAQUE fetchComposite #-}
fetchComposite :: Int# -> Int# -> Int# -> Int#
fetchComposite initial first second = runRW# (\s0 ->
  case newByteArray# 16# s0 of { (# s1, array #) ->
  case writeIntArray# array 0# 313# s1 of { s2 ->
  case writeIntArray# array 1# initial s2 of { s3 ->
  case fetchWorker array 1# first s3 of { (# s4, old1 #) ->
  case fetchWorker array 1# second s4 of { (# s5, old2 #) ->
  case readIntArray# array 1# s5 of { (# s6, final #) ->
  case readIntArray# array 0# s6 of { (# _, guard #) ->
  if isTrue# (guard ==# 313#)
    then old1 +# 17# *# old2 +# 31# *# final
    else -1# }}}}}}})
