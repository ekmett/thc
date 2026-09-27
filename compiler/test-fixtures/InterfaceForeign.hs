-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
{-# LANGUAGE TemplateHaskell #-}

-- |
-- Module      : InterfaceForeign
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC Template Haskell
--
-- Compiler fixture for interface foreign Core and metadata.
module InterfaceForeign where
import Language.Haskell.TH.Syntax (addForeignSource, ForeignSrcLang(LangC))
$(addForeignSource LangC "int thc_interface_c_control(void) { return 29; }\n" >> pure [])
foreign export ccall "thc_interface_fixture" exported :: Int -> Int
exported :: Int -> Int
exported x = x + 1
