-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
{-# LANGUAGE ForeignFunctionInterface #-}

-- |
-- Module      : InterfaceForeignAlias
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC with the declared language extensions
--
-- Compiler fixture for interface foreign alias Core and metadata.
module InterfaceForeignAlias (callback) where

import Foreign.StablePtr (StablePtr, deRefStablePtr)

-- Compile the same source and module twice, changing only this external name.
foreign export ccall THC_FOREIGN_C_LABEL callback :: StablePtr (IO ()) -> IO ()

{-# NOINLINE callback #-}
callback :: StablePtr (IO ()) -> IO ()
callback stable = deRefStablePtr stable >>= id
