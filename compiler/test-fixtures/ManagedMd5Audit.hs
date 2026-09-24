-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Keep the original installed GHC implementation visible to the exporter.
-- This deliberately does not substitute a new Haskell or Java MD5 algorithm.
module ManagedMd5Audit (probe) where

import GHC.Internal.Fingerprint (fingerprintData)

probe = fingerprintData
