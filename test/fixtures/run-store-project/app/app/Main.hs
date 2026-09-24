-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where
import GHC.Exts
import GHC.IO (IO(..))
import Answer (answerValue)

main :: IO ()
main = IO $ \s0 ->
  case newMutVar# (I# 41#) s0 of { (# s1, ref #) ->
  case writeMutVar# ref answerValue s1 of { s2 ->
  case readMutVar# ref s2 of { (# s3, boxed #) ->
  case boxed of { I# answer ->
  case answer ==# 42# of {
    1# -> (# s3, () #);
    _ -> raise# boxed
  }}}}}
