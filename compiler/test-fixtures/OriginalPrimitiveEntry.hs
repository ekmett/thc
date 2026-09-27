-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, ScopedTypeVariables #-}

-- |
-- Module      : OriginalPrimitiveEntry
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original primitive entry Core and metadata.
module OriginalPrimitiveEntry (signed16, unsigned16, signed64) where

import Control.Monad.ST
import Data.Int
import Data.Word
import Data.Primitive.ByteArray
import GHC.Exts (Int(I#), Int#)

-- Ordinary original-package public APIs. No replacement foreign declaration.
signed16 :: Int# -> Int#
signed16 value = case runST (do
  array <- newByteArray 32
  setByteArray array 2 7 (fromIntegral (I# value) :: Int16)
  answer <- readByteArray array 3
  pure (fromIntegral (answer :: Int16) :: Int)) of I# answer -> answer

unsigned16 :: Int# -> Int#
unsigned16 value = case runST (do
  array <- newByteArray 32
  setByteArray array 2 7 (fromIntegral (I# value) :: Word16)
  answer <- readByteArray array 3
  pure (fromIntegral (answer :: Word16) :: Int)) of I# answer -> answer

signed64 :: Int# -> Int#
signed64 value = case runST (do
  array <- newByteArray 128
  setByteArray array 2 7 (fromIntegral (I# value) :: Int64)
  answer <- readByteArray array 3
  pure (fromIntegral (answer :: Int64) :: Int)) of I# answer -> answer
