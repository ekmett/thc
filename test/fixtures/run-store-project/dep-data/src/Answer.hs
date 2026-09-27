-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE Safe #-}

-- |
-- Module      : Answer
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC with the declared language extensions
--
-- Module for the @run-store-project@ integration fixture.
module Answer (answerValue) where

import SafeDependency (stableValue)

answerValue :: Int
answerValue = stableValue
{-# OPAQUE answerValue #-}
