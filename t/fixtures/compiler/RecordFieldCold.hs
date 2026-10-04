-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | Retained cold Core with a genuine GHC-generated strict constructor wrapper.
module RecordFieldCold (Strict(..), cold) where

import GHC.Exts (Int(..), Int#)
import RecordFieldClient (fieldAlias)

data Strict = Strict !Int

{-# OPAQUE mkStrict #-}
mkStrict :: Int# -> Strict
mkStrict n = Strict (I# n)

{-# OPAQUE cold #-}
cold :: Int# -> Int#
cold n = case mkStrict n of Strict (I# value) -> fieldAlias value
