-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude, GADTs #-}

-- |
-- Module      : ConstructorFieldAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Constructor worker types are not all Object, but lazy fields may hold thunks.
module ConstructorFieldAudit where
import GHC.Exts (Int#)

data Payload = End | Payload Int#
newtype Wrapped = Wrapped Payload

data Record a = Record !Payload Payload !a a !(Int# -> Int#) (Int# -> Int#)
data Primitive = Primitive Int#
data NewtypeField = NewtypeField Wrapped
data Evidence a where
  Evidence :: Payload -> Evidence Payload
