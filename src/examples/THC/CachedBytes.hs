-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : THC.CachedBytes
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC primitive types and operations
--
-- Pure literal decoding and byte-buffer construction for the selected cache.
-- The installed ShortByteString implementation owns packing and slicing.
module THC.CachedBytes (calculate) where

import GHC.Exts
import GHC.Word (Word8(W8#))
import GHC.Internal.CString (unpackCString#, unpackCStringUtf8#)
import qualified Data.ByteString.Short as S

{-# OPAQUE decoded #-}
decoded :: Int# -> [Char]
decoded selector = case andI# selector 3# of
  0# -> unpackCString# "A\255\128\0Z"#
  1# -> unpackCStringUtf8# "\206\187\240\159\152\128"#
  2# -> unpackCString# ""#
  _ -> unpackCString# "\0ignored"#

{-# OPAQUE packed #-}
packed :: Int# -> Int# -> S.ShortByteString
packed seed selector = S.pack (prefix (decoded selector))
  where
    prefix [] = suffix (andI# seed 15#)
    prefix (C# character : rest) =
      W8# (wordToWord8# (int2Word# (ord# character))) : prefix rest
    suffix count = case count of
      0# -> []
      _ -> W8# (wordToWord8# (int2Word# (seed +# count *# 73#))) : suffix (count -# 1#)

{-# OPAQUE shared #-}
shared :: S.ShortByteString
shared = S.pack [W8# (wordToWord8# 0##), W8# (wordToWord8# 255##),
                 W8# (wordToWord8# 128##), W8# (wordToWord8# 65##)]

{-# OPAQUE score #-}
score :: S.ShortByteString -> Int#
score bytes = go (S.unpack bytes) 0#
  where
    go [] result = result
    go (W8# byte : rest) result = go rest (result *# 33# +# word2Int# (word8ToWord# byte))

-- | Decode literals, pack dynamic bytes, and select a prefix or suffix.
-- Negative and out-of-range slice counts retain ShortByteString semantics.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int# -> Int#
calculate seed count selector =
  let bytes = packed seed selector
      sliced = case andI# selector 4# of
        0# -> S.take (I# count) bytes
        _ -> S.drop (I# count) bytes
  in score sliced +# score shared +# score shared
