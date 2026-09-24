-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, ScopedTypeVariables #-}
module ArraySliceAudit where
import GHC.Exts
import GHC.Arr (Array(..))
import Control.Monad.ST (ST, runST)
import qualified Data.Array as A
import Data.Array.ST (STArray, runSTArray, newArray, readArray, writeArray, freeze, thaw)

-- These genuinely bottom values stay in copied, unselected cells.
{-# OPAQUE bottomInt #-}
bottomInt :: Int
bottomInt = bottomInt
{-# OPAQUE bottomFunction #-}
bottomFunction :: Int -> Int
bottomFunction = bottomFunction

{-# OPAQUE sliceSnapshots #-}
sliceSnapshots :: Int# -> Int#
sliceSnapshots raw = case runRW# (\s ->
  case newArray# 6# bottomInt s of { (# s1, a #) ->
  case writeArray# a 1# (I# raw + 1) s1 of { s2 ->
  case writeArray# a 2# (I# raw + 2) s2 of { s3 ->
  case writeArray# a 3# (I# raw + 3) s3 of { s4 ->
  case writeArray# a 4# (I# raw + 4) s4 of { s5 ->
  case freezeArray# a 1# 4# s5 of { (# s6, frozen #) ->
  case writeArray# a 2# (I# raw + 20) s6 of { s7 ->
  case cloneArray# frozen 1# 2# of { cloned ->
  case thawArray# cloned 0# 2# s7 of { (# s8, b #) ->
  case writeArray# b 0# (I# raw + 30) s8 of { s9 ->
  case freezeArray# b 0# 2# s9 of { (# s10, snapshot #) ->
  case writeArray# b 1# (I# raw + 40) s10 of { s11 ->
  case readArray# b 1# s11 of { (# s12, now #) ->
  case readArray# a 2# s12 of { (# _, originalNow #) ->
  case indexArray# frozen 1# of { (# old #) ->
  case indexArray# cloned 0# of { (# copied #) ->
  case indexArray# snapshot 0# of { (# first #) ->
  case indexArray# snapshot 1# of { (# lastValue #) ->
    old*3 + copied*5 + first*7 + lastValue*11 + now*13 + originalNow*17
  }}}}}}}}}}}}}}}}}}) of I# answer -> answer

{-# OPAQUE lazySlices #-}
lazySlices :: Int# -> Int#
lazySlices raw = case runRW# (\s ->
  case newArray# 6# bottomInt s of { (# s1, a #) ->
  case writeArray# a 2# (I# raw + 7) s1 of { s2 ->
  case freezeArray# a 0# 6# s2 of { (# s3, frozen #) ->
  case cloneArray# frozen 0# 6# of { copied ->
  case thawArray# copied 0# 6# s3 of { (# s4, b #) ->
  case readArray# b 0# s4 of { (# s5, ignored #) ->
  case writeArray# b 2# (I# raw + 19) s5 of { s6 ->
  case freezeArray# b 0# 6# s6 of { (# _, final #) ->
  case indexArray# frozen 2# of { (# old #) ->
  case indexArray# final 2# of { (# now #) -> old*3 + now*5 }
  }}}}}}}}}) of I# answer -> answer

{-# OPAQUE closureSlices #-}
closureSlices :: Int# -> Int#
closureSlices raw = case runRW# (\s ->
  case newArray# 2# bottomFunction s of { (# s1, a #) ->
  case writeArray# a 1# (\n -> n + I# raw) s1 of { s2 ->
  case freezeArray# a 0# 2# s2 of { (# s3, frozen #) ->
  case cloneArray# frozen 0# 2# of { copied ->
  case thawArray# copied 0# 2# s3 of { (# s4, b #) ->
  case readArray# b 1# s4 of { (# _, f #) -> f (I# raw + 1) }
  }}}}}) of I# answer -> answer

{-# OPAQUE keepArray #-}
keepArray :: Array# Int -> Int# -> Int#
keepArray _ x = x
{-# OPAQUE zeroSlices #-}
zeroSlices :: Int# -> Int#
zeroSlices raw = case runRW# (\s ->
  case newArray# 0# bottomInt s of { (# s1, a #) ->
  case freezeArray# a 0# 0# s1 of { (# s2, frozen #) ->
  case cloneArray# frozen 0# 0# of { copied ->
  case thawArray# copied 0# 0# s2 of { (# s3, b #) ->
  case freezeArray# b 0# 0# s3 of { (# _, final #) -> I# (keepArray final raw) }
  }}}}) of I# answer -> answer

-- Public STArray construction and public Array indexing around the raw copying
-- operations. Known bounds discharge the checked-index paths without source edits.
{-# OPAQUE publicSlices #-}
publicSlices :: Int# -> Int#
publicSlices raw =
  let source :: A.Array Int Int
      source = runSTArray $ do
        a <- newArray (0,3) bottomInt
        writeArray a 1 (I# raw+5)
        writeArray a 2 (I# raw+11)
        pure a
  in case source of
    Array _ _ _ storage -> case cloneArray# storage 1# 2# of
      copied -> case runRW# (\s -> case thawArray# copied 0# 2# s of
        (# s1, b #) -> case writeArray# b 0# (I# raw+23) s1 of
          s2 -> case freezeArray# b 0# 2# s2 of
            (# _, final #) -> let frozen = Array 0 1 2 final :: A.Array Int Int
                             in (source A.! 1)*3 + (frozen A.! 0)*5 + (frozen A.! 1)*7) of I# answer -> answer

-- Ordinary public freeze/thaw still retains arrEleBottom's real error closure.
-- It is native-checked but never counted as strict THC execution support.
{-# OPAQUE publicFreezeThaw #-}
publicFreezeThaw :: Int# -> Int#
publicFreezeThaw raw = case runST go of I# answer -> answer
 where
  go :: forall s. ST s Int
  go = do
    a <- newArray (0,1) (I# raw) :: ST s (STArray s Int Int)
    frozen <- freeze a :: ST s (A.Array Int Int)
    writeArray a 0 (I# raw+13)
    b <- thaw frozen :: ST s (STArray s Int Int)
    writeArray b 1 (I# raw+17)
    old <- readArray a 0
    now <- readArray b 1
    pure ((frozen A.! 0)*3 + old*5 + now*7)
