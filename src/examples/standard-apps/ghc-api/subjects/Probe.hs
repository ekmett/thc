-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Probe
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell with containers
--
-- Small Map-using source target for the GHC API load example.
module Probe (answer) where

import qualified Data.Map.Strict as Map

answer :: Int
answer = Map.findWithDefault 0 "thc" (Map.singleton "thc" 42)
