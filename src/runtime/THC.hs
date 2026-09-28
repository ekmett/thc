-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}

-- |
-- Module      : THC
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; THC runtime services or native fallback implementation
--
-- Small, stable runtime facade. Detailed services are in @THC.Thread@,
-- @THC.Memory@, @THC.GC@ and @THC.Trace@. Hazardous/internal controls are not
-- re-exported; @THC.Internal.JIT@ must be imported explicitly.
module THC
  ( module THC.Runtime
  , CpuAffinitySupport(..), cpuAffinitySupport, affinityApplied, forkOnWithAffinity
  ) where

import THC.Runtime
import THC.Thread (CpuAffinitySupport(..), cpuAffinitySupport, affinityApplied, forkOnWithAffinity)
