-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- | Read-only runtime identity and permissions. The trusted boundary consists
-- only of fixed, validated selectors; no raw foreign values escape this module.
module THC.Runtime
  ( Availability(..), RuntimeKind(..), Backend(..), RuntimeInfo(..)
  , RuntimeCapabilities(..), runtimeInfo, runtimeCapabilities
  ) where

import Data.Version (showVersion)
import qualified System.Info as System
import THC.Internal.RuntimeABI

data RuntimeKind = NativeGHC | TruffleHaskell deriving (Eq, Ord, Show)
data Backend = NativeBackend | ASTBackend | BytecodeBackend deriving (Eq, Ord, Show)

-- | Version strings are informational, not feature-detection interfaces.
data RuntimeInfo = RuntimeInfo
  { runtimeKind :: Availability RuntimeKind
  , executingBackend :: Availability Backend
  , runtimeVersion :: Availability String
  , jvmVersion :: Availability String
  , jvmName :: Availability String
  } deriving (Eq, Show)

-- | Permissions are for the current THC context, not the entire JVM. CPU
-- capacity is the context's initial eligible capacity, not a thread count.
data RuntimeCapabilities = RuntimeCapabilities
  { nativeAccessPermitted :: Availability Bool
  , threadCreationPermitted :: Availability Bool
  , cpuCapacity :: Availability Int
  } deriving (Eq, Show)

runtimeInfo :: IO RuntimeInfo
runtimeInfo = do
  kind <- queryEnum 0 [(0, NativeGHC), (1, TruffleHaskell)]
  backend <- queryEnum 1 [(0, NativeBackend), (1, ASTBackend), (2, BytecodeBackend)]
  version <- case kind of
    Available NativeGHC -> pure (Available (System.compilerName ++ "-" ++ showVersion System.compilerVersion))
    _ -> queryText 5 0
  RuntimeInfo kind backend version <$> queryText 6 0 <*> queryText 7 0

runtimeCapabilities :: IO RuntimeCapabilities
runtimeCapabilities = RuntimeCapabilities
  <$> queryEnum 2 [(0, False), (1, True)]
  <*> queryEnum 3 [(0, False), (1, True)]
  <*> queryInt 4 0 0
