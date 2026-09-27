-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
-- |
-- Module      : NarrowIntegerTransport
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC 9.14.1
--
-- Original narrow primitive values crossing storage and continuation boundaries.
module NarrowIntegerTransport where
import GHC.Exts

data Fields = Fields Int8# Word8# Int16# Word16# Int32# Word32#

{-# OPAQUE makeFields #-}
makeFields :: Int# -> Fields
makeFields x = Fields (intToInt8# x) (wordToWord8# (int2Word# x))
  (intToInt16# x) (wordToWord16# (int2Word# x))
  (intToInt32# x) (wordToWord32# (int2Word# x))

{-# OPAQUE combine #-}
combine :: Int8# -> Word8# -> Int16# -> Word16# -> Int32# -> Word32# -> Int#
combine a b c d e f = int8ToInt# a +# word2Int# (word8ToWord# b) +#
  int16ToInt# c +# word2Int# (word16ToWord# d) +#
  int32ToInt# e +# word2Int# (word32ToWord# f)

{-# OPAQUE consumeFields #-}
consumeFields :: Fields -> Int#
consumeFields (Fields a b c d e f) = combine a b c d e f

{-# OPAQUE fieldsCase #-}
fieldsCase :: Int# -> Int#
fieldsCase x = consumeFields (makeFields x)

{-# OPAQUE makeTuple #-}
makeTuple :: Int# -> (# Int8#, Word8#, Int16#, Word16#, Int32#, Word32# #)
makeTuple x = (# intToInt8# x, wordToWord8# (int2Word# x),
  intToInt16# x, wordToWord16# (int2Word# x),
  intToInt32# x, wordToWord32# (int2Word# x) #)

{-# OPAQUE tupleCase #-}
tupleCase :: Int# -> Int#
tupleCase x = case makeTuple x of (# a,b,c,d,e,f #) -> combine a b c d e f

{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply f x = f x

{-# OPAQUE captureCase #-}
captureCase :: Int# -> Int#
captureCase x = case makeTuple x of
  (# a,b,c,d,e,f #) -> apply (\y -> combine a b c d e f +# y) (x +# 19#)

{-# OPAQUE papCase #-}
papCase :: Int# -> Int#
papCase x = case makeTuple x of
  (# a,b,c,d,e,f #) -> apply (\y -> (combine a b c d) e f +# y) (x -# 7#)

{-# OPAQUE finishPartial #-}
finishPartial :: Int8# -> Word32# -> Int# -> Int#
finishPartial a b c = int8ToInt# a +# word2Int# (word32ToWord# b) +# c

{-# OPAQUE retainedPapCase #-}
retainedPapCase :: Int# -> Int#
retainedPapCase x = apply (finishPartial (intToInt8# x) (wordToWord32# (int2Word# x))) (x +# 31#)

{-# OPAQUE sumMake #-}
sumMake :: Int# -> (# Int8# | (# Word16#, Int32# #) | Word32# #)
sumMake x = case andI# x 3# of
  0# -> (# intToInt8# x | | #)
  1# -> (# | (# wordToWord16# (int2Word# x), intToInt32# x #) | #)
  _ -> (# | | wordToWord32# (int2Word# x) #)

{-# OPAQUE sumCase #-}
sumCase :: Int# -> Int#
sumCase x = case sumMake x of
  (# a | | #) -> int8ToInt# a
  (# | (# a,b #) | #) -> word2Int# (word16ToWord# a) +# int32ToInt# b
  (# | | a #) -> word2Int# (word32ToWord# a)

{-# OPAQUE arithmeticCase #-}
arithmeticCase :: Int# -> Int#
arithmeticCase x = combine
  (plusInt8# (intToInt8# x) (intToInt8# 13#))
  (timesWord8# (wordToWord8# (int2Word# x)) (wordToWord8# 17##))
  (subInt16# (intToInt16# x) (intToInt16# 129#))
  (plusWord16# (wordToWord16# (int2Word# x)) (wordToWord16# 65535##))
  (timesInt32# (intToInt32# x) (intToInt32# 65537#))
  (quotWord32# (wordToWord32# (int2Word# x)) (wordToWord32# 3##))

{-# OPAQUE maskedCase #-}
maskedCase :: Int# -> Int#
maskedCase x = runRW# (\s -> case maskAsyncExceptions#
  (\s0 -> (# s0, intToInt16# x #)) s of (# _, v #) -> int16ToInt# v)

{-# OPAQUE byteArrayCase #-}
byteArrayCase :: Int# -> Int#
byteArrayCase x = runRW# (\s0 -> case newByteArray# 16# s0 of
  (# s1, a #) -> case writeInt8Array# a 0# (intToInt8# x) s1 of
    s2 -> case writeWord8Array# a 1# (wordToWord8# (int2Word# x)) s2 of
      s3 -> case writeInt16Array# a 1# (intToInt16# x) s3 of
        s4 -> case writeWord16Array# a 2# (wordToWord16# (int2Word# x)) s4 of
          s5 -> case writeInt32Array# a 2# (intToInt32# x) s5 of
            s6 -> case writeWord32Array# a 3# (wordToWord32# (int2Word# x)) s6 of
              s7 -> case readInt8Array# a 0# s7 of
                (# s8, b #) -> case readWord8Array# a 1# s8 of
                  (# s9, c #) -> case readInt16Array# a 1# s9 of
                    (# s10, d #) -> case readWord16Array# a 2# s10 of
                      (# s11, e #) -> case readInt32Array# a 2# s11 of
                        (# s12, f #) -> case readWord32Array# a 3# s12 of
                          (# _, g #) -> combine b c d e f g)

{-# OPAQUE vectorCase #-}
vectorCase :: Int# -> Int#
vectorCase x = case unpackWord32X4# (broadcastWord32X4# (wordToWord32# (int2Word# x))) of
  (# a,b,c,d #) -> word2Int# (word32ToWord# (plusWord32# (plusWord32# a b) (plusWord32# c d)))

entries :: [(String, Int -> Int)]
entries = [("fieldsCase", \(I# x) -> I# (fieldsCase x)),
  ("tupleCase", \(I# x) -> I# (tupleCase x)),
  ("captureCase", \(I# x) -> I# (captureCase x)),
  ("papCase", \(I# x) -> I# (papCase x)),
  ("retainedPapCase", \(I# x) -> I# (retainedPapCase x)),
  ("sumCase", \(I# x) -> I# (sumCase x)),
  ("arithmeticCase", \(I# x) -> I# (arithmeticCase x)),
  ("maskedCase", \(I# x) -> I# (maskedCase x)),
  ("byteArrayCase", \(I# x) -> I# (byteArrayCase x)),
  ("vectorCase", \(I# x) -> I# (vectorCase x))]
