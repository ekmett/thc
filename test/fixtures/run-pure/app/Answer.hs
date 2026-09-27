-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Answer
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell with the imported library dependencies
--
-- Module for the @run-pure@ integration fixture.
module Answer (answerValue) where

{-# NOINLINE answerValue #-}
answerValue :: Int
answerValue = 42
