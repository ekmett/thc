-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1, native 64-bit targets
--
-- Original GHC register constructors and nested aggregates exercised across
-- function calls, partial applications, captures and constructor fields.
module FourWayAggregateFields where

import GHC.Exts
import GHC.Word (Word64(W64#))
import GHC.CmmToAsm.Format (Format(..), VirtualRegWithFormat(..))
import GHC.Platform.Reg (VirtualReg(..))
import GHC.Types.Unique (getKey, mkUniqueGrimily)

-- These are the original GHC constructors, not a lookalike declaration.
-- VirtualRegWithFormat UNPACKs VirtualReg to a four-way Word64# sum, followed
-- by an evaluated, lifted Format. OPAQUE boundaries preserve that heap field.
{-# OPAQUE makeOriginal #-}
makeOriginal :: Int# -> Word64# -> VirtualRegWithFormat
makeOriginal selector bits =
  let unique = mkUniqueGrimily (W64# bits)
  in case andI# selector 3# of
    0# -> VirtualRegWithFormat (VirtualRegI unique) II64
    1# -> VirtualRegWithFormat (VirtualRegHi unique) FF64
    2# -> VirtualRegWithFormat (VirtualRegD unique) II32
    _ -> VirtualRegWithFormat (VirtualRegV128 unique) FF32

{-# OPAQUE consumeOriginal #-}
consumeOriginal :: VirtualRegWithFormat -> (# Int#, Word64# #)
consumeOriginal (VirtualRegWithFormat register _) = case register of
  VirtualRegI unique -> case getKey unique of W64# bits -> (# 11#, bits #)
  VirtualRegHi unique -> case getKey unique of W64# bits -> (# 23#, bits #)
  VirtualRegD unique -> case getKey unique of W64# bits -> (# 37#, bits #)
  VirtualRegV128 unique -> case getKey unique of W64# bits -> (# 53#, bits #)

{-# OPAQUE consumeFormat #-}
consumeFormat :: VirtualRegWithFormat -> Word64#
consumeFormat (VirtualRegWithFormat _ format) = wordToWord64# (case format of
  II64 -> 641##
  FF64 -> 642##
  II32 -> 321##
  FF32 -> 322##
  _ -> 999##)

{-# OPAQUE consumeDefault #-}
consumeDefault :: VirtualRegWithFormat -> Word64#
consumeDefault (VirtualRegWithFormat register _) = case register of
  VirtualRegI unique -> case getKey unique of W64# bits -> bits
  _ -> wordToWord64# 18446744073709551615##

{-# OPAQUE roundtripPayload #-}
roundtripPayload :: Int# -> Word64# -> Word64#
roundtripPayload selector bits = case consumeOriginal (makeOriginal selector bits) of
  (# _, result #) -> result

{-# OPAQUE roundtripTag #-}
roundtripTag :: Int# -> Word64# -> Word64#
roundtripTag selector bits = case consumeOriginal (makeOriginal selector bits) of
  (# tag, _ #) -> wordToWord64# (int2Word# tag)

{-# OPAQUE formatTag #-}
formatTag :: Int# -> Word64# -> Word64#
formatTag selector bits = consumeFormat (makeOriginal selector bits)

{-# OPAQUE defaultArm #-}
defaultArm :: Int# -> Word64# -> Word64#
defaultArm selector bits = consumeDefault (makeOriginal selector bits)

data Retained = Retained !VirtualRegWithFormat !VirtualRegWithFormat

{-# OPAQUE makeRetained #-}
makeRetained :: Int# -> Word64# -> Retained
makeRetained selector bits = Retained (makeOriginal selector bits)
  (makeOriginal (selector +# 1#) (xor64# bits (wordToWord64# 9223372036854775808##)))

-- Each observation depends on both values. In particular the first original
-- object stays live while the second original object crosses a consumer call.
{-# OPAQUE retainedFirst #-}
retainedFirst :: Int# -> Word64# -> Word64#
retainedFirst selector bits = case makeRetained selector bits of
  Retained first second -> case consumeOriginal second of
    (# tag, _ #) -> case consumeOriginal first of
      (# _, value #) -> plusWord64# value (wordToWord64# (int2Word# tag))

{-# OPAQUE retainedSecond #-}
retainedSecond :: Int# -> Word64# -> Word64#
retainedSecond selector bits = case makeRetained selector bits of
  Retained first second -> case consumeOriginal first of
    (# tag, _ #) -> case consumeOriginal second of
      (# _, value #) -> plusWord64# value (wordToWord64# (int2Word# tag))

{-# OPAQUE retainedTags #-}
retainedTags :: Int# -> Word64# -> Word64#
retainedTags selector bits = case makeRetained selector bits of
  Retained first second -> case consumeOriginal second of
    (# secondTag, _ #) -> case consumeOriginal first of
      (# firstTag, _ #) -> wordToWord64# (int2Word# (firstTag *# 100# +# secondTag))

{-# OPAQUE applyProducer #-}
applyProducer :: (Word64# -> VirtualRegWithFormat) -> Word64# -> VirtualRegWithFormat
applyProducer producer bits = producer bits

{-# OPAQUE residualProducer #-}
residualProducer :: Int# -> Word64# -> Word64#
residualProducer selector bits =
  case consumeOriginal (applyProducer (makeOriginal selector) bits) of
    (# tag, result #) -> plusWord64# result (wordToWord64# (int2Word# tag))

{-# OPAQUE consumeWithSalt #-}
consumeWithSalt :: VirtualRegWithFormat -> Word64# -> Word64#
consumeWithSalt value salt = case consumeOriginal value of
  (# tag, bits #) -> plusWord64# (xor64# bits salt) (wordToWord64# (int2Word# tag))

{-# OPAQUE applyConsumer #-}
applyConsumer :: (Word64# -> Word64#) -> Word64# -> Word64#
applyConsumer consumer salt = consumer salt

{-# OPAQUE residualConsumer #-}
residualConsumer :: Int# -> Word64# -> Word64#
residualConsumer selector bits =
  case makeOriginal selector bits of
    value@VirtualRegWithFormat{} ->
      applyConsumer (consumeWithSalt value) (wordToWord64# 9223372036854775808##)

-- The sum is inside a tuple, never inside another sum's payload. Retain the
-- erased State#/empty-tuple distinctions and a lazy lifted neighbour as well.
type Four = (# Word64# | Word64# | Word64# | Word64# #)
type Nested = (# Int#, (# State# RealWorld, (# #), Four, Int #), Word64# #)

data Failure = Failure
lazyBottom :: Int
lazyBottom = raise# Failure

{-# OPAQUE unpackOriginal #-}
unpackOriginal :: VirtualRegWithFormat -> Four
unpackOriginal (VirtualRegWithFormat register _) = case register of
  VirtualRegI unique -> case getKey unique of W64# bits -> (# bits | | | #)
  VirtualRegHi unique -> case getKey unique of W64# bits -> (# | bits | | #)
  VirtualRegD unique -> case getKey unique of W64# bits -> (# | | bits | #)
  VirtualRegV128 unique -> case getKey unique of W64# bits -> (# | | | bits #)

{-# OPAQUE consumeFour #-}
consumeFour :: Four -> (# Int#, Word64# #)
consumeFour value = case value of
  (# bits | | | #) -> (# 11#, bits #)
  (# | bits | | #) -> (# 23#, bits #)
  (# | | bits | #) -> (# 37#, bits #)
  (# | | | bits #) -> (# 53#, bits #)

{-# OPAQUE makeNested #-}
makeNested :: Int# -> Word64# -> Nested
makeNested selector bits = case unpackOriginal (makeOriginal selector bits) of
  value -> (# selector -# 7#, (# realWorld#, (# #), value, lazyBottom #),
    plusWord64# bits (wordToWord64# 43##) #)

{-# OPAQUE passNested #-}
passNested :: Nested -> Nested
passNested value = value

{-# OPAQUE consumeNested #-}
consumeNested :: Nested -> Word64# -> Word64#
consumeNested (# before, (# _, (# #), value, _ #), after #) salt =
  case consumeFour value of
    (# tag, bits #) -> plusWord64# (xor64# bits salt)
      (plusWord64# after (wordToWord64# (int2Word# (before +# tag))))

{-# OPAQUE nestedRoundtrip #-}
nestedRoundtrip :: Int# -> Word64# -> Word64#
nestedRoundtrip selector bits = consumeNested (passNested (makeNested selector bits)) (wordToWord64# 0##)

{-# OPAQUE applyNestedProducer #-}
applyNestedProducer :: (Word64# -> Nested) -> Word64# -> Nested
applyNestedProducer producer bits = producer bits

{-# OPAQUE nestedResidualProducer #-}
nestedResidualProducer :: Int# -> Word64# -> Word64#
nestedResidualProducer selector bits =
  consumeNested (applyNestedProducer (makeNested selector) bits) (wordToWord64# 9223372036854775808##)

{-# OPAQUE nestedResidualConsumer #-}
nestedResidualConsumer :: Int# -> Word64# -> Word64#
nestedResidualConsumer selector bits = case makeNested selector bits of
  value -> applyConsumer (consumeNested value) (wordToWord64# 9223372036854775808##)

data NestedBox = NestedBox Int# Nested Word64#

{-# OPAQUE makeNestedBox #-}
makeNestedBox :: Int# -> Word64# -> NestedBox
makeNestedBox selector bits = NestedBox (selector +# 19#) (makeNested selector bits)
  (xor64# bits (wordToWord64# 9223372036854775808##))

{-# OPAQUE nestedHeap #-}
nestedHeap :: Int# -> Word64# -> Word64#
nestedHeap selector bits = case makeNestedBox selector bits of
  NestedBox before value after -> plusWord64# (consumeNested value after)
    (wordToWord64# (int2Word# before))

-- A separate genuine mixed family exercises inactive reference padding. It is
-- not substituted for the original GHC Word64-only constructor proof above.
type MixedFour = (# Int | Word64# | (# #) | Int# #)
type MixedNested = (# Int#, (# State# RealWorld, (# #), MixedFour, Int #), Word64# #)

{-# OPAQUE makeMixedFour #-}
makeMixedFour :: Int# -> Word64# -> MixedFour
makeMixedFour selector bits = case andI# selector 3# of
  0# -> (# lazyBottom | | | #)
  1# -> (# | bits | | #)
  2# -> (# | | (# #) | #)
  _ -> (# | | | selector #)

{-# OPAQUE consumeMixedFour #-}
consumeMixedFour :: MixedFour -> Word64#
consumeMixedFour value = case value of
  (# _ | | | #) -> wordToWord64# 11##
  (# | bits | | #) -> plusWord64# bits (wordToWord64# 23##)
  (# | | (# #) | #) -> wordToWord64# 37##
  (# | | | selector #) -> wordToWord64# (int2Word# (selector +# 53#))

{-# OPAQUE makeMixedNested #-}
makeMixedNested :: Int# -> Word64# -> MixedNested
makeMixedNested selector bits = case makeMixedFour selector bits of
  value -> (# selector -# 7#, (# realWorld#, (# #), value, lazyBottom #),
    plusWord64# bits (wordToWord64# 43##) #)

{-# OPAQUE consumeMixedNested #-}
consumeMixedNested :: MixedNested -> Word64# -> Word64#
consumeMixedNested (# before, (# _, (# #), value, _ #), after #) salt =
  plusWord64# (xor64# (consumeMixedFour value) salt)
    (plusWord64# after (wordToWord64# (int2Word# before)))

{-# OPAQUE mixedNested #-}
mixedNested :: Int# -> Word64# -> Word64#
mixedNested selector bits = consumeMixedNested (makeMixedNested selector bits) (wordToWord64# 0##)

{-# OPAQUE mixedNestedCapture #-}
mixedNestedCapture :: Int# -> Word64# -> Word64#
mixedNestedCapture selector bits = case makeMixedNested selector bits of
  value -> applyConsumer (consumeMixedNested value) (wordToWord64# 9223372036854775808##)

data MixedBox = MixedBox MixedNested Word64#

{-# OPAQUE makeMixedBox #-}
makeMixedBox :: Int# -> Word64# -> MixedBox
makeMixedBox selector bits = MixedBox (makeMixedNested selector bits)
  (xor64# bits (wordToWord64# 9223372036854775808##))

{-# OPAQUE mixedNestedHeap #-}
mixedNestedHeap :: Int# -> Word64# -> Word64#
mixedNestedHeap selector bits = case makeMixedBox selector bits of
  MixedBox value after -> consumeMixedNested value after
