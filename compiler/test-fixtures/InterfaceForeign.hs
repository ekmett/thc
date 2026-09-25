-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module InterfaceForeign where
foreign export ccall "thc_interface_fixture" exported :: Int -> Int
exported :: Int -> Int
exported x = x + 1
