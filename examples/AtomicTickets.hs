-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module AtomicTickets where
import GHC.Exts

-- A ticket dispenser reserves a block and returns its first ticket.
-- keepAlive# retains the allocation while the Addr# is in use.
{-# OPAQUE reserveTickets #-}
reserveTickets :: Int# -> Int# -> Int#
reserveTickets initial count = runRW# (\s0 ->
  case newPinnedByteArray# 8# s0 of { (# s1, bytes #) ->
  case mutableByteArrayContents# bytes of { address ->
  case keepAlive# bytes s1 (\s2 ->
    case atomicWriteWordAddr# address (int2Word# initial) s2 of { s3 ->
    case fetchAddWordAddr# address (int2Word# count) s3 of { (# s4, first #) ->
      (# s4, word2Int# first #)
    } }) of { (# _, ticket #) -> ticket }
  } })
