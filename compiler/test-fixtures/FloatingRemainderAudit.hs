-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FloatingRemainderAudit where
import GHC.Exts
import GHC.Prim (minFloat#, maxFloat#, minDouble#, maxDouble#)

{-# OPAQUE asinhFloat #-}
asinhFloat :: Int# -> Int# -> Int#
asinhFloat a _ = word2Int# (word32ToWord# (castFloatToWord32# (asinhFloat#
  (castWord32ToFloat# (wordToWord32# (int2Word# a))))))

{-# OPAQUE acoshFloat #-}
acoshFloat :: Int# -> Int# -> Int#
acoshFloat a _ = word2Int# (word32ToWord# (castFloatToWord32# (acoshFloat#
  (castWord32ToFloat# (wordToWord32# (int2Word# a))))))

{-# OPAQUE atanhFloat #-}
atanhFloat :: Int# -> Int# -> Int#
atanhFloat a _ = word2Int# (word32ToWord# (castFloatToWord32# (atanhFloat#
  (castWord32ToFloat# (wordToWord32# (int2Word# a))))))

{-# OPAQUE minFloat #-}
minFloat :: Int# -> Int# -> Int#
minFloat a b = word2Int# (word32ToWord# (castFloatToWord32# (minFloat#
  (castWord32ToFloat# (wordToWord32# (int2Word# a)))
  (castWord32ToFloat# (wordToWord32# (int2Word# b))))))

{-# OPAQUE maxFloat #-}
maxFloat :: Int# -> Int# -> Int#
maxFloat a b = word2Int# (word32ToWord# (castFloatToWord32# (maxFloat#
  (castWord32ToFloat# (wordToWord32# (int2Word# a)))
  (castWord32ToFloat# (wordToWord32# (int2Word# b))))))

{-# OPAQUE asinhDouble #-}
asinhDouble :: Int# -> Int# -> Int#
asinhDouble a _ = word2Int# (word64ToWord# (castDoubleToWord64# (asinhDouble#
  (castWord64ToDouble# (wordToWord64# (int2Word# a))))))

{-# OPAQUE acoshDouble #-}
acoshDouble :: Int# -> Int# -> Int#
acoshDouble a _ = word2Int# (word64ToWord# (castDoubleToWord64# (acoshDouble#
  (castWord64ToDouble# (wordToWord64# (int2Word# a))))))

{-# OPAQUE atanhDouble #-}
atanhDouble :: Int# -> Int# -> Int#
atanhDouble a _ = word2Int# (word64ToWord# (castDoubleToWord64# (atanhDouble#
  (castWord64ToDouble# (wordToWord64# (int2Word# a))))))

{-# OPAQUE minDouble #-}
minDouble :: Int# -> Int# -> Int#
minDouble a b = word2Int# (word64ToWord# (castDoubleToWord64# (minDouble#
  (castWord64ToDouble# (wordToWord64# (int2Word# a)))
  (castWord64ToDouble# (wordToWord64# (int2Word# b))))))

{-# OPAQUE maxDouble #-}
maxDouble :: Int# -> Int# -> Int#
maxDouble a b = word2Int# (word64ToWord# (castDoubleToWord64# (maxDouble#
  (castWord64ToDouble# (wordToWord64# (int2Word# a)))
  (castWord64ToDouble# (wordToWord64# (int2Word# b))))))

{-# OPAQUE decodeWordsDirect #-}
decodeWordsDirect :: Int# -> Int# -> Int#
decodeWordsDirect bits field = case decodeDouble_2Int# (castWord64ToDouble# (wordToWord64# (int2Word# bits))) of
  (# s, h, l, e #) -> case field of 0# -> s; 1# -> word2Int# h; 2# -> word2Int# l; _ -> e

{-# OPAQUE decodeWordsWorker #-}
decodeWordsWorker :: Double# -> (# Int#, Word#, Word#, Int# #)
decodeWordsWorker x = case decodeDouble_2Int# x of (# s, h, l, e #) -> (# s, h, l, e +# 1# #)

{-# OPAQUE decodeWordsCall #-}
decodeWordsCall :: Int# -> Int# -> Int#
decodeWordsCall bits field = case decodeWordsWorker (castWord64ToDouble# (wordToWord64# (int2Word# bits))) of
  (# s, h, l, e #) -> case field of 0# -> s; 1# -> word2Int# h; 2# -> word2Int# l; _ -> e -# 1#
