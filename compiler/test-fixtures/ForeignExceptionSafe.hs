-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}

-- |
-- Module      : ForeignExceptionSafe
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Safe Haskell; public THC exception API
--
-- Compiler fixture for foreign exception safe Core and metadata.
module ForeignExceptionSafe where
import THC.Exception
safeClient :: ForeignException -> IO (Maybe String)
safeClient = foreignExceptionMessage
