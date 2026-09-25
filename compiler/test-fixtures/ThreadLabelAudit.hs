-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ThreadLabelAudit where

import GHC.Exts

-- UTF-8 lambda, embedded NUL, and a variable ASCII byte. No trailing sentinel.
{-# OPAQUE setLabel #-}
setLabel :: ThreadId# -> Int# -> State# RealWorld -> State# RealWorld
setLabel tid seed s0 =
  case newByteArray# 4# s0 of { (# s1, bytes #) ->
  case writeWord8Array# bytes 0# (wordToWord8# 206##) s1 of { s2 ->
  case writeWord8Array# bytes 1# (wordToWord8# 187##) s2 of { s3 ->
  case writeWord8Array# bytes 2# (wordToWord8# 0##) s3 of { s4 ->
  case writeWord8Array# bytes 3# (wordToWord8# (int2Word# (andI# seed 127#))) s4 of { s5 ->
  case unsafeFreezeByteArray# bytes s5 of { (# s6, frozen #) -> labelThread# tid frozen s6
  } } } } } }

{-# OPAQUE observe #-}
observe :: ThreadId# -> State# RealWorld -> (# State# RealWorld, Int# #)
observe tid s = case threadLabel# tid s of
  (# s1, 0#, _ #) -> (# s1, -1# #)
  (# s1, _, bytes #) -> (# s1, hash bytes 0# (sizeofByteArray# bytes) #)
  where
    hash bytes i acc = case i <# sizeofByteArray# bytes of
      0# -> acc
      _ -> hash bytes (i +# 1#) (acc *# 31# +# word2Int# (word8ToWord# (indexWord8Array# bytes i)))

{-# OPAQUE selfLabel #-}
selfLabel :: Int# -> Int#
selfLabel seed = runRW# (\s -> case myThreadId# s of
  (# s1, tid #) -> case setLabel tid seed s1 of
    s2 -> case observe tid s2 of (# _, value #) -> value)

{-# OPAQUE overwriteLabel #-}
overwriteLabel :: Int# -> Int#
overwriteLabel seed = runRW# (\s -> case myThreadId# s of
  (# s1, tid #) -> case setLabel tid seed s1 of
    s2 -> case setLabel tid (seed +# 1#) s2 of
      s3 -> case observe tid s3 of (# _, value #) -> value)

{-# OPAQUE emptyLabel #-}
emptyLabel :: Int# -> Int#
emptyLabel seed = runRW# (\s -> case myThreadId# s of
  (# s1, tid #) -> case setLabel tid seed s1 of
    s2 -> case newByteArray# 0# s2 of
      (# s3, bytes #) -> case unsafeFreezeByteArray# bytes s3 of
        (# s4, frozen #) -> case labelThread# tid frozen s4 of
          s5 -> case observe tid s5 of (# _, value #) -> value)

{-# OPAQUE awaitFinished #-}
awaitFinished :: ThreadId# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
awaitFinished tid fuel s = case threadStatus# tid s of
  (# s1, 16#, _, _ #) -> (# s1, 1# #)
  (# s1, _, _, _ #) -> case fuel ># 0# of
    0# -> (# s1, 0# #)
    _ -> case yield# s1 of s2 -> awaitFinished tid (fuel -# 1#) s2

{-# OPAQUE deadLabel #-}
deadLabel :: Int# -> Int#
deadLabel seed = case fork# (\s -> case myThreadId# s of
  (# s1, tid #) -> case setLabel tid seed s1 of s2 -> (# s2, () #)) realWorld# of
    (# s1, tid #) -> case awaitFinished tid 1000000# s1 of
      (# s2, 1# #) -> case observe tid s2 of (# _, value #) -> value
      (# _, _ #) -> -999#

{-# OPAQUE deadOverwrite #-}
deadOverwrite :: Int# -> Int#
deadOverwrite seed = case fork# (\s -> (# s, () #)) realWorld# of
  (# s1, tid #) -> case awaitFinished tid 1000000# s1 of
    (# s2, 1# #) -> case observe tid s2 of
      (# s3, before #) -> case setLabel tid seed s3 of
        s4 -> case observe tid s4 of
          (# _, value #) -> case before ==# -1# of
            1# -> value
            _ -> -999#
    (# _, _ #) -> -999#
