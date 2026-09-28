-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}

-- |
-- Module      : ForeignExceptionUnsafeImport
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Safe Haskell rejection fixture for unsafe THC internals
--
-- Compiler fixture for foreign exception unsafe import Core and metadata.
module ForeignExceptionUnsafeImport where
import THC.Internal.Exception
