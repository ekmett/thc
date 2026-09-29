-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, Safe #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC runtime
--
-- Small lifted Java calls. Run under THC with host lookup and member access
-- enabled for these classes; native GHC can compile but cannot execute the
-- THC-owned intrinsics. No Java String or array is a native address.
module JavaInterop (addExact, builderLength, arrayLengthViaJava) where

import Data.Int (Int32)
import qualified THC.Interop.Java as Java

-- | Static method with two exact Java int arguments: @addExact 20 22 == 42@.
addExact :: Int32 -> Int32 -> IO Int32
addExact x y = do
  name <- Java.javaStringUtf8 "java.lang.Math"#
  math <- Java.lookupHostSymbol name
  method <- Java.javaStringUtf8 "addExact"#
  a <- Java.boxJavaInt x
  b <- Java.boxJavaInt y
  Java.invokeMember math method [a, b] >>= Java.unboxJavaInt

-- | Constructor, instance invocation, and calling a bound member. Returns 4.
builderLength :: IO Int32
builderLength = do
  name <- Java.javaStringUtf8 "java.lang.StringBuilder"#
  cls <- Java.lookupHostSymbol name
  builder <- Java.instantiate cls []
  append <- Java.javaStringUtf8 "append"#
  text <- Java.javaStringUtf8 "thc!"#
  _ <- Java.invokeMember builder append [text]
  len <- Java.javaStringUtf8 "length"#
  method <- Java.readMember builder len
  Java.execute method [] >>= Java.unboxJavaInt

-- | Explicitly adapt a raw byte[] before passing it to Java. Returns 4 without
-- copying its elements or pretending the raw array is an interop receiver.
arrayLengthViaJava :: IO Int32
arrayLengthViaJava = do
  bytes <- Java.newJavaByteArray 4
  argument <- Java.asGuestValue (Java.javaByteArrayAsObject bytes)
  name <- Java.javaStringUtf8 "java.lang.reflect.Array"#
  cls <- Java.lookupHostSymbol name
  method <- Java.javaStringUtf8 "getLength"#
  Java.invokeMember cls method [argument] >>= Java.unboxJavaInt
