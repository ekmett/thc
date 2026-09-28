-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, Trustworthy #-}

-- |
-- Module      : THC.Polyglot
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC polyglot runtime
--
-- Opaque foreign values and synchronous, context-owned Truffle calls. These
-- intrinsics are supplied by THC, not by a native C library. The audited
-- wrappers keep handles abstract and effects in IO.
module THC.Polyglot (Value, eval, evalJS, readMember, executeInt, execute) where

import GHC.Exts (Addr#, Int(..))
import GHC.IO (IO(..))
import THC.Internal.Polyglot

-- | Evaluate source. Language, source and name must be valid NUL-terminated
-- UTF-8 address literals. Arbitrary addresses require the usual unsafe care.
eval :: Addr# -> Addr# -> Addr# -> IO Value
eval language source name = IO $ \state ->
  case eval# language source name state of
    (# next, raw #) -> (# next, Value raw #)

-- | Evaluate JavaScript in the enclosing context.
evalJS :: Addr# -> Addr# -> IO Value
evalJS source name = eval "js"# source name

-- | Read a member named by a valid NUL-terminated UTF-8 address.
readMember :: Value -> Addr# -> IO Value
readMember (Value value) name = IO $ \state ->
  case readMember# value name state of
    (# next, raw #) -> (# next, Value raw #)

-- | Call with an integer in the exact JavaScript Number range.
executeInt :: Value -> Int -> IO Int
executeInt (Value function) (I# argument) = IO $ \state ->
  case executeInt# function argument state of
    (# next, result #) -> (# next, I# result #)

-- | Apply a foreign function to one opaque value, including a buffer or array
-- view. The argument retains its aliasing and mutation permissions.
execute :: Value -> Value -> IO Value
execute (Value function) (Value argument) = IO $ \state ->
  case executeValue# function argument state of
    (# next, result #) -> (# next, Value result #)
