-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- |
-- Module      : THC.Runtime
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; THC runtime services or native fallback implementation
--
-- Read-only runtime identity and permissions. The trusted boundary consists
-- only of fixed, validated selectors; no raw foreign values escape this module.
module THC.Runtime
  ( Available(..), RuntimeKind(..), Backend(..), RuntimeInfo(..)
  , RuntimeCapabilities(..), runtimeInfo, runtimeCapabilities
  ) where

import Data.Version (showVersion)
import qualified System.Info as System
import THC.Internal.RuntimeABI

-- | Runtime supplying the service ABI, independently of its selected backend.
data RuntimeKind = NativeGHC | TruffleHaskell deriving (Eq, Ord, Show)
-- | Execution backend reported by the current runtime context.
data Backend = NativeBackend | ASTBackend | BytecodeBackend deriving (Eq, Ord, Show)

-- | Version strings are informational, not feature-detection interfaces.
data RuntimeInfo = RuntimeInfo
  { runtimeKind :: Available RuntimeKind
  , executingBackend :: Available Backend
  , runtimeVersion :: Available String
  , jvmVersion :: Available String
  , jvmName :: Available String
  } deriving (Eq, Show)

-- | Permissions are for the current THC context, not the entire JVM. CPU
-- capacity is the context's initial eligible capacity, not a thread count.
data RuntimeCapabilities = RuntimeCapabilities
  { nativeAccessPermitted :: Available Bool
  , threadCreationPermitted :: Available Bool
  , cpuCapacity :: Available Int
  } deriving (Eq, Show)

-- | Query runtime identity and informational version strings. Each field keeps
-- its own @Available@ status; native GHC has no JVM identity to report.
--
-- A client can inspect one field without assuming the others are available:
--
-- > info <- runtimeInfo
-- > case runtimeKind info of
-- >   Available kind -> print kind
-- >   status -> print status
runtimeInfo :: IO RuntimeInfo
runtimeInfo = do
  kind <- queryEnum 0 [(0, NativeGHC), (1, TruffleHaskell)]
  backend <- queryEnum 1 [(0, NativeBackend), (1, ASTBackend), (2, BytecodeBackend)]
  version <- case kind of
    Available NativeGHC -> pure (Available (System.compilerName ++ "-" ++ showVersion System.compilerVersion))
    _ -> queryText 5 0
  RuntimeInfo kind backend version <$> queryText 6 0 <*> queryText 7 0

-- | Query context permissions and initial CPU capacity without changing them.
-- Permission to use a service is distinct from the service being available.
runtimeCapabilities :: IO RuntimeCapabilities
runtimeCapabilities = RuntimeCapabilities
  <$> queryEnum 2 [(0, False), (1, True)]
  <*> queryEnum 3 [(0, False), (1, True)]
  <*> queryInt 4 0 0
