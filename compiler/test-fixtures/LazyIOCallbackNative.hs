-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; corresponding compiled fixture module
--
-- Native GHC observer for the lazy io callback fixture.
module Main where

import LazyIOCallbackAudit

main :: IO ()
main = do
  print catchLazyActionHead
  print catchLazyHandlerHead
  print keepAliveScalar
  print keepAliveTuple
