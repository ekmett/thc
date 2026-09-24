-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE Safe #-}
module Answer (answerValue) where

import SafeDependency (stableValue)

answerValue :: Int
answerValue = stableValue
{-# OPAQUE answerValue #-}
