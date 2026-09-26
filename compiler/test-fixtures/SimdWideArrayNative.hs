-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main (main) where
import GHC.Exts
import Control.Monad (forM_)
import qualified SimdWideArrayScalar as Scalar

initialize :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> State# RealWorld -> State# RealWorld
initialize bytes size seed i s = case i ==# size of
  1# -> s
  _ -> case writeWord8Array# bytes i (wordToWord8# (int2Word# (seed +# i *# 37#))) s of
    s' -> initialize bytes size seed (i +# 1#) s'
checksum :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
checksum bytes size i total s = case i ==# size of
  1# -> (# s, total #)
  _ -> case readWord8Array# bytes i s of
    (# s', value #) -> checksum bytes size (i +# 1#) (total +# word2Int# (word8ToWord# value) *# (i *# 2# +# 1#)) s'
run :: String -> Int -> Int -> (Int,Int)
run name (I# seed) (I# offset) = runRW# (\s -> case size name of { I# n ->
  case newByteArray# n s of { (# s1, bytes #) ->
  case initialize bytes n seed 0# s1 of { s2 ->
  case select name bytes offset seed of { result ->
  case checksum bytes n 0# 0# s2 of { (# _, digest #) -> (I# result, I# digest) } } } } })
size :: String -> Int
size name = case name of
  "int16X16IndexPacked" -> 96
  "int16X16IndexScalar" -> 96
  "int16X16ReadPacked" -> 96
  "int16X16ReadScalar" -> 96
  "int16X16WritePacked" -> 96
  "int16X16WriteScalar" -> 96
  "word16X16IndexPacked" -> 96
  "word16X16IndexScalar" -> 96
  "word16X16ReadPacked" -> 96
  "word16X16ReadScalar" -> 96
  "word16X16WritePacked" -> 96
  "word16X16WriteScalar" -> 96
  "int32X8IndexPacked" -> 96
  "int32X8IndexScalar" -> 96
  "int32X8ReadPacked" -> 96
  "int32X8ReadScalar" -> 96
  "int32X8WritePacked" -> 96
  "int32X8WriteScalar" -> 96
  "word32X8IndexPacked" -> 96
  "word32X8IndexScalar" -> 96
  "word32X8ReadPacked" -> 96
  "word32X8ReadScalar" -> 96
  "word32X8WritePacked" -> 96
  "word32X8WriteScalar" -> 96
  "int32X16IndexPacked" -> 192
  "int32X16IndexScalar" -> 192
  "int32X16ReadPacked" -> 192
  "int32X16ReadScalar" -> 192
  "int32X16WritePacked" -> 192
  "int32X16WriteScalar" -> 192
  "word32X16IndexPacked" -> 192
  "word32X16IndexScalar" -> 192
  "word32X16ReadPacked" -> 192
  "word32X16ReadScalar" -> 192
  "word32X16WritePacked" -> 192
  "word32X16WriteScalar" -> 192
  "int64X4IndexPacked" -> 96
  "int64X4IndexScalar" -> 96
  "int64X4ReadPacked" -> 96
  "int64X4ReadScalar" -> 96
  "int64X4WritePacked" -> 96
  "int64X4WriteScalar" -> 96
  "word64X4IndexPacked" -> 96
  "word64X4IndexScalar" -> 96
  "word64X4ReadPacked" -> 96
  "word64X4ReadScalar" -> 96
  "word64X4WritePacked" -> 96
  "word64X4WriteScalar" -> 96
  "int64X8IndexPacked" -> 192
  "int64X8IndexScalar" -> 192
  "int64X8ReadPacked" -> 192
  "int64X8ReadScalar" -> 192
  "int64X8WritePacked" -> 192
  "int64X8WriteScalar" -> 192
  "word64X8IndexPacked" -> 192
  "word64X8IndexScalar" -> 192
  "word64X8ReadPacked" -> 192
  "word64X8ReadScalar" -> 192
  "word64X8WritePacked" -> 192
  "word64X8WriteScalar" -> 192
  "floatX8IndexPacked" -> 96
  "floatX8IndexScalar" -> 96
  "floatX8ReadPacked" -> 96
  "floatX8ReadScalar" -> 96
  "floatX8WritePacked" -> 96
  "floatX8WriteScalar" -> 96
  "floatX16IndexPacked" -> 192
  "floatX16IndexScalar" -> 192
  "floatX16ReadPacked" -> 192
  "floatX16ReadScalar" -> 192
  "floatX16WritePacked" -> 192
  "floatX16WriteScalar" -> 192
  "doubleX4IndexPacked" -> 96
  "doubleX4IndexScalar" -> 96
  "doubleX4ReadPacked" -> 96
  "doubleX4ReadScalar" -> 96
  "doubleX4WritePacked" -> 96
  "doubleX4WriteScalar" -> 96
  "doubleX8IndexPacked" -> 192
  "doubleX8IndexScalar" -> 192
  "doubleX8ReadPacked" -> 192
  "doubleX8ReadScalar" -> 192
  "doubleX8WritePacked" -> 192
  "doubleX8WriteScalar" -> 192
  _ -> error "Unknown SIMD wide scalar input"
select :: String -> MutableByteArray# RealWorld -> Int# -> Int# -> Int#
select name = case name of
  "int16X16IndexPacked" -> Scalar.int16X16IndexPacked
  "int16X16IndexScalar" -> Scalar.int16X16IndexScalar
  "int16X16ReadPacked" -> Scalar.int16X16ReadPacked
  "int16X16ReadScalar" -> Scalar.int16X16ReadScalar
  "int16X16WritePacked" -> Scalar.int16X16WritePacked
  "int16X16WriteScalar" -> Scalar.int16X16WriteScalar
  "word16X16IndexPacked" -> Scalar.word16X16IndexPacked
  "word16X16IndexScalar" -> Scalar.word16X16IndexScalar
  "word16X16ReadPacked" -> Scalar.word16X16ReadPacked
  "word16X16ReadScalar" -> Scalar.word16X16ReadScalar
  "word16X16WritePacked" -> Scalar.word16X16WritePacked
  "word16X16WriteScalar" -> Scalar.word16X16WriteScalar
  "int32X8IndexPacked" -> Scalar.int32X8IndexPacked
  "int32X8IndexScalar" -> Scalar.int32X8IndexScalar
  "int32X8ReadPacked" -> Scalar.int32X8ReadPacked
  "int32X8ReadScalar" -> Scalar.int32X8ReadScalar
  "int32X8WritePacked" -> Scalar.int32X8WritePacked
  "int32X8WriteScalar" -> Scalar.int32X8WriteScalar
  "word32X8IndexPacked" -> Scalar.word32X8IndexPacked
  "word32X8IndexScalar" -> Scalar.word32X8IndexScalar
  "word32X8ReadPacked" -> Scalar.word32X8ReadPacked
  "word32X8ReadScalar" -> Scalar.word32X8ReadScalar
  "word32X8WritePacked" -> Scalar.word32X8WritePacked
  "word32X8WriteScalar" -> Scalar.word32X8WriteScalar
  "int32X16IndexPacked" -> Scalar.int32X16IndexPacked
  "int32X16IndexScalar" -> Scalar.int32X16IndexScalar
  "int32X16ReadPacked" -> Scalar.int32X16ReadPacked
  "int32X16ReadScalar" -> Scalar.int32X16ReadScalar
  "int32X16WritePacked" -> Scalar.int32X16WritePacked
  "int32X16WriteScalar" -> Scalar.int32X16WriteScalar
  "word32X16IndexPacked" -> Scalar.word32X16IndexPacked
  "word32X16IndexScalar" -> Scalar.word32X16IndexScalar
  "word32X16ReadPacked" -> Scalar.word32X16ReadPacked
  "word32X16ReadScalar" -> Scalar.word32X16ReadScalar
  "word32X16WritePacked" -> Scalar.word32X16WritePacked
  "word32X16WriteScalar" -> Scalar.word32X16WriteScalar
  "int64X4IndexPacked" -> Scalar.int64X4IndexPacked
  "int64X4IndexScalar" -> Scalar.int64X4IndexScalar
  "int64X4ReadPacked" -> Scalar.int64X4ReadPacked
  "int64X4ReadScalar" -> Scalar.int64X4ReadScalar
  "int64X4WritePacked" -> Scalar.int64X4WritePacked
  "int64X4WriteScalar" -> Scalar.int64X4WriteScalar
  "word64X4IndexPacked" -> Scalar.word64X4IndexPacked
  "word64X4IndexScalar" -> Scalar.word64X4IndexScalar
  "word64X4ReadPacked" -> Scalar.word64X4ReadPacked
  "word64X4ReadScalar" -> Scalar.word64X4ReadScalar
  "word64X4WritePacked" -> Scalar.word64X4WritePacked
  "word64X4WriteScalar" -> Scalar.word64X4WriteScalar
  "int64X8IndexPacked" -> Scalar.int64X8IndexPacked
  "int64X8IndexScalar" -> Scalar.int64X8IndexScalar
  "int64X8ReadPacked" -> Scalar.int64X8ReadPacked
  "int64X8ReadScalar" -> Scalar.int64X8ReadScalar
  "int64X8WritePacked" -> Scalar.int64X8WritePacked
  "int64X8WriteScalar" -> Scalar.int64X8WriteScalar
  "word64X8IndexPacked" -> Scalar.word64X8IndexPacked
  "word64X8IndexScalar" -> Scalar.word64X8IndexScalar
  "word64X8ReadPacked" -> Scalar.word64X8ReadPacked
  "word64X8ReadScalar" -> Scalar.word64X8ReadScalar
  "word64X8WritePacked" -> Scalar.word64X8WritePacked
  "word64X8WriteScalar" -> Scalar.word64X8WriteScalar
  "floatX8IndexPacked" -> Scalar.floatX8IndexPacked
  "floatX8IndexScalar" -> Scalar.floatX8IndexScalar
  "floatX8ReadPacked" -> Scalar.floatX8ReadPacked
  "floatX8ReadScalar" -> Scalar.floatX8ReadScalar
  "floatX8WritePacked" -> Scalar.floatX8WritePacked
  "floatX8WriteScalar" -> Scalar.floatX8WriteScalar
  "floatX16IndexPacked" -> Scalar.floatX16IndexPacked
  "floatX16IndexScalar" -> Scalar.floatX16IndexScalar
  "floatX16ReadPacked" -> Scalar.floatX16ReadPacked
  "floatX16ReadScalar" -> Scalar.floatX16ReadScalar
  "floatX16WritePacked" -> Scalar.floatX16WritePacked
  "floatX16WriteScalar" -> Scalar.floatX16WriteScalar
  "doubleX4IndexPacked" -> Scalar.doubleX4IndexPacked
  "doubleX4IndexScalar" -> Scalar.doubleX4IndexScalar
  "doubleX4ReadPacked" -> Scalar.doubleX4ReadPacked
  "doubleX4ReadScalar" -> Scalar.doubleX4ReadScalar
  "doubleX4WritePacked" -> Scalar.doubleX4WritePacked
  "doubleX4WriteScalar" -> Scalar.doubleX4WriteScalar
  "doubleX8IndexPacked" -> Scalar.doubleX8IndexPacked
  "doubleX8IndexScalar" -> Scalar.doubleX8IndexScalar
  "doubleX8ReadPacked" -> Scalar.doubleX8ReadPacked
  "doubleX8ReadScalar" -> Scalar.doubleX8ReadScalar
  "doubleX8WritePacked" -> Scalar.doubleX8WritePacked
  "doubleX8WriteScalar" -> Scalar.doubleX8WriteScalar
  _ -> error "Unknown SIMD wide scalar operation"
main :: IO ()
main = do
  input <- getContents
  forM_ (lines input) $ \line -> case words line of
    [entry, seed, offset] -> do
      let (value,digest) = run entry (read seed) (read offset)
      putStrLn (entry ++ "\t" ++ seed ++ "\t" ++ offset ++ "\t" ++ show value ++ "\t" ++ show digest)
    _ -> error "Expected entry seed offset"
