-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Native GHC infers Safe here; capture must preserve the inferred property.
module SafeDependency (stableValue) where

stableValue :: Int
stableValue = 42
