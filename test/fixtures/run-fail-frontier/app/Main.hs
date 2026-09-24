-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE ScopedTypeVariables #-}
module Main where

import Control.Exception (catch, IOException)

main :: IO ()
main = catch (fail "boom") (\(_ :: IOException) -> pure ())
