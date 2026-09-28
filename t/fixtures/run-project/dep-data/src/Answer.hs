-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE CPP, MagicHash #-}

-- |
-- Module      : Answer
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Module for the @run-project@ integration fixture.
module Answer (answerValue) where
import GHC.Exts (Int(I#))
answerValue :: Int
#ifdef PROJECT_RECENT
answerValue = I# 42#
#else
answerValue = I# 41#
#endif
