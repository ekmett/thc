-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnliftedDatatypes, StandaloneKindSignatures #-}
module DataToTagAudit where

import GHC.Exts
import GHC.Prim (dataToTagSmall#)

data Small = A | B Int | C (Int -> Int)
data Large = L0 | L1 | L2 | L3 | L4 | L5 | L6 | L7 | L8 Int
type Unlifted :: UnliftedType
data Unlifted = U0 | U1 Int
newtype Wrapped = Wrapped Small

bottomInt :: Int
bottomInt = bottomInt
bottomSmall :: Small
bottomSmall = bottomSmall

{-# OPAQUE smallTag #-}
smallTag :: Small -> Int#
smallTag x = dataToTag# x +# 1#
{-# OPAQUE largeTag #-}
largeTag :: Large -> Int#
largeTag x = dataToTag# x +# 1#
{-# OPAQUE unliftedTag #-}
unliftedTag :: Unlifted -> Int#
unliftedTag x = dataToTag# x +# 1#
{-# OPAQUE pairTag #-}
pairTag :: (Int, Int) -> Int#
pairTag x = dataToTagSmall# x +# 1#

{-# OPAQUE pickSmall #-}
pickSmall :: Int# -> Small
pickSmall x = case remInt# x 3# of
  0# -> A
  1# -> B bottomInt
  _ -> C (\y -> y)
{-# OPAQUE pickLarge #-}
pickLarge :: Int# -> Large
pickLarge x = case remInt# (andI# x 15#) 9# of
  0# -> L0
  1# -> L1
  2# -> L2
  3# -> L3
  4# -> L4
  5# -> L5
  6# -> L6
  7# -> L7
  _ -> L8 bottomInt

smallCase, largeCase, lazyCase, pairCase, unliftedCase, forceCase, wrappedCase :: Int# -> Int#
smallCase x = smallTag (pickSmall x) +# x
largeCase x = largeTag (pickLarge x) +# x
lazyCase x = smallTag (B bottomInt) +# x
pairCase x = pairTag (I# x, bottomInt) +# x
unliftedCase x = case andI# x 1# of
  0# -> unliftedTag U0 +# x
  _ -> unliftedTag (U1 bottomInt) +# x
forceCase x = smallTag (forceChoice x) +# x
wrappedCase x = unwrapTag (Wrapped (pickSmall x)) +# x

{-# OPAQUE unwrapTag #-}
unwrapTag :: Wrapped -> Int#
unwrapTag (Wrapped x) = dataToTag# x +# 1#

{-# OPAQUE forceChoice #-}
forceChoice :: Int# -> Small
forceChoice x = case x <# 0# of 1# -> bottomSmall; _ -> A

-- These raw internal calls are export-only negative frontiers. They are never
-- run by the native oracle: the public DataToTag solver would refuse them.
{-# OPAQUE unknownFamily #-}
unknownFamily :: a -> Int#
unknownFamily x = dataToTagSmall# x +# 1#
{-# OPAQUE closureFamily #-}
closureFamily :: (Int -> Int) -> Int#
closureFamily x = dataToTagSmall# x +# 1#
{-# OPAQUE newtypeFamily #-}
newtypeFamily :: Wrapped -> Int#
newtypeFamily x = dataToTagSmall# x +# 1#
{-# OPAQUE wrongSmallFamily #-}
wrongSmallFamily :: Large -> Int#
wrongSmallFamily x = dataToTagSmall# x +# 1#

{-# OPAQUE barePrimitive #-}
barePrimitive :: Small -> Int#
barePrimitive = dataToTagSmall#
