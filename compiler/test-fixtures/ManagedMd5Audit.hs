-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : ManagedMd5Audit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 internal library APIs
--
-- Keep the original installed GHC implementation visible to the exporter.
-- This deliberately does not substitute a new Haskell or Java MD5 algorithm.
module ManagedMd5Audit (probe) where

import GHC.Internal.Fingerprint (fingerprintData)

probe = fingerprintData
