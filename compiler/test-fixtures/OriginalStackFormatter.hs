-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- Test inputs and an output observer only: the formatter is the original GHC function.
module OriginalStackFormatter (formatOriginal) where

import GHC.Exts (Int#, Char(C#), ord#, (-#))
import GHC.Internal.ClosureTypes (ClosureType(RET_SMALL))
import GHC.Internal.Stack.Decode (StackEntry(..), prettyStackEntry)

{-# NOINLINE formatOriginal #-}
formatOriginal :: Int# -> Int# -> Int#
formatOriginal row position =
  let entry = case row of
        0# -> StackEntry "entry" "Main" "Fixture.hs:12:3-12:17" RET_SMALL
        1# -> StackEntry "" "" "" RET_SMALL
        2# -> StackEntry "$wdecode" "GHC.Internal.Stack.Decode" "<source unavailable>" RET_SMALL
        3# -> StackEntry "λ雪😀" "Módulo.例" "路径.hs:1:2" RET_SMALL
        4# -> StackEntry "a.b (c)" "M\tN" "line\nspan" RET_SMALL
        _ -> StackEntry "\0last" "Null" "zero\0location" RET_SMALL
  in characterAt position (prettyStackEntry entry)

characterAt :: Int# -> String -> Int#
characterAt position text = case text of
  [] -> -1#
  C# c : rest -> case position of
    0# -> ord# c
    _ -> characterAt (position -# 1#) rest
