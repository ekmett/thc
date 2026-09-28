-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- |
-- Module      : THC.Exception
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; THC runtime services or native fallback implementation
--
-- Handle admitted foreign-language application failures with ordinary Haskell
-- 'Control.Exception.catch', 'Control.Exception.try' and cleanup combinators.
-- Internal runtime faults, cancellation and fatal host errors remain uncatchable
-- through this bridge. Inspection is in IO; pure display is inert.
module THC.Exception
  ( ForeignException, foreignExceptionType, foreignExceptionMessage ) where

import THC.Internal.Exception (ForeignException, exceptionText)

-- | The foreign type name, when the language makes one available.
foreignExceptionType :: ForeignException -> IO (Maybe String)
foreignExceptionType = exceptionText 0

-- | A snapshot of the foreign message. Querying metadata may execute foreign
-- language code. Admitted foreign application failures are converted automatically.
foreignExceptionMessage :: ForeignException -> IO (Maybe String)
foreignExceptionMessage = exceptionText 1
