-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GHCForeignImportPrim, MagicHash, UnliftedFFITypes #-}

-- Negative producer control, never linked or executed. A concrete boxed rep
-- alone does not identify a supported primitive nominal carrier.
module PackageNativeUnknownPrim where

import GHC.Exts

foreign import prim "negative_bytearray_prim"
  unsupportedArray :: ByteArray# -> Word#
