-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module InteropPrimitives where
import GHC.Exts
import THC.Prim
import Control.Exception (catch)
import Control.Monad.ST (stToIO)
import GHC.IO (IO(..))
import THC.Exception (ForeignException, foreignExceptionType)
import qualified THC.Interop as Interop

getLibrary :: Object# RealWorld -> InteropLibrary# RealWorld
getLibrary value = getInteropLibrary# value
{-# OPAQUE getLibrary #-}

readOne :: Object# RealWorld -> InteropLibrary# RealWorld -> Int#
        -> State# RealWorld -> (# State# RealWorld, Int8# #)
readOne value library offset state = readBufferByte# value library offset state
{-# OPAQUE readOne #-}

sumBytes :: Object# RealWorld -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
sumBytes value count state = loop (getInteropLibrary# value) 0# 0# state where
  loop library index total current = case index <# count of
    0# -> (# current, total #)
    _ -> case readBufferByte# value library index current of
      (# next, byte #) -> loop library (index +# 1#) (total +# int8ToInt# byte) next
{-# OPAQUE sumBytes #-}

writeOne :: Object# RealWorld -> InteropLibrary# RealWorld -> Int# -> Int8#
         -> State# RealWorld -> State# RealWorld
writeOne value library offset byte state = writeBufferByte# value library offset byte state
{-# OPAQUE writeOne #-}

arrayLong :: Object# RealWorld -> Int# -> State# RealWorld -> (# State# RealWorld, Int64# #)
arrayLong value index state = case readArrayElement# value (getInteropLibrary# value) index state of
  (# next, element #) -> asLong# element (getInteropLibrary# element) next
{-# OPAQUE arrayLong #-}

-- Exercise the lifted facade and genuine exception dictionary, not a Java catch.
caughtRead :: Object# RealWorld -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
caughtRead value offset state = case catch action handler of
  IO run -> case run state of (# next, I# answer #) -> (# next, answer #)
  where
    action = stToIO (Interop.readBufferByte (Interop.fromObject# value) (I# offset)) >> pure 0
    handler :: ForeignException -> IO Int
    handler failure = do
      name <- foreignExceptionType failure
      pure $ case name of
        Just "com.oracle.truffle.api.interop.InvalidBufferOffsetException" -> 1
        Just "com.oracle.truffle.api.interop.UnsupportedMessageException" -> 2
        Just "com.oracle.truffle.api.interop.UnsupportedTypeException" -> 3
        _ -> -1
{-# OPAQUE caughtRead #-}
