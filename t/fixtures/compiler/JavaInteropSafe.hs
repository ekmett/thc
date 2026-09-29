-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, Safe #-}

-- A Safe client of the lifted facade. Java intrinsics execute under THC, not
-- native GHC; the host-interop fixture runner supplies their execution checks.
module JavaInteropSafe
  ( addExact20, builderLength, arrayLengthViaJava, primitiveArrays
  , characterObjectAndOverload
  ) where

import Data.Int (Int32)
import qualified JavaInterop as Example
import qualified THC.Interop.Java as Java

-- | Expected result: 42.
addExact20 :: IO Int32
addExact20 = Example.addExact 20 22
{-# OPAQUE addExact20 #-}

-- | Expected result: 4.
builderLength :: IO Int32
builderLength = Example.builderLength
{-# OPAQUE builderLength #-}

-- | Expected result: 4.
arrayLengthViaJava :: IO Int32
arrayLengthViaJava = Example.arrayLengthViaJava
{-# OPAQUE arrayLengthViaJava #-}

-- | All eight lifted primitive-array types remain distinct. Char stores one
-- UTF-16 code unit, including a surrogate, not a Haskell Unicode scalar.
-- Expected result: True. The Int array also checks an overlapping copy.
primitiveArrays :: IO Bool
primitiveArrays = do
  booleans <- Java.newJavaBooleanArray 1
  Java.writeJavaBooleanArray booleans 0 True
  boolean <- Java.readJavaBooleanArray booleans 0
  bytes <- Java.newJavaByteArray 1
  Java.writeJavaByteArray bytes 0 (-7)
  byte <- Java.readJavaByteArray bytes 0
  shorts <- Java.newJavaShortArray 1
  Java.writeJavaShortArray shorts 0 (-300)
  short <- Java.readJavaShortArray shorts 0
  chars <- Java.newJavaCharArray 1
  Java.writeJavaCharArray chars 0 0xD800
  char <- Java.readJavaCharArray chars 0
  ints <- Java.newJavaIntArray 3
  Java.writeJavaIntArray ints 0 20
  Java.writeJavaIntArray ints 1 22
  Java.copyJavaIntArray ints 0 ints 1 2
  int <- Java.readJavaIntArray ints 2
  intLength <- Java.javaIntArrayLength ints
  alias <- Java.objectAsJavaIntArray (Java.javaIntArrayAsObject ints)
  Java.writeJavaIntArray alias 0 42
  aliased <- Java.readJavaIntArray ints 0
  longs <- Java.newJavaLongArray 1
  Java.writeJavaLongArray longs 0 10000000000
  long <- Java.readJavaLongArray longs 0
  floats <- Java.newJavaFloatArray 1
  Java.writeJavaFloatArray floats 0 1.5
  float <- Java.readJavaFloatArray floats 0
  doubles <- Java.newJavaDoubleArray 1
  Java.writeJavaDoubleArray doubles 0 (-2.25)
  double <- Java.readJavaDoubleArray doubles 0
  pure (boolean && byte == -7 && short == -300 && char == 0xD800 &&
        int == 22 && intLength == 3 && aliased == 42 && long == 10000000000 &&
        float == 1.5 && double == -2.25)
{-# OPAQUE primitiveArrays #-}

-- | Expected result: True. The host receiver supplies isCharacter(Object),
-- choose(Character) = 1 and choose(String) = 2. Boxing a Java char must preserve
-- Character identity even when passed through Object or an overloaded member.
characterObjectAndOverload :: Java.Object -> IO Bool
characterObjectAndOverload receiver = do
  character <- Java.boxJavaChar 0x78
  string <- Java.javaStringUtf8 "x"#
  identityName <- Java.javaStringUtf8 "isCharacter"#
  chooseName <- Java.javaStringUtf8 "choose"#
  characterIdentity <- Java.invokeMember receiver identityName [character]
    >>= Java.unboxJavaBoolean
  stringIdentity <- Java.invokeMember receiver identityName [string]
    >>= Java.unboxJavaBoolean
  characterChoice <- Java.invokeMember receiver chooseName [character]
    >>= Java.unboxJavaInt
  stringChoice <- Java.invokeMember receiver chooseName [string]
    >>= Java.unboxJavaInt
  pure (characterIdentity && not stringIdentity &&
        characterChoice == 1 && stringChoice == 2)
{-# OPAQUE characterObjectAndOverload #-}
