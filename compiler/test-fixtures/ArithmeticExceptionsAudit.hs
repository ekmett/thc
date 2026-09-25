-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ArithmeticExceptionsAudit where

import GHC.Exts
import GHC.Internal.Exception.Type (SomeException, ArithException(..), fromException)

-- Use the original existential exception and its real Exception/Typeable
-- dictionary. A payload with only the right constructor spelling cannot pass.
classify :: SomeException -> Int
{-# OPAQUE classify #-}
classify exception = case fromException exception of
  Just DivideByZero -> 101
  Just Overflow -> 102
  Just Underflow -> 103
  _ -> -999

caught :: (State# RealWorld -> (# State# RealWorld, Int #)) -> Int#
{-# OPAQUE caught #-}
caught action = case runRW# (\s -> catch# action
    (\exception state -> (# state, classify exception #)) s) of
  (# _, I# answer #) -> answer

scalarDivZeroBody, scalarOverflowBody, scalarUnderflowBody :: Int# -> Int#
{-# OPAQUE scalarDivZeroBody #-}
{-# OPAQUE scalarOverflowBody #-}
{-# OPAQUE scalarUnderflowBody #-}
scalarDivZeroBody x = case x of 0# -> raiseDivZero# (# #); _ -> x +# 11#
scalarOverflowBody x = case x of 0# -> raiseOverflow# (# #); _ -> x +# 12#
scalarUnderflowBody x = case x of 0# -> raiseUnderflow# (# #); _ -> x +# 13#

tupleDivZeroBody, tupleOverflowBody, tupleUnderflowBody :: Int# -> (# Int#, Int# #)
{-# OPAQUE tupleDivZeroBody #-}
{-# OPAQUE tupleOverflowBody #-}
{-# OPAQUE tupleUnderflowBody #-}
tupleDivZeroBody x = case x of 0# -> raiseDivZero# (# #); _ -> (# x, 21# #)
tupleOverflowBody x = case x of 0# -> raiseOverflow# (# #); _ -> (# x, 22# #)
tupleUnderflowBody x = case x of 0# -> raiseUnderflow# (# #); _ -> (# x, 23# #)

scalarDivZero, scalarOverflow, scalarUnderflow, tupleDivZero, tupleOverflow, tupleUnderflow :: Int# -> Int#
{-# OPAQUE scalarDivZero #-}
{-# OPAQUE scalarOverflow #-}
{-# OPAQUE scalarUnderflow #-}
{-# OPAQUE tupleDivZero #-}
{-# OPAQUE tupleOverflow #-}
{-# OPAQUE tupleUnderflow #-}
scalarDivZero x = caught (\s -> case scalarDivZeroBody x of y -> (# s, I# y #))
scalarOverflow x = caught (\s -> case scalarOverflowBody x of y -> (# s, I# y #))
scalarUnderflow x = caught (\s -> case scalarUnderflowBody x of y -> (# s, I# y #))
tupleDivZero x = caught (\s -> case tupleDivZeroBody x of (# a, b #) -> (# s, I# (a +# b) #))
tupleOverflow x = caught (\s -> case tupleOverflowBody x of (# a, b #) -> (# s, I# (a +# b) #))
tupleUnderflow x = caught (\s -> case tupleUnderflowBody x of (# a, b #) -> (# s, I# (a +# b) #))
