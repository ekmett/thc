-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
{-# LANGUAGE ForeignFunctionInterface #-}
module InterfaceForeignAlias (callback) where

import Foreign.StablePtr (StablePtr, deRefStablePtr)

-- Compile the same source and module twice, changing only this external name.
foreign export ccall THC_FOREIGN_C_LABEL callback :: StablePtr (IO ()) -> IO ()

{-# NOINLINE callback #-}
callback :: StablePtr (IO ()) -> IO ()
callback stable = deRefStablePtr stable >>= id
