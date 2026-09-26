-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedFFITypes, ForeignFunctionInterface #-}
module Main (main) where

import GHC.Exts
import GHC.IO (IO(..))

foreign import ccall unsafe "variant_sum" readAddress :: Addr# -> Word# -> IO Word
foreign import ccall unsafe "variant_sum" readBytes :: ByteArray# -> Word# -> IO Word

-- Keep the negative control boxed; tuple-bottom lowering is a separate test.
{-# OPAQUE checkWord #-}
checkWord :: Word# -> Word# -> ()
checkWord actual expected = case eqWord# actual expected of
  1# -> ()
  _ -> raise# (W# actual)

main :: IO ()
main = IO $ \s0 ->
  case newPinnedByteArray# 4# s0 of { (# s1, mutable #) ->
  case writeWord8Array# mutable 0# (wordToWord8# 1##) s1 of { s2 ->
  case writeWord8Array# mutable 1# (wordToWord8# 2##) s2 of { s3 ->
  case writeWord8Array# mutable 2# (wordToWord8# 3##) s3 of { s4 ->
  case writeWord8Array# mutable 3# (wordToWord8# 4##) s4 of { s5 ->
  case unsafeFreezeByteArray# mutable s5 of { (# s6, bytes #) ->
  case readBytes bytes 4## of { IO first ->
  case first s6 of { (# s7, W# whole #) ->
  case readAddress (plusAddr# (byteArrayContents# bytes) 1#) 3## of { IO second ->
  case second s7 of { (# s8, W# interior #) ->
  case touch# bytes s8 of { s9 ->
  case checkWord whole 10## of { () ->
  case checkWord interior 9## of { () -> (# s9, () #)
  }}}}}}}}}}}}}
