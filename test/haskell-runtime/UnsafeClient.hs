-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}
-- Deliberate negative compilation test: Internal is both hazardous and unstable.
module UnsafeClient (unsafeClient) where
import THC.Internal.JIT (JitSnapshot, jitSnapshot)
unsafeClient :: IO JitSnapshot
unsafeClient = jitSnapshot
