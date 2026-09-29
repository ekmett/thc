-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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
