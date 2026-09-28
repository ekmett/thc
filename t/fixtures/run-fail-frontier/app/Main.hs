-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE ScopedTypeVariables #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC scoped exception handlers; base IO
--
-- Executable for the @run-fail-frontier@ integration fixture.
module Main where

import Control.Exception (catch, IOException)

main :: IO ()
main = catch (fail "boom") (\(_ :: IOException) -> pure ())
