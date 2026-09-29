-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
-- | Immutable native Truffle strings. Encoding 0 is UTF-8, 7 is UTF-16BE.
module StringPrimitives where

import GHC.Exts
import THC.Prim

-- A, é and U+1F600: three code points, seven UTF-8 bytes.
unicode :: Int# -> TruffleString#
unicode encoding = case truffleStringEncoding# encoding of
  e -> truffleStringConcat# e
    (truffleStringFromCodePoint# e 65#)
    (truffleStringConcat# e (truffleStringFromCodePoint# e 233#)
      (truffleStringFromCodePoint# e 128512#))

unicodeStats :: Int# -> (# Int#, Int#, Int#, Int#, Int# #)
unicodeStats encoding = case truffleStringEncoding# encoding of
  e -> case unicode encoding of
    s -> (# truffleStringByteLength# e s, truffleStringCodePointLength# e s,
      truffleStringCodePointAt# e s 2#, truffleStringCodePointToByteIndex# e s 0# 2#,
      truffleStringIsValid# e s #)

roundTrip :: Int# -> Int# -> ByteArray#
roundTrip from to = case truffleStringEncoding# from of
  a -> case truffleStringEncoding# to of
    b -> truffleStringToByteArray# a
      (truffleStringSwitchEncoding# a (truffleStringSwitchEncoding# b (unicode from)))

sliceRepeat :: Int# -> ByteArray#
sliceRepeat encoding = case truffleStringEncoding# encoding of
  e -> truffleStringToByteArray# e
    (truffleStringRepeat# e (truffleStringSubstring# e (unicode encoding) 1# 2#) 2#)

copyBytes :: Int# -> ByteArray# -> Int# -> Int# -> TruffleString#
copyBytes encoding bytes offset size = truffleStringFromByteArray#
  (truffleStringEncoding# encoding) bytes offset size

numericRoundTrip :: Int64# -> Int64#
numericRoundTrip n = truffleStringParseInt64#
  (truffleStringFromInt64# (truffleStringEncoding# 0#) n) 10#

codePointSum :: Int# -> TruffleString# -> Int#
codePointSum encoding s = case truffleStringEncoding# encoding of
  e -> let go i acc = case i ==# truffleStringCodePointLength# e s of
             1# -> acc
             _ -> go (i +# 1#) (acc +# truffleStringCodePointAt# e s i)
       in go 0# 0#

nativeString :: Object# s -> State# s -> (# State# s, TruffleString# #)
nativeString value s = asTruffleString# value (getInteropLibrary# value) s

rawString :: TruffleString# -> Object# s
rawString = truffleStringAsObject#
