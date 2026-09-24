-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module TagToEnumAudit where
import GHC.Exts
import TagToEnumExternal (External, externalCode)

data Colour = Red | Green | Blue
{-# OPAQUE chooseBool #-}
chooseBool :: Int# -> Bool
chooseBool n = tagToEnum# n
{-# OPAQUE chooseOrdering #-}
chooseOrdering :: Int# -> Ordering
chooseOrdering n = tagToEnum# n
{-# OPAQUE chooseColour #-}
chooseColour :: Int# -> Colour
chooseColour n = tagToEnum# n
{-# OPAQUE chooseExternal #-}
chooseExternal :: Int# -> External
chooseExternal n = tagToEnum# n

boolCase :: Int# -> Int#
boolCase n = case chooseBool n of False -> -11#; True -> 29#
orderingCase :: Int# -> Int#
orderingCase n = case chooseOrdering n of LT -> 71#; EQ -> -23#; GT -> 211#
colourCase :: Int# -> Int#
colourCase n = case chooseColour n of Red -> 17#; Green -> -31#; Blue -> 83#
externalCase :: Int# -> Int#
externalCase n = externalCode (chooseExternal n)

{-# OPAQUE ignoreBool #-}
ignoreBool :: Bool -> Int# -> Int#
ignoreBool _ n = n +# 5#
-- The undefined tag expression remains beneath an unused lifted application.
lazyCase :: Int# -> Int#
lazyCase n = ignoreBool (chooseBool (raise# Red)) n
-- Even tags outside the enum domain are harmless while the lifted result is unused.
lazyTagCase :: Int# -> Int#
lazyTagCase n = ignoreBool (chooseBool n) n
{-# OPAQUE consume #-}
consume :: (Int# -> Colour) -> Int# -> Int#
consume f n = case f n of Red -> 17#; Green -> -31#; Blue -> 83#
{-# OPAQUE prefix #-}
prefix :: Int# -> Int# -> Colour
prefix bias n = chooseColour (remInt# (bias +# n) 3#)
papCase :: Int# -> Int#
papCase n = consume (prefix 3#) n

-- An explicitly threaded State# makes the mutation observable after the enum
-- is consumed; the native compiler may erase a write whose resulting token
-- is discarded. The tuple fields use the existing State-aware result path.
{-# OPAQUE effectfulTag #-}
effectfulTag :: MutVar# s Int -> Int# -> State# s -> (# State# s, Int# #)
effectfulTag ref n s = case readMutVar# ref s of
  (# s1, I# count #) -> case writeMutVar# ref (I# (count +# 1#)) s1 of
    s2 -> (# s2, n #)
onceCase :: Int# -> Int#
onceCase n = runRW# (\s -> case newMutVar# (I# 0#) s of
  (# s1, ref #) -> case effectfulTag ref n s1 of
    (# s2, tag #) -> case chooseBool tag of
      False -> case readMutVar# ref s2 of (# _, I# count #) -> count +# 101#
      True -> case readMutVar# ref s2 of (# _, I# count #) -> count +# 203#)
