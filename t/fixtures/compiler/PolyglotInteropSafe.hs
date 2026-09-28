-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}
module PolyglotInteropSafe (snapshot, element, collection) where

import THC.Polyglot (Value)
import qualified THC.Interop.Buffer as Buffer
import qualified THC.Interop.Array as Array

snapshot :: Value -> IO Buffer.ByteArray
snapshot value = Buffer.copySlice value 1 2

element :: Value -> IO Value
element value = Array.read value 0

collection :: Value -> IO (Array.Array Value)
collection = Array.copy
