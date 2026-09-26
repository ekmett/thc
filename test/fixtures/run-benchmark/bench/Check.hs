-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Check (answer) where
import qualified Answer

{-# NOINLINE answer #-}
answer :: Int
answer = Answer.answer
