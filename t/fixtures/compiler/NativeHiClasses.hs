-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ConstraintKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE KindSignatures #-}
{-# LANGUAGE MagicHash #-}
{-# OPTIONS_GHC -fno-write-if-simplified-core #-}
-- Input: ordinary GHC class declarations, consumed by NativeHiDependency/Scalar.
-- Purpose: thin libraries still define compiler-derived dictionary constructors
-- and selectors; their missing ordinary bodies must never be invented.
-- Output: one thin .hi/.o pair in the existing native execution build.
module NativeHiClasses (Measure(..), Advance(..), Evidence(..), opaqueIdentity) where

import Data.Kind (Constraint)
import GHC.Exts (Int#)

class Measure a where
  measure :: a -> Int#

class Measure a => Advance a where
  advance :: a -> a

data Evidence (constraint :: Constraint) where
  Evidence :: constraint => Evidence constraint

{-# OPAQUE opaqueIdentity #-}
opaqueIdentity :: a -> a
opaqueIdentity value = value
