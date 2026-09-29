-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, Trustworthy #-}

-- |
-- Module      : THC.Interop.Java
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC runtime
--
-- Lifted Java interop in 'IO'. Host lookup and exposed members obey the enclosing
-- context's host-class lookup and HostAccess policies. These are THC intrinsics,
-- not native-GHC foreign functions. Failures use the foreign-exception path.
--
-- An 'Object' holds the actual reference, not an arbitrary Haskell value.
-- Raw Java arrays need 'asGuestValue' before member/array interop; use
-- 'asHostObject' to recover a host reference from an interop wrapper.
-- Each message acquires the actual dispatcher at that operation site.
--
-- Arguments are interop values. Calls allocate one immutable raw object array
-- from the supplied list; they do not copy the referenced objects or arrays.
-- All handles here belong to 'RealWorld': Java may retain and mutate them.
-- Neither a lifted wrapper nor a read-only reference implies thread safety.
module THC.Interop.Java
  ( Object, fromObject#, toObject#
  , lookupHostSymbol, asGuestValue, asHostObject, javaNull, javaStringUtf8
  , execute, instantiate, readMember, writeMember, invokeMember
    -- * Java scalar values
  , boxJavaBoolean, unboxJavaBoolean
  , boxJavaByte, unboxJavaByte
  , boxJavaShort, unboxJavaShort
  , boxJavaChar, unboxJavaChar
  , boxJavaInt, unboxJavaInt
  , boxJavaLong, unboxJavaLong
  , boxJavaFloat, unboxJavaFloat
  , boxJavaDouble, unboxJavaDouble
    -- * Actual JVM primitive arrays
  , JavaBooleanArray(..), newJavaBooleanArray, javaBooleanArrayLength
  , readJavaBooleanArray, writeJavaBooleanArray, copyJavaBooleanArray
  , objectAsJavaBooleanArray, javaBooleanArrayAsObject
  , JavaByteArray(..), newJavaByteArray, javaByteArrayLength
  , readJavaByteArray, writeJavaByteArray, copyJavaByteArray
  , objectAsJavaByteArray, javaByteArrayAsObject
  , JavaShortArray(..), newJavaShortArray, javaShortArrayLength
  , readJavaShortArray, writeJavaShortArray, copyJavaShortArray
  , objectAsJavaShortArray, javaShortArrayAsObject
  , JavaCharArray(..), newJavaCharArray, javaCharArrayLength
  , readJavaCharArray, writeJavaCharArray, copyJavaCharArray
  , objectAsJavaCharArray, javaCharArrayAsObject
  , JavaIntArray(..), newJavaIntArray, javaIntArrayLength
  , readJavaIntArray, writeJavaIntArray, copyJavaIntArray
  , objectAsJavaIntArray, javaIntArrayAsObject
  , JavaLongArray(..), newJavaLongArray, javaLongArrayLength
  , readJavaLongArray, writeJavaLongArray, copyJavaLongArray
  , objectAsJavaLongArray, javaLongArrayAsObject
  , JavaFloatArray(..), newJavaFloatArray, javaFloatArrayLength
  , readJavaFloatArray, writeJavaFloatArray, copyJavaFloatArray
  , objectAsJavaFloatArray, javaFloatArrayAsObject
  , JavaDoubleArray(..), newJavaDoubleArray, javaDoubleArrayLength
  , readJavaDoubleArray, writeJavaDoubleArray, copyJavaDoubleArray
  , objectAsJavaDoubleArray, javaDoubleArrayAsObject
  ) where

import GHC.Exts
  ( Addr#, Array#, MutableArray#, RealWorld, State#, Int#, Int(I#)
  , Float(F#), Double(D#), isTrue#, (/=#), (+#)
  , newArray#, writeArray#, unsafeFreezeArray# )
import GHC.Int (Int8(I8#), Int16(I16#), Int32(I32#), Int64(I64#))
import GHC.Word (Word16(W16#))
import GHC.IO (IO(..))
import qualified THC.Prim as Prim

-- | Lifted raw reference. It may be a host object, an interop value, or null;
-- no eager conversion or dispatcher acquisition is performed.
data Object = Object (Prim.Object# RealWorld)

-- | Lift a raw reference without copying or adapting it.
fromObject# :: Prim.Object# RealWorld -> Object
fromObject# = Object

-- | Recover the same raw reference, for use with 'THC.Prim' operations.
toObject# :: Object -> Prim.Object# RealWorld
toObject# (Object object) = object

objectIO :: (State# RealWorld -> (# State# RealWorld, Prim.Object# RealWorld #)) -> IO Object
objectIO action = IO $ \state -> case action state of
  (# next, object #) -> (# next, Object object #)

unitIO :: (State# RealWorld -> State# RealWorld) -> IO ()
unitIO action = IO $ \state -> case action state of next -> (# next, () #)

intIO :: (State# RealWorld -> (# State# RealWorld, Int# #)) -> IO Int
intIO action = IO $ \state -> case action state of
  (# next, value #) -> (# next, I# value #)

-- | Look up a Java class named by an interop string, subject to host policy.
lookupHostSymbol :: Object -> IO Object
lookupHostSymbol (Object name) = objectIO (Prim.lookupHostSymbol# name)

-- | Adapt a raw Java reference for interop without copying it.
asGuestValue :: Object -> IO Object
asGuestValue (Object object) = objectIO (Prim.asGuestValue# object)

-- | Recover the raw host reference. Non-host interop values are rejected.
asHostObject :: Object -> IO Object
asHostObject (Object object) =
  objectIO (Prim.asHostObject# object (Prim.getInteropLibrary# object))

-- | An interop null value suitable for a Java reference argument.
javaNull :: IO Object
javaNull = objectIO Prim.javaNull#

-- | Copy a valid NUL-terminated UTF-8 address into a Java string.
-- Intended for address literals such as @"java.lang.Math"#@. This is not a
-- conversion from arbitrary Haskell values; other addresses require the usual
-- care about validity and lifetime.
javaStringUtf8 :: Addr# -> IO Object
javaStringUtf8 address = objectIO (Prim.javaStringUtf8# address)

withArguments :: [Object]
              -> (Array# (Prim.Object# RealWorld) -> State# RealWorld
                  -> (# State# RealWorld, Prim.Object# RealWorld #))
              -> IO Object
withArguments values action = objectIO $ \state ->
  case length values of
    I# count -> case Prim.javaNull# state of
      (# next, empty #) -> case newArray# count empty next of
        (# allocated, array #) -> fill array 0# values allocated
  where
    fill :: MutableArray# RealWorld (Prim.Object# RealWorld) -> Int# -> [Object]
         -> State# RealWorld -> (# State# RealWorld, Prim.Object# RealWorld #)
    fill array _ [] state = case unsafeFreezeArray# array state of
      (# next, frozen #) -> action frozen next
    fill array index (Object value : rest) state =
      case writeArray# array index value state of
        next -> fill array (index +# 1#) rest next

-- | Execute an interop callable with the supplied arguments.
execute :: Object -> [Object] -> IO Object
execute (Object receiver) values =
  withArguments values (Prim.execute# receiver (Prim.getInteropLibrary# receiver))

-- | Invoke a Java constructor or another instantiable interop receiver.
instantiate :: Object -> [Object] -> IO Object
instantiate (Object receiver) values =
  withArguments values (Prim.instantiate# receiver (Prim.getInteropLibrary# receiver))

-- | Read a field/member whose name supports the interop string protocol.
readMember :: Object -> Object -> IO Object
readMember (Object receiver) (Object name) =
  objectIO (Prim.readMember# receiver (Prim.getInteropLibrary# receiver) name)

-- | Write an exposed field/member; ordinary host access policy still applies.
writeMember :: Object -> Object -> Object -> IO ()
writeMember (Object receiver) (Object name) (Object value) =
  unitIO (Prim.writeMember# receiver (Prim.getInteropLibrary# receiver) name value)

-- | Invoke a named static or instance method. Names are interop strings.
invokeMember :: Object -> Object -> [Object] -> IO Object
invokeMember (Object receiver) (Object name) values =
  withArguments values (Prim.invokeMember# receiver (Prim.getInteropLibrary# receiver) name)

-- | Box a Java boolean.
boxJavaBoolean :: Bool -> IO Object
boxJavaBoolean value = objectIO (Prim.boxJavaBoolean# (case value of False -> 0#; True -> 1#))

-- | Convert exactly to Java boolean; incompatible values raise a foreign exception.
unboxJavaBoolean :: Object -> IO Bool
unboxJavaBoolean (Object object) = IO $ \state ->
  case Prim.unboxJavaBoolean# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, isTrue# (value /=# 0#) #)

-- | Box a Java byte.
boxJavaByte :: Int8 -> IO Object
boxJavaByte (I8# value) = objectIO (Prim.boxJavaByte# value)

-- | Convert exactly to Java byte; incompatible values raise a foreign exception.
unboxJavaByte :: Object -> IO Int8
unboxJavaByte (Object object) = IO $ \state ->
  case Prim.unboxJavaByte# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, I8# value #)

-- | Box a Java short.
boxJavaShort :: Int16 -> IO Object
boxJavaShort (I16# value) = objectIO (Prim.boxJavaShort# value)

-- | Convert exactly to Java short; incompatible values raise a foreign exception.
unboxJavaShort :: Object -> IO Int16
unboxJavaShort (Object object) = IO $ \state ->
  case Prim.unboxJavaShort# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, I16# value #)

-- | Box a Java char UTF-16 code unit (not a Haskell Char).
boxJavaChar :: Word16 -> IO Object
boxJavaChar (W16# value) = objectIO (Prim.boxJavaChar# value)

-- | Convert exactly to Java char; incompatible values raise a foreign exception.
unboxJavaChar :: Object -> IO Word16
unboxJavaChar (Object object) = IO $ \state ->
  case Prim.unboxJavaChar# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, W16# value #)

-- | Box a Java int.
boxJavaInt :: Int32 -> IO Object
boxJavaInt (I32# value) = objectIO (Prim.boxJavaInt# value)

-- | Convert exactly to Java int; incompatible values raise a foreign exception.
unboxJavaInt :: Object -> IO Int32
unboxJavaInt (Object object) = IO $ \state ->
  case Prim.unboxJavaInt# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, I32# value #)

-- | Box a Java long.
boxJavaLong :: Int64 -> IO Object
boxJavaLong (I64# value) = objectIO (Prim.boxJavaLong# value)

-- | Convert exactly to Java long; incompatible values raise a foreign exception.
unboxJavaLong :: Object -> IO Int64
unboxJavaLong (Object object) = IO $ \state ->
  case Prim.unboxJavaLong# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, I64# value #)

-- | Box a Java float.
boxJavaFloat :: Float -> IO Object
boxJavaFloat (F# value) = objectIO (Prim.boxJavaFloat# value)

-- | Convert exactly to Java float; incompatible values raise a foreign exception.
unboxJavaFloat :: Object -> IO Float
unboxJavaFloat (Object object) = IO $ \state ->
  case Prim.unboxJavaFloat# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, F# value #)

-- | Box a Java double.
boxJavaDouble :: Double -> IO Object
boxJavaDouble (D# value) = objectIO (Prim.boxJavaDouble# value)

-- | Convert exactly to Java double; incompatible values raise a foreign exception.
unboxJavaDouble :: Object -> IO Double
unboxJavaDouble (Object object) = IO $ \state ->
  case Prim.unboxJavaDouble# object (Prim.getInteropLibrary# object) state of
    (# next, value #) -> (# next, D# value #)

-- | Lifted owner of an actual Java @boolean[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaBooleanArray = JavaBooleanArray (Prim.JavaBooleanArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaBooleanArray :: Int -> IO JavaBooleanArray
newJavaBooleanArray (I# count) = IO $ \state ->
  case Prim.newJavaBooleanArray# count state of
    (# next, array #) -> (# next, JavaBooleanArray array #)

-- | Current element count.
javaBooleanArrayLength :: JavaBooleanArray -> IO Int
javaBooleanArrayLength (JavaBooleanArray array) = intIO (Prim.javaBooleanArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaBooleanArray :: JavaBooleanArray -> Int -> IO Bool
readJavaBooleanArray (JavaBooleanArray array) (I# index) = IO $ \state ->
  case Prim.readJavaBooleanArray# array index state of
    (# next, value #) -> (# next, isTrue# (value /=# 0#) #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaBooleanArray :: JavaBooleanArray -> Int -> Bool -> IO ()
writeJavaBooleanArray (JavaBooleanArray array) (I# index) value =
  unitIO (Prim.writeJavaBooleanArray# array index (case value of False -> 0#; True -> 1#))

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaBooleanArray :: JavaBooleanArray -> Int -> JavaBooleanArray -> Int -> Int -> IO ()
copyJavaBooleanArray (JavaBooleanArray source) (I# sourceOffset)
                    (JavaBooleanArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaBooleanArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaBooleanArray :: Object -> IO JavaBooleanArray
objectAsJavaBooleanArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaBooleanArray# object state of
    (# next, array #) -> (# next, JavaBooleanArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaBooleanArrayAsObject :: JavaBooleanArray -> Object
javaBooleanArrayAsObject (JavaBooleanArray array) = Object (Prim.javaBooleanArrayAsObject# array)

-- | Lifted owner of an actual Java @byte[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaByteArray = JavaByteArray (Prim.JavaByteArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaByteArray :: Int -> IO JavaByteArray
newJavaByteArray (I# count) = IO $ \state ->
  case Prim.newJavaByteArray# count state of
    (# next, array #) -> (# next, JavaByteArray array #)

-- | Current element count.
javaByteArrayLength :: JavaByteArray -> IO Int
javaByteArrayLength (JavaByteArray array) = intIO (Prim.javaByteArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaByteArray :: JavaByteArray -> Int -> IO Int8
readJavaByteArray (JavaByteArray array) (I# index) = IO $ \state ->
  case Prim.readJavaByteArray# array index state of
    (# next, value #) -> (# next, I8# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaByteArray :: JavaByteArray -> Int -> Int8 -> IO ()
writeJavaByteArray (JavaByteArray array) (I# index) (I8# value) =
  unitIO (Prim.writeJavaByteArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaByteArray :: JavaByteArray -> Int -> JavaByteArray -> Int -> Int -> IO ()
copyJavaByteArray (JavaByteArray source) (I# sourceOffset)
                    (JavaByteArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaByteArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaByteArray :: Object -> IO JavaByteArray
objectAsJavaByteArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaByteArray# object state of
    (# next, array #) -> (# next, JavaByteArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaByteArrayAsObject :: JavaByteArray -> Object
javaByteArrayAsObject (JavaByteArray array) = Object (Prim.javaByteArrayAsObject# array)

-- | Lifted owner of an actual Java @short[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaShortArray = JavaShortArray (Prim.JavaShortArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaShortArray :: Int -> IO JavaShortArray
newJavaShortArray (I# count) = IO $ \state ->
  case Prim.newJavaShortArray# count state of
    (# next, array #) -> (# next, JavaShortArray array #)

-- | Current element count.
javaShortArrayLength :: JavaShortArray -> IO Int
javaShortArrayLength (JavaShortArray array) = intIO (Prim.javaShortArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaShortArray :: JavaShortArray -> Int -> IO Int16
readJavaShortArray (JavaShortArray array) (I# index) = IO $ \state ->
  case Prim.readJavaShortArray# array index state of
    (# next, value #) -> (# next, I16# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaShortArray :: JavaShortArray -> Int -> Int16 -> IO ()
writeJavaShortArray (JavaShortArray array) (I# index) (I16# value) =
  unitIO (Prim.writeJavaShortArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaShortArray :: JavaShortArray -> Int -> JavaShortArray -> Int -> Int -> IO ()
copyJavaShortArray (JavaShortArray source) (I# sourceOffset)
                    (JavaShortArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaShortArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaShortArray :: Object -> IO JavaShortArray
objectAsJavaShortArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaShortArray# object state of
    (# next, array #) -> (# next, JavaShortArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaShortArrayAsObject :: JavaShortArray -> Object
javaShortArrayAsObject (JavaShortArray array) = Object (Prim.javaShortArrayAsObject# array)

-- | Lifted owner of an actual Java @char[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaCharArray = JavaCharArray (Prim.JavaCharArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaCharArray :: Int -> IO JavaCharArray
newJavaCharArray (I# count) = IO $ \state ->
  case Prim.newJavaCharArray# count state of
    (# next, array #) -> (# next, JavaCharArray array #)

-- | Current element count.
javaCharArrayLength :: JavaCharArray -> IO Int
javaCharArrayLength (JavaCharArray array) = intIO (Prim.javaCharArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaCharArray :: JavaCharArray -> Int -> IO Word16
readJavaCharArray (JavaCharArray array) (I# index) = IO $ \state ->
  case Prim.readJavaCharArray# array index state of
    (# next, value #) -> (# next, W16# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaCharArray :: JavaCharArray -> Int -> Word16 -> IO ()
writeJavaCharArray (JavaCharArray array) (I# index) (W16# value) =
  unitIO (Prim.writeJavaCharArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaCharArray :: JavaCharArray -> Int -> JavaCharArray -> Int -> Int -> IO ()
copyJavaCharArray (JavaCharArray source) (I# sourceOffset)
                    (JavaCharArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaCharArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaCharArray :: Object -> IO JavaCharArray
objectAsJavaCharArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaCharArray# object state of
    (# next, array #) -> (# next, JavaCharArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaCharArrayAsObject :: JavaCharArray -> Object
javaCharArrayAsObject (JavaCharArray array) = Object (Prim.javaCharArrayAsObject# array)

-- | Lifted owner of an actual Java @int[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaIntArray = JavaIntArray (Prim.JavaIntArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaIntArray :: Int -> IO JavaIntArray
newJavaIntArray (I# count) = IO $ \state ->
  case Prim.newJavaIntArray# count state of
    (# next, array #) -> (# next, JavaIntArray array #)

-- | Current element count.
javaIntArrayLength :: JavaIntArray -> IO Int
javaIntArrayLength (JavaIntArray array) = intIO (Prim.javaIntArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaIntArray :: JavaIntArray -> Int -> IO Int32
readJavaIntArray (JavaIntArray array) (I# index) = IO $ \state ->
  case Prim.readJavaIntArray# array index state of
    (# next, value #) -> (# next, I32# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaIntArray :: JavaIntArray -> Int -> Int32 -> IO ()
writeJavaIntArray (JavaIntArray array) (I# index) (I32# value) =
  unitIO (Prim.writeJavaIntArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaIntArray :: JavaIntArray -> Int -> JavaIntArray -> Int -> Int -> IO ()
copyJavaIntArray (JavaIntArray source) (I# sourceOffset)
                    (JavaIntArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaIntArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaIntArray :: Object -> IO JavaIntArray
objectAsJavaIntArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaIntArray# object state of
    (# next, array #) -> (# next, JavaIntArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaIntArrayAsObject :: JavaIntArray -> Object
javaIntArrayAsObject (JavaIntArray array) = Object (Prim.javaIntArrayAsObject# array)

-- | Lifted owner of an actual Java @long[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaLongArray = JavaLongArray (Prim.JavaLongArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaLongArray :: Int -> IO JavaLongArray
newJavaLongArray (I# count) = IO $ \state ->
  case Prim.newJavaLongArray# count state of
    (# next, array #) -> (# next, JavaLongArray array #)

-- | Current element count.
javaLongArrayLength :: JavaLongArray -> IO Int
javaLongArrayLength (JavaLongArray array) = intIO (Prim.javaLongArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaLongArray :: JavaLongArray -> Int -> IO Int64
readJavaLongArray (JavaLongArray array) (I# index) = IO $ \state ->
  case Prim.readJavaLongArray# array index state of
    (# next, value #) -> (# next, I64# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaLongArray :: JavaLongArray -> Int -> Int64 -> IO ()
writeJavaLongArray (JavaLongArray array) (I# index) (I64# value) =
  unitIO (Prim.writeJavaLongArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaLongArray :: JavaLongArray -> Int -> JavaLongArray -> Int -> Int -> IO ()
copyJavaLongArray (JavaLongArray source) (I# sourceOffset)
                    (JavaLongArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaLongArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaLongArray :: Object -> IO JavaLongArray
objectAsJavaLongArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaLongArray# object state of
    (# next, array #) -> (# next, JavaLongArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaLongArrayAsObject :: JavaLongArray -> Object
javaLongArrayAsObject (JavaLongArray array) = Object (Prim.javaLongArrayAsObject# array)

-- | Lifted owner of an actual Java @float[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaFloatArray = JavaFloatArray (Prim.JavaFloatArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaFloatArray :: Int -> IO JavaFloatArray
newJavaFloatArray (I# count) = IO $ \state ->
  case Prim.newJavaFloatArray# count state of
    (# next, array #) -> (# next, JavaFloatArray array #)

-- | Current element count.
javaFloatArrayLength :: JavaFloatArray -> IO Int
javaFloatArrayLength (JavaFloatArray array) = intIO (Prim.javaFloatArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaFloatArray :: JavaFloatArray -> Int -> IO Float
readJavaFloatArray (JavaFloatArray array) (I# index) = IO $ \state ->
  case Prim.readJavaFloatArray# array index state of
    (# next, value #) -> (# next, F# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaFloatArray :: JavaFloatArray -> Int -> Float -> IO ()
writeJavaFloatArray (JavaFloatArray array) (I# index) (F# value) =
  unitIO (Prim.writeJavaFloatArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaFloatArray :: JavaFloatArray -> Int -> JavaFloatArray -> Int -> Int -> IO ()
copyJavaFloatArray (JavaFloatArray source) (I# sourceOffset)
                    (JavaFloatArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaFloatArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaFloatArray :: Object -> IO JavaFloatArray
objectAsJavaFloatArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaFloatArray# object state of
    (# next, array #) -> (# next, JavaFloatArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaFloatArrayAsObject :: JavaFloatArray -> Object
javaFloatArrayAsObject (JavaFloatArray array) = Object (Prim.javaFloatArrayAsObject# array)

-- | Lifted owner of an actual Java @double[]@. The constructor also allows
-- raw primitive/vector APIs to use the same array without copying.
data JavaDoubleArray = JavaDoubleArray (Prim.JavaDoubleArray# RealWorld)

-- | Allocate a zero-initialized array of the given element count.
newJavaDoubleArray :: Int -> IO JavaDoubleArray
newJavaDoubleArray (I# count) = IO $ \state ->
  case Prim.newJavaDoubleArray# count state of
    (# next, array #) -> (# next, JavaDoubleArray array #)

-- | Current element count.
javaDoubleArrayLength :: JavaDoubleArray -> IO Int
javaDoubleArrayLength (JavaDoubleArray array) = intIO (Prim.javaDoubleArrayLength# array)

-- | Read at a zero-based element index; JVM bounds checks apply.
readJavaDoubleArray :: JavaDoubleArray -> Int -> IO Double
readJavaDoubleArray (JavaDoubleArray array) (I# index) = IO $ \state ->
  case Prim.readJavaDoubleArray# array index state of
    (# next, value #) -> (# next, D# value #)

-- | Write at a zero-based element index; JVM bounds checks apply.
writeJavaDoubleArray :: JavaDoubleArray -> Int -> Double -> IO ()
writeJavaDoubleArray (JavaDoubleArray array) (I# index) (D# value) =
  unitIO (Prim.writeJavaDoubleArray# array index value)

-- | Copy source offset, destination offset, and element count with
-- @System.arraycopy@ overlap semantics. No conversion of element types occurs.
copyJavaDoubleArray :: JavaDoubleArray -> Int -> JavaDoubleArray -> Int -> Int -> IO ()
copyJavaDoubleArray (JavaDoubleArray source) (I# sourceOffset)
                    (JavaDoubleArray destination) (I# destinationOffset) (I# count) =
  unitIO (Prim.copyJavaDoubleArray# source sourceOffset destination destinationOffset count)

-- | Check a raw reference's actual JVM array type. For an interop host wrapper,
-- first use 'asHostObject'. The array is retained, not copied.
objectAsJavaDoubleArray :: Object -> IO JavaDoubleArray
objectAsJavaDoubleArray (Object object) = IO $ \state ->
  case Prim.objectAsJavaDoubleArray# object state of
    (# next, array #) -> (# next, JavaDoubleArray array #)

-- | Forget only the array type. Use 'asGuestValue' before host member dispatch.
javaDoubleArrayAsObject :: JavaDoubleArray -> Object
javaDoubleArrayAsObject (JavaDoubleArray array) = Object (Prim.javaDoubleArrayAsObject# array)
