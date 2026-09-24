{-# LANGUAGE MagicHash, UnboxedTuples #-}
module BigNatLiteralAudit where
import GHC.Exts
import Numeric.Natural (Natural)
import GHC.Num.Integer (integerToBigNatSign#)

{-# OPAQUE integerIdentity #-}
integerIdentity :: Integer -> Integer
integerIdentity x = x
{-# OPAQUE naturalIdentity #-}
naturalIdentity :: Natural -> Natural
naturalIdentity x = x

{-# OPAQUE integerChoice #-}
integerChoice :: Int# -> Integer
integerChoice x = case andI# x 15# of
  0# -> (0)
  1# -> (1)
  2# -> (-1)
  3# -> (9223372036854775807)
  4# -> (9223372036854775808)
  5# -> (18446744073709551615)
  6# -> (18446744073709551616)
  7# -> (18446744073709551617)
  8# -> (170141183460469231731687303715884105727)
  9# -> (170141183460469231731687303715884105728)
  10# -> (340282366920938463463374607431768211456)
  11# -> (340282366920938463472597979468622987265)
  12# -> (-340282366920938463481821351505477763071)
  13# -> (6277101735386680763835789423207666416102355444464034512895)
  14# -> (6277101735386680763835789423207666416102355444464034512897)
  _ -> (57896044618658097711785492504343953926975274699741220483192166611388333031427)

{-# OPAQUE naturalChoice #-}
naturalChoice :: Int# -> Natural
naturalChoice x = case andI# x 15# of
  0# -> (0)
  1# -> (1)
  2# -> (1)
  3# -> (9223372036854775807)
  4# -> (9223372036854775808)
  5# -> (18446744073709551615)
  6# -> (18446744073709551616)
  7# -> (18446744073709551617)
  8# -> (170141183460469231731687303715884105727)
  9# -> (170141183460469231731687303715884105728)
  10# -> (340282366920938463463374607431768211456)
  11# -> (340282366920938463472597979468622987265)
  12# -> (340282366920938463481821351505477763071)
  13# -> (6277101735386680763835789423207666416102355444464034512895)
  14# -> (6277101735386680763835789423207666416102355444464034512897)
  _ -> (57896044618658097711785492504343953926975274699741220483192166611388333031427)

{-# OPAQUE integerRoundTrip #-}
integerRoundTrip :: Int# -> Int# -> Int#
integerRoundTrip x _ = case (fromInteger (integerIdentity (toInteger (I# x))) :: Int) of I# n -> n
{-# OPAQUE naturalRoundTrip #-}
naturalRoundTrip :: Int# -> Int# -> Int#
naturalRoundTrip x _ = case (fromIntegral (naturalIdentity (fromIntegral (W# (int2Word# x)))) :: Word) of W# n -> word2Int# n
{-# OPAQUE integerLiteral #-}
integerLiteral :: Int# -> Int# -> Int#
integerLiteral x _ = case (fromInteger (integerIdentity (integerChoice x)) :: Int) of I# n -> n
{-# OPAQUE naturalLiteral #-}
naturalLiteral :: Int# -> Int# -> Int#
naturalLiteral x _ = case (fromIntegral (naturalIdentity (naturalChoice x)) :: Word) of W# n -> word2Int# n
{-# OPAQUE magnitudeSize #-}
magnitudeSize :: Int# -> Int# -> Int#
magnitudeSize x _ = case integerToBigNatSign# (integerChoice x) of (# _, b #) -> sizeofByteArray# b
{-# OPAQUE magnitudeSign #-}
magnitudeSign :: Int# -> Int# -> Int#
magnitudeSign x _ = case integerToBigNatSign# (integerChoice x) of (# s, _ #) -> s
{-# OPAQUE magnitudeByte #-}
magnitudeByte :: Int# -> Int# -> Int#
magnitudeByte x i = case integerToBigNatSign# (integerChoice x) of
 (# _, b #) -> case i >=# 0# of
  0# -> -1#
  _ -> case i <# sizeofByteArray# b of
   0# -> -1#
   _ -> word2Int# (word8ToWord# (indexWord8Array# b i))
{-# OPAQUE magnitudeWord #-}
magnitudeWord :: Int# -> Int# -> Int#
magnitudeWord x i = case integerToBigNatSign# (integerChoice x) of
 (# _, b #) -> case i >=# 0# of
  0# -> -1#
  _ -> case i <# quotInt# (sizeofByteArray# b) 8# of
   0# -> -1#
   _ -> word2Int# (indexWordArray# b i)

-- Keep actual arithmetic cold dependencies visible as strict frontiers.
{-# OPAQUE integerAddFrontier #-}
integerAddFrontier :: Int# -> Int# -> Int#
integerAddFrontier x y = case (fromInteger (toInteger (I# x) + toInteger (I# y)) :: Int) of I# n -> n
{-# OPAQUE naturalAddFrontier #-}
naturalAddFrontier :: Int# -> Int# -> Int#
naturalAddFrontier x y = case (fromIntegral ((fromIntegral (W# (int2Word# x)) :: Natural) + fromIntegral (W# (int2Word# y))) :: Word) of W# n -> word2Int# n
