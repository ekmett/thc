-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, ScopedTypeVariables #-}
module TruffleStringExceptions where

import Control.Exception
import Data.IORef
import Data.List (isSuffixOf)
import GHC.Exts
import System.IO.Unsafe (unsafePerformIO)
import THC.Exception
import THC.Prim

text :: Int# -> TruffleString#
text choice = case truffleStringEncoding# 0# of
  encoding -> case choice of
    0# -> truffleStringFromCodePoint# encoding 120#
    1# -> truffleStringConcat# encoding
      (truffleStringFromInt64# encoding (intToInt64# 9223372036854775807#))
      (truffleStringFromCodePoint# encoding 48#)
    _ -> truffleStringFromCodePoint# encoding 55#

run :: IO Int -> Int#
run action = case unsafePerformIO action of I# n -> n
{-# OPAQUE run #-}

checked :: IO Int -> IO Int
checked action = do
  count <- newIORef (0 :: Int)
  answer <- (action `finally` modifyIORef' count (+ 100)) `catch`
    \(failure :: ForeignException) -> do
      kind <- foreignExceptionType failure
      pure (if maybe False ("NumberFormatException" `isSuffixOf`) kind then 42 else -1)
  cleaned <- readIORef count
  pure (answer + cleaned)
{-# OPAQUE checked #-}

caughtInt :: Int# -> Int#
caughtInt choice = run (checked (evaluate (I# (int64ToInt# (truffleStringParseInt64# (text choice) 10#)))))
{-# OPAQUE caughtInt #-}

caughtDouble :: Int# -> Int#
caughtDouble choice = run (checked (evaluate (I# (double2Int# (truffleStringParseDouble# (text choice))))))
{-# OPAQUE caughtDouble #-}

-- Share the genuine installed dependency universe between both parser entries.
-- Loading two separate whole unit programs is not part of the numeric contract.
caughtNumber :: Int# -> Int# -> Int#
caughtNumber parser choice = case parser of
  0# -> caughtInt choice
  _ -> caughtDouble choice
{-# OPAQUE caughtNumber #-}

rethrowInt :: Int# -> Int#
rethrowInt choice = run (evaluate (I# (int64ToInt# (truffleStringParseInt64# (text choice) 10#)))
  `catch` \(failure :: ForeignException) -> throwIO failure)
{-# OPAQUE rethrowInt #-}
