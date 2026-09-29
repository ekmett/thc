-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : THC.CachedText
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC primitive types and operations
--
-- Genuine Text construction and transformation through the selected cache.
module THC.CachedText (calculate) where

import Data.Bits ((.&.))
import Data.Char (ord)
import qualified Data.Text as T
import GHC.Exts

{-# OPAQUE packed #-}
packed :: Int# -> T.Text
packed count = T.pack (go (max 0 (min 192 (I# count))))
  where
    go 0 = []
    go n = character (n .&. 7) : go (n - 1)
    character 0 = '\0'
    character 1 = '\x7ff'
    character 2 = '\x800'
    character 3 = '\x1f600'
    character 4 = '\xd800'
    character _ = 'A'

{-# OPAQUE mapped #-}
mapped :: Int# -> T.Text -> T.Text
mapped mode = T.map (\c -> if isTrue# (andI# mode 1# ==# 0#) && c == 'A' then '\x1f600' else c)

{-# OPAQUE filtered #-}
filtered :: Int# -> T.Text -> T.Text
filtered mode = T.filter (\c -> isTrue# (andI# mode 2# ==# 0#) && c /= '\0')

{-# OPAQUE shared #-}
shared :: T.Text
shared = T.pack ['A', '\x3bb', '\x1f600', '\0', 'Z']

{-# OPAQUE score #-}
score :: T.Text -> Int#
score text = case T.foldl' (\acc c -> (acc * 33 + ord c) .&. 2147483647) 0 text of
  I# result -> result

-- | Dynamic character count and transformation selector; large counts exercise
-- buffer growth, and all paths preserve Text's original Unicode replacement.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int#
calculate count mode = score (filtered mode (mapped mode (packed count))) +# score shared +# score shared
