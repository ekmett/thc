-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ImplicitParams #-}

-- | Genuine class selectors: two ordinary dictionaries and one erased unary
-- dictionary. Keep callers polymorphic so GHC retains the selector operations.
module SelectorProofAudit where

class Parent a where
  parentFirst :: a -> Int
  parentSecond :: a -> Int

class Parent a => Child a where
  childMethod :: a -> Int

class Unary a where
  unaryMethod :: a -> Int

{-# OPAQUE method #-}
method :: Child a => a -> Int
method = childMethod

{-# OPAQUE superclass #-}
superclass :: Child a => a -> Int
superclass = parentFirst

{-# OPAQUE unary #-}
unary :: Unary a => a -> Int
unary = unaryMethod

data Payload = First Int | Second Int

{-# OPAQUE implicitRead #-}
implicitRead :: (?payload :: Payload) => Int
implicitRead = case ?payload of
  First n -> n
  Second n -> n + 1

-- The case has a concrete Payload result, but C:IP changes its apparent
-- enclosing type. Export must retain the former without certifying the latter.
{-# OPAQUE implicitSupply #-}
implicitSupply :: Bool -> Int -> Int
implicitSupply choose n =
  let ?payload = case choose of
        False -> First n
        True -> Second n
  in implicitRead
