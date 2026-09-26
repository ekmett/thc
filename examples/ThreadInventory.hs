-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ThreadInventory where

import GHC.Exts

-- A snapshot is an ordinary Array# of unlifted identities. Do not assume a
-- stable ordering or that finished threads disappear immediately.
{-# OPAQUE occurrences #-}
occurrences :: ThreadId# -> Array# ThreadId# -> Int# -> Int#
occurrences wanted threads i = case i <# sizeofArray# threads of
  0# -> 0#
  _ -> case indexArray# threads i of
    (# tid #) -> reallyUnsafePtrEquality# wanted tid +# occurrences wanted threads (i +# 1#)

-- Useful scalar entry for the THC runner: ten means exactly one self identity
-- and an unbound guest. The token makes first-compiled observations distinct.
{-# OPAQUE selfInventory #-}
selfInventory :: Int# -> Int#
selfInventory token = runRW# (\s -> case myThreadId# s of
  (# s1, self #) -> case listThreads# s1 of
    (# s2, threads #) -> case isCurrentThreadBound# s2 of
      (# _, bound #) -> token +# 10# *# occurrences self threads 0# +# bound)

{-# OPAQUE boundQuery #-}
boundQuery :: Int# -> Int#
boundQuery token = runRW# (\s -> case isCurrentThreadBound# s of (# _, bound #) -> token +# bound)

{-# OPAQUE snapshotSize #-}
snapshotSize :: Int# -> Int#
snapshotSize token = runRW# (\s -> case listThreads# s of (# _, threads #) -> token +# sizeofArray# threads)

-- Bytecode's existing fork# path supplies actual concurrency. The child stays
-- alive across acquisition; the same immutable snapshot remains usable after
-- it signals completion. Neither scheduling order nor the RTS's background thread count
-- is used as an oracle.
{-# OPAQUE forkSnapshot #-}
forkSnapshot :: Int# -> Int#
forkSnapshot token = runRW# (\s ->
  case newMVar# s of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, done #) ->
  case fork# (\s4 -> case putMVar# ready () s4 of { s5 ->
    case takeMVar# gate s5 of { (# s6, _ #) ->
    case putMVar# done () s6 of { s7 -> (# s7, () #) } } }) s3 of { (# s4, child #) ->
  case takeMVar# ready s4 of { (# s5, _ #) ->
  case myThreadId# s5 of { (# s6, self #) ->
  case listThreads# s6 of { (# s7, threads #) ->
  case occurrences self threads 0# +# 10# *# occurrences child threads 0# of { before ->
  case putMVar# gate () s7 of { s8 ->
  case takeMVar# done s8 of { (# _, _ #) ->
    token +# before +# 100# *# occurrences child threads 0#
  } } } } } } } } } })
