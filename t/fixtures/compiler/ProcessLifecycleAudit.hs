-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ProcessLifecycleAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for process lifecycle audit Core and metadata.
module ProcessLifecycleAudit where

import GHC.Exts

-- The producer specializes these typed consumers with actual FCallIds read
-- from the installed process package. No replacement foreign imports or target
-- metadata are constructed here.
type CreateCall = Addr# -> Addr# -> Addr# -> Int32# -> Int32# -> Int32# ->
    Addr# -> Addr# -> Addr# -> Addr# -> Addr# -> Int32# -> Addr# ->
    State# RealWorld -> (# State# RealWorld, Int32# #)
type StatusCall = Int32# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type TerminateCall = Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)

processCreate :: CreateCall -> Addr# -> Addr# -> Addr# -> Int# -> Int# -> Int# ->
    Addr# -> Addr# -> Addr# -> Addr# -> Addr# -> Int# -> Addr# -> Int#
processCreate call argv cwd env input output err inputResult outputResult errorResult group user flags failure =
  case call argv cwd env (intToInt32# input) (intToInt32# output) (intToInt32# err)
       inputResult outputResult errorResult group user (intToInt32# flags) failure realWorld# of
    (# _, pid #) -> int32ToInt# pid

processPoll :: StatusCall -> Int# -> Addr# -> Int#
processPoll call pid destination = case call (intToInt32# pid) destination realWorld# of
  (# _, status #) -> int32ToInt# status

processWait :: StatusCall -> Int# -> Addr# -> Int#
processWait call pid destination = case call (intToInt32# pid) destination realWorld# of
  (# _, status #) -> int32ToInt# status

processTerminate :: TerminateCall -> Int# -> Int#
processTerminate call pid = case call (intToInt32# pid) realWorld# of
  (# _, status #) -> int32ToInt# status
