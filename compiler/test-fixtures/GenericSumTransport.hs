-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
-- |
-- Module      : GenericSumTransport
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1, native 64-bit targets
--
-- Pure observations of active sum alternatives; inactive native registers are
-- deliberately never observed. OPAQUE boundaries retain genuine sum transport.
module GenericSumTransport where
import GHC.Exts

type AddressChoice = (# Addr# | Int# #)
{-# OPAQUE addressMake #-}
addressMake :: Int# -> Addr# -> Int# -> AddressChoice
addressMake selector address bits = case andI# selector 1# of
  0# -> (# address | #)
  _ -> (# | bits #)

{-# OPAQUE addressConsume #-}
addressConsume :: Addr# -> AddressChoice -> Int#
addressConsume original value = case value of
  (# address | #) -> eqAddr# original address +# word2Int# (word8ToWord# (indexWord8OffAddr# address 0#))
  (# | bits #) -> bits

addressCase :: Int# -> Int# -> Int#
addressCase selector bits =
  let address = plusAddr# "managed-address"# (andI# bits 7#)
  in addressConsume address (addressMake selector address bits)

type Vectors = (# Int64X2# | FloatX4# | (# #) #)
{-# OPAQUE vectorMake #-}
vectorMake :: Int# -> Int# -> Vectors
vectorMake selector bits = case andI# selector 3# of
  0# -> (# packInt64X2# (# intToInt64# bits, intToInt64# (notI# bits) #) | | #)
  1# -> (# | broadcastFloatX4# (int2Float# (andI# bits 255#)) | #)
  _ -> (# | | (# #) #)

{-# OPAQUE vectorConsume #-}
vectorConsume :: Vectors -> Int#
vectorConsume value = case value of
  (# vector | | #) -> case unpackInt64X2# vector of
    (# a,b #) -> xorI# (int64ToInt# a) (uncheckedIShiftRL# (int64ToInt# b) 1#)
  (# | vector | #) -> case unpackFloatX4# vector of
    (# a,b,c,d #) -> float2Int# (plusFloat# (plusFloat# a b) (plusFloat# c d))
  (# | | (# #) #) -> 71#

vectorCase :: Int# -> Int# -> Int#
vectorCase selector bits = vectorConsume (vectorMake selector bits)

{-# OPAQUE vectorSelect #-}
vectorSelect :: Vectors -> Int64X2#
vectorSelect value = case value of
  (# vector | | #) -> vector
  (# | vector | #) -> case unpackFloatX4# vector of
    (# a,_,_,_ #) -> broadcastInt64X2# (intToInt64# (float2Int# a))
  (# | | (# #) #) -> broadcastInt64X2# (intToInt64# 73#)
vectorResultCase :: Int# -> Int# -> Int#
vectorResultCase selector bits = case unpackInt64X2# (vectorSelect (vectorMake selector bits)) of
  (# a,b #) -> xorI# (int64ToInt# a) (uncheckedIShiftRL# (int64ToInt# b) 1#)

type Nested = (# (# State# RealWorld | (# #) #) | (# AddressChoice, Int# #) | Vectors | (# (Int,Int) | Int# #) #)
{-# OPAQUE nestedMake #-}
nestedMake :: Int# -> Int# -> State# RealWorld -> Nested
nestedMake selector bits state = case andI# selector 3# of
  0# -> case andI# selector 4# of
    0# -> (# (# state | #) | | | #)
    _ -> (# (# | (# #) #) | | | #)
  1# -> (# | (# addressMake (uncheckedIShiftRL# selector 2#) "nested-address"# bits, notI# bits #) | | #)
  2# -> (# | | vectorMake (uncheckedIShiftRL# selector 2#) bits | #)
  _ -> case andI# selector 4# of
    0# -> let bottom = bottom in (# | | | (# (I# bits,bottom) | #) #)
    _ -> (# | | | (# | bits #) #)

{-# OPAQUE nestedConsume #-}
nestedConsume :: Nested -> Int#
nestedConsume value = case value of
  (# (# _ | #) | | | #) -> 101#
  (# (# | (# #) #) | | | #) -> 103#
  (# | (# choice, neighbor #) | | #) -> xorI# neighbor (addressConsume "nested-address"# choice)
  (# | | vector | #) -> vectorConsume vector
  (# | | | (# (I# bits,_) | #) #) -> bits +# 107#
  (# | | | (# | bits #) #) -> bits

nestedCase :: Int# -> Int# -> Int#
nestedCase selector bits = runRW# (\state -> nestedConsume (nestedMake selector bits state))

type Around = (# Int#, (# State# RealWorld, (# #), Nested #), Int# #)
{-# OPAQUE aroundMake #-}
aroundMake :: Int# -> Int# -> State# RealWorld -> Around
aroundMake selector bits state = (# bits, (# state, (# #), nestedMake selector bits state #), notI# bits #)
{-# OPAQUE aroundConsume #-}
aroundConsume :: Around -> Int#
aroundConsume (# before, (# _, (# #), nested #), after #) =
  xorI# (xorI# before after) (nestedConsume nested)
aroundCase :: Int# -> Int# -> Int#
aroundCase selector bits = runRW# (\state -> aroundConsume (aroundMake selector bits state))

data Saved = Saved Int# Nested Int#
{-# OPAQUE save #-}
save :: Int# -> Int# -> State# RealWorld -> Saved
save selector bits state = Saved bits (nestedMake selector bits state) (notI# bits)
{-# OPAQUE restore #-}
restore :: Saved -> Int#
restore (Saved before nested after) = xorI# (xorI# before after) (nestedConsume nested)
heapCase :: Int# -> Int# -> Int#
heapCase selector bits = runRW# (\state -> restore (save selector bits state))

{-# OPAQUE applyLater #-}
applyLater :: (Int# -> Int#) -> Int# -> Int#
applyLater function input = function input
captureCase :: Int# -> Int# -> Int#
captureCase selector bits = runRW# (\state -> case nestedMake selector bits state of
  nested -> applyLater (\extra -> xorI# extra (nestedConsume nested)) bits)

{-# OPAQUE passConsumer #-}
passConsumer :: (Nested -> Int#) -> Nested -> Int#
passConsumer function input = function input
residualCase :: Int# -> Int# -> Int#
residualCase selector bits = runRW# (\state -> passConsumer nestedConsume (nestedMake selector bits state))

{-# OPAQUE consumeWith #-}
consumeWith :: Nested -> Int# -> Int#
consumeWith nested extra = xorI# extra (nestedConsume nested)
papCase :: Int# -> Int# -> Int#
papCase selector bits = runRW# (\state -> applyLater (consumeWith (nestedMake selector bits state)) bits)

{-# OPAQUE rotate #-}
rotate :: Int# -> Nested -> Nested -> Int#
rotate count first second = case count ==# 0# of
  1# -> nestedConsume first +# (3# *# nestedConsume second)
  _ -> rotate (count -# 1#) second first
pairedCase :: Int# -> Int# -> Int#
pairedCase selector bits = runRW# (\state -> rotate 3# (nestedMake selector bits state)
  (nestedMake (selector +# 1#) (notI# bits) state))
