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
-- A Safe client of the lifted facade. Java intrinsics execute under THC, not
-- native GHC; the host-interop fixture runner supplies their execution checks.
module JavaInteropSafe
  ( addExact20, builderLength, arrayLengthViaJava, primitiveArrays
  , characterObjectAndOverload, hostControls, deniedLookup, deniedMember
  ) where

import Data.Int (Int32)
import Control.Exception (catch)
import THC.Exception (ForeignException)
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
-- choose(Character) = 1 and choose(String) = 2. Explicit host wrappers preserve
-- Java identity. A two-code-unit string avoids the host's char conversion.
characterObjectAndOverload :: Java.Object -> IO Bool
characterObjectAndOverload receiver = do
  character <- Java.boxJavaChar 0x78 >>= Java.asBoxedGuestValue
  string <- Java.javaStringUtf8 "xy"# >>= Java.asBoxedGuestValue
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

-- | One ordinary host workflow, including an eight-argument mixed scalar call.
hostControls :: Java.Object -> IO Int
hostControls receiver = do
  added <- addExact20
  lengthOfBuilder <- builderLength
  lengthOfArray <- arrayLengthViaJava
  arrays <- primitiveArrays
  character <- characterObjectAndOverload receiver
  field <- Java.javaStringUtf8 "value"#
  replacement <- Java.boxJavaInt 41
  Java.writeMember receiver field replacement
  written <- Java.readMember receiver field >>= Java.unboxJavaInt
  method <- Java.javaStringUtf8 "mixed"#
  boolean <- Java.boxJavaBoolean True
  byte <- Java.boxJavaByte 2
  short <- Java.boxJavaShort 300
  char <- Java.boxJavaChar 120
  int <- Java.boxJavaInt 20
  long <- Java.boxJavaLong 10000000000
  float <- Java.boxJavaFloat 1.5
  double <- Java.boxJavaDouble (-2.25)
  result <- Java.invokeMember receiver method [boolean, byte, short, char, int, long, float, double]
    >>= Java.unboxJavaDouble
  array <- Java.newJavaIntArray 1
  wrapped <- Java.asGuestValue (Java.javaIntArrayAsObject array)
  raw <- Java.asHostObject wrapped
  alias <- Java.objectAsJavaIntArray raw
  Java.writeJavaIntArray alias 0 73
  aliased <- Java.readJavaIntArray array 0
  pure ((if added == 42 then 1 else 0) + (if lengthOfBuilder == 4 then 2 else 0) +
        (if lengthOfArray == 4 then 4 else 0) + (if arrays then 8 else 0) +
        (if character then 16 else 0) + (if written == 41 then 32 else 0) +
        (if result == 10000000442.25 then 64 else 0) + (if aliased == 73 then 128 else 0))
{-# OPAQUE hostControls #-}

-- | The context denies java.io.File; its ordinary failure is catchable.
deniedLookup :: IO Bool
deniedLookup = catch action caught
  where
    action = Java.javaStringUtf8 "java.io.File"# >>= Java.lookupHostSymbol >> pure False
    caught :: ForeignException -> IO Bool
    caught _ = pure True
{-# OPAQUE deniedLookup #-}

-- | HostAccess.NONE exposes no fields on this receiver.
deniedMember :: Java.Object -> IO Bool
deniedMember receiver = catch action caught
  where
    action = do
      name <- Java.javaStringUtf8 "value"#
      _ <- Java.readMember receiver name
      pure False
    caught :: ForeignException -> IO Bool
    caught _ = pure True
{-# OPAQUE deniedMember #-}
