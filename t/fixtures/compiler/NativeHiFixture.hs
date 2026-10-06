-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE MagicHash #-}
module NativeHiFixture (entry, Record(..), SeparateSurrogates, Supplementary) where

import GHC.Exts (Int#, (+#), (-#))

data Record = Record { field :: Int }

-- GHC Symbols contain Char values: these two spellings have distinct meanings
-- even though both collapse to the same UTF-16 string on the JVM.
type SeparateSurrogates = "\xD800\xDC00"
type Supplementary = "\x10000"

{-# NOINLINE privateLoop #-}
privateLoop :: Int# -> Int# -> Int#
privateLoop n acc = case n of
  0# -> acc
  _ -> privateLoop (n -# 1#) (acc +# n)

{-# DEPRECATED entry "warning\0text \xD800\& \xDC00\& \x1F642 é" #-}
{-# NOINLINE entry #-}
entry :: Int# -> Int#
entry n = privateLoop n 0#
