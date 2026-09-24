-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import GHC.Exts (Addr#, Char(C#), Int(I#), Int#, (+#), (==#), ord#, raise#,
                 newMutVar#, readMutVar#, writeMutVar#)
import GHC.IO (IO(..))
import GHC.Internal.CString (unpackCString#)

{-# NOINLINE score #-}
score :: Addr# -> Int
score address = I# (go (unpackCString# address))
  where
    go :: [Char] -> Int#
    go [] = 0#
    go (C# ch : tailChars) = ord# ch +# go tailChars

{-# OPAQUE main #-}
main :: IO ()
main = IO $ \state0 ->
  case newMutVar# (I# 0#) state0 of { (# state1, ref #) ->
  case score ""# of { I# empty ->
  case score "ABC"# of { I# ascii ->
  case score "\255\128"# of { I# high ->
  case score "A\0Z"# of { I# terminated ->
  case writeMutVar# ref (I# (empty +# ascii +# high +# terminated)) state1 of { state2 ->
  case readMutVar# ref state2 of { (# state3, boxed #) ->
  case boxed of { I# actual ->
  case actual ==# 646# of {
    1# -> (# state3, () #);
    _ -> raise# boxed
  }}}}}}}}}
