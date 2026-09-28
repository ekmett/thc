-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, ScopedTypeVariables #-}

-- |
-- Module      : ForeignExceptionAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC JavaScript FFI; not native GHC execution
--
-- Compiler fixture for foreign exception audit Core and metadata.
module ForeignExceptionAudit where

import Control.Exception
import Data.IORef
import GHC.Exts (Int(I#), Int#)
import System.IO.Unsafe (unsafePerformIO)
import THC.Exception
import qualified THC.Polyglot as Polyglot

foreign import javascript safe "n => { if (n === 0) { if (!globalThis.thcFailure) globalThis.thcFailure = new Error('foreign boom'); throw globalThis.thcFailure; } return n + 7; }"
  foreignNumber :: Int -> IO Int

run :: IO Int -> Int#
run action = case unsafePerformIO action of I# answer -> answer
{-# OPAQUE run #-}

caught :: Int# -> Int#
caught n = run ((foreignNumber (I# n)) `catch` \(_ :: ForeignException) -> pure 42)
{-# OPAQUE caught #-}

rethrowNow :: Int# -> Int#
rethrowNow n = run ((foreignNumber (I# n)) `catch` \(e :: SomeException) -> throwIO e)
{-# OPAQUE rethrowNow #-}

rethrowLater :: Int# -> Int#
rethrowLater n = run $ do
  stored <- newIORef Nothing
  answer <- (foreignNumber (I# n)) `catch` \(e :: SomeException) ->
    writeIORef stored (Just e) >> pure 0
  saved <- readIORef stored
  case saved of Nothing -> pure answer; Just e -> throwIO e
{-# OPAQUE rethrowLater #-}

cleanup :: Int# -> Int#
cleanup n = run $ do
  ref <- newIORef (0 :: Int)
  answer <- ((foreignNumber (I# n)) `finally` writeIORef ref 100)
    `catch` \(_ :: ForeignException) -> pure 42
  count <- readIORef ref
  pure (answer + count)
{-# OPAQUE cleanup #-}

metadata :: Int# -> Int#
metadata n = run ((foreignNumber (I# n)) `catch` \(e :: ForeignException) -> do
  message <- foreignExceptionMessage e
  pure (maybe (-1) length message))
{-# OPAQUE metadata #-}

lazyOrdinary :: Int# -> Int#
lazyOrdinary _ = run $ (throwIO (error "exception payload must stay lazy" :: SomeException)
    `catch` \(_ :: SomeException) -> pure 99)
{-# OPAQUE lazyOrdinary #-}

ordinary :: Int# -> Int#
ordinary n = run $ ((throwIO Overflow) `catch`
  \(e :: ArithException) -> pure (if e == Overflow then I# n else -1))
{-# OPAQUE ordinary #-}

displayIsInert :: Int# -> Int#
displayIsInert n = run ((foreignNumber (I# n)) `catch` \(e :: ForeignException) ->
  pure (length (displayException e)))
{-# OPAQUE displayIsInert #-}

-- Dynamic eval syntax failure is a catchable foreign application failure too.
parseCleanup :: Int# -> Int#
parseCleanup _ = run $ do
  cleaned <- newIORef (0 :: Int)
  answer <- ((Polyglot.evalJS "("# "invalid-user-source.js"# >> pure 0)
    `finally` writeIORef cleaned 100) `catch` \(_ :: ForeignException) -> pure 42
  count <- readIORef cleaned
  pure (answer + count)
{-# OPAQUE parseCleanup #-}

hostCall :: Int -> IO Int
hostCall n = do
  value <- Polyglot.evalJS "thcHostFailure"# "host-failure-value.js"#
  Polyglot.executeInt value n
{-# OPAQUE hostCall #-}

hostMetadata :: Int# -> Int#
hostMetadata n = run $ hostCall (I# n) `catch` \(e :: ForeignException) -> do
  message <- foreignExceptionMessage e
  pure (maybe (-1) length message)
{-# OPAQUE hostMetadata #-}

hostMetadataCleanup :: Int# -> Int#
hostMetadataCleanup n = run $ do
  ref <- newIORef (0 :: Int)
  answer <- ((hostCall (I# n) `catch` \(e :: ForeignException) ->
      foreignExceptionMessage e >> pure 0) `finally` writeIORef ref 100)
    `catch` \(_ :: ForeignException) -> pure 42
  count <- readIORef ref
  pure (answer + count)
{-# OPAQUE hostMetadataCleanup #-}

hostCatch :: Int# -> Int#
hostCatch n = run $ hostCall (I# n) `catch` \(_ :: ForeignException) -> pure 42
{-# OPAQUE hostCatch #-}
