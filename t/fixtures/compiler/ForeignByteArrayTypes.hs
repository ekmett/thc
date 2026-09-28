-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedFFITypes #-}

-- | Genuine FCall type identities whose runtime representation loses mutability.
module ForeignByteArrayTypes where

import GHC.Exts

foreign import ccall unsafe "thc_array_types"
  arrayTypes :: ByteArray# -> MutableByteArray# RealWorld -> Int# -> IO Int

foreign import ccall unsafe "thc_scalar_types"
  scalarTypes :: Int# -> IO Int
