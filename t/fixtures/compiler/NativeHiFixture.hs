-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module NativeHiFixture (entry, Record(..)) where

import GHC.Exts (Int#, (+#), (-#))

data Record = Record { field :: Int }

{-# NOINLINE privateLoop #-}
privateLoop :: Int# -> Int# -> Int#
privateLoop n acc = case n of
  0# -> acc
  _ -> privateLoop (n -# 1#) (acc +# n)

{-# NOINLINE entry #-}
entry :: Int# -> Int#
entry n = privateLoop n 0#
