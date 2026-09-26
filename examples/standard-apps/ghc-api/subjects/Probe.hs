-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Probe (answer) where

import qualified Data.Map.Strict as Map

answer :: Int
answer = Map.findWithDefault 0 "thc" (Map.singleton "thc" 42)
