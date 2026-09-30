-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : WindowsCodePageAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1; native Windows interfaces
--
-- Typed consumers of the original Windows encoding and error declarations.
module WindowsCodePageAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))

-- These are typed consumers, never replacement foreign declarations. The
-- producer supplies real FCallIds from the unchanged ghc-internal interfaces.
type PageCall = State# RealWorld -> (# State# RealWorld, Word32# #)
type InfoCall = Word32# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int# #)
type LeadCall = Word32# -> Word8# -> State# RealWorld -> (# State# RealWorld, Int# #)
type MultiCall = Word32# -> Word32# -> Addr# -> Int32# -> Addr# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type WideCall = Word32# -> Word32# -> Addr# -> Int32# -> Addr# -> Int32# -> Addr# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type MapCall = Word32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type MapStateCall = State# RealWorld -> (# State# RealWorld #)
type MessageCall = Word32# -> State# RealWorld -> (# State# RealWorld, Addr# #)
type FreeCall = Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)

ansiPage, consolePage, windowsError :: PageCall -> Int# -> Word#
ansiPage call _ = case call realWorld# of (# _, value #) -> word32ToWord# value
consolePage call _ = case call realWorld# of (# _, value #) -> word32ToWord# value
windowsError call _ = case call realWorld# of (# _, value #) -> word32ToWord# value
pageInfo :: InfoCall -> Word# -> Addr# -> Int#
pageInfo call page output = case call (wordToWord32# page) output realWorld# of (# _, value #) -> value
leadByte :: LeadCall -> Word# -> Word# -> Int#
leadByte call page byte = case call (wordToWord32# page) (wordToWord8# byte) realWorld# of (# _, value #) -> value
multiByte :: MultiCall -> Word# -> Word# -> Addr# -> Int# -> Addr# -> Int# -> Int#
multiByte call page flags input count output capacity =
  case call (wordToWord32# page) (wordToWord32# flags) input (intToInt32# count) output (intToInt32# capacity) realWorld# of
    (# _, value #) -> int32ToInt# value
wideChar, wideCharSafe :: WideCall -> Word# -> Word# -> Addr# -> Int# -> Addr# -> Int# -> Addr# -> Addr# -> Int#
wideChar call page flags input count output capacity def used =
  case call (wordToWord32# page) (wordToWord32# flags) input (intToInt32# count) output (intToInt32# capacity) def used realWorld# of
    (# _, value #) -> int32ToInt# value
wideCharSafe = wideChar
mapError :: MapCall -> Word# -> Int#
mapError call value = case call (wordToWord32# value) realWorld# of (# _, result #) -> int32ToInt# result
mapCurrentError :: MapStateCall -> Int# -> Int#
mapCurrentError call _ = case call realWorld# of (# _ #) -> 0#
errorMessage :: MessageCall -> Word# -> Addr#
errorMessage call value = case call (wordToWord32# value) realWorld# of (# _, result #) -> result
localFree :: FreeCall -> Addr# -> Addr#
localFree call value = case call value realWorld# of (# _, result #) -> result

nativeAnsiPage, nativeConsolePage, nativeWindowsError :: PageCall -> IO Word
nativeAnsiPage call = IO (\state -> case call state of (# next, value #) -> (# next, W# (word32ToWord# value) #))
nativeConsolePage call = IO (\state -> case call state of (# next, value #) -> (# next, W# (word32ToWord# value) #))
nativeWindowsError call = IO (\state -> case call state of (# next, value #) -> (# next, W# (word32ToWord# value) #))
nativePageInfo :: InfoCall -> Word -> Ptr () -> IO Int
nativePageInfo call (W# page) (Ptr output) = IO (\state ->
  case call (wordToWord32# page) output state of (# next, value #) -> (# next, I# value #))
nativeLeadByte :: LeadCall -> Word -> Word -> IO Int
nativeLeadByte call (W# page) (W# byte) = IO (\state ->
  case call (wordToWord32# page) (wordToWord8# byte) state of (# next, value #) -> (# next, I# value #))
nativeMultiByte :: MultiCall -> Word -> Word -> Ptr () -> Int -> Ptr () -> Int -> IO Int
nativeMultiByte call (W# page) (W# flags) (Ptr input) (I# count) (Ptr output) (I# capacity) = IO (\state ->
  case call (wordToWord32# page) (wordToWord32# flags) input (intToInt32# count) output (intToInt32# capacity) state of
    (# next, value #) -> (# next, I# (int32ToInt# value) #))
nativeWideChar, nativeWideCharSafe :: WideCall -> Word -> Word -> Ptr () -> Int -> Ptr () -> Int -> Ptr () -> Ptr () -> IO Int
nativeWideChar call (W# page) (W# flags) (Ptr input) (I# count) (Ptr output) (I# capacity) (Ptr def) (Ptr used) = IO (\state ->
  case call (wordToWord32# page) (wordToWord32# flags) input (intToInt32# count) output (intToInt32# capacity) def used state of
    (# next, value #) -> (# next, I# (int32ToInt# value) #))
nativeWideCharSafe = nativeWideChar
nativeMapError :: MapCall -> Word -> IO Int
nativeMapError call (W# value) = IO (\state ->
  case call (wordToWord32# value) state of (# next, result #) -> (# next, I# (int32ToInt# result) #))
nativeMapCurrentError :: MapStateCall -> IO ()
nativeMapCurrentError call = IO (\state -> case call state of (# next #) -> (# next, () #))
nativeErrorMessage :: MessageCall -> Word -> IO (Ptr ())
nativeErrorMessage call (W# value) = IO (\state ->
  case call (wordToWord32# value) state of (# next, result #) -> (# next, Ptr result #))
nativeLocalFree :: FreeCall -> Ptr () -> IO (Ptr ())
nativeLocalFree call (Ptr value) = IO (\state -> case call value state of (# next, result #) -> (# next, Ptr result #))
