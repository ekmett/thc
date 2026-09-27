-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Facts
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; pinned GHC 9.14.1 target facts
--
-- Typed known-start module and target facts. No value is inferred from the
-- producer host; foreign products and exception identities remain semantic.
module THC.Compact.Facts where

import qualified Data.ByteString as BS
import Data.Word (Word64)
import THC.Compact.Core

data Facts = Facts
  { factsSchema :: !Word64
  , factsGhc :: !BS.ByteString
  , factsUnit :: !BS.ByteString
  , factsModule :: !BS.ByteString
  , factsBoundary :: !BS.ByteString
  , factsProvidedModules :: !(Presence [BS.ByteString])
  , factsTargetLayout :: !(Presence TargetLayout)
  , factsConstructors :: ![Constructor]
  , factsForeign :: !(Presence ForeignArtifacts)
  , factsExceptionBridge :: !(Presence ExceptionBridge)
  , factsExceptionBridgeUnit :: !(Presence BS.ByteString)
  , factsPendingProvenance :: ![Presence ()]
  } deriving (Eq, Show)

data Endianness = LittleEndian | BigEndian deriving (Eq, Ord, Enum, Bounded, Show)
data TargetLayout = TargetLayout
  { targetDocumentSchema :: !Word64
  , targetCompilerId :: !BS.ByteString
  , targetCompilerAbi :: !BS.ByteString
  , targetCompilerPlatform :: !BS.ByteString
  , targetCompilerWay :: !BS.ByteString
  , targetLayoutSchema :: !Word64
  , targetProfiled :: !Bool
  , targetWordBytes :: !Word64
  , targetEndianness :: !Endianness
  , targetPlatform :: !BS.ByteString
  , targetTablesNextToCode :: !Bool
  , targetNumbers :: ![Word64]
  } deriving (Eq, Show)

data ForeignArtifacts = ForeignArtifacts !Word64 !BS.ByteString !(Presence Stubs) ![ForeignFile]
  deriving (Eq, Show)
data Stubs = Stubs !BS.ByteString !BS.ByteString ![Label] ![Label] deriving (Eq, Show)
data Label = Label !Bool !BS.ByteString !BS.ByteString !BS.ByteString deriving (Eq, Show)
data ForeignFile = ForeignFile !BS.ByteString !BS.ByteString !BS.ByteString deriving (Eq, Show)
data ExceptionBridge = ExceptionBridge !Word64 !BS.ByteString !BS.ByteString
  !BS.ByteString !BS.ByteString !BS.ByteString !BS.ByteString deriving (Eq, Show)

-- | Header slots whose typed nonempty payloads are not implemented yet.
-- Conversion rejects them, instead of preserving unexamined JSON text or
-- silently treating a present record as absent.
pendingProvenanceNames :: [BS.ByteString]
pendingProvenanceNames =
  [ "foreignLink", "staticForeignImportStubs", "staticForeignImports"
  , "staticForeignExports", "staticForeignExportRegistration", "packageScalarLink"
  , "packageNativeLink", "packageNativeArchive"
  ]

-- | Exact order of numeric fields in the existing GHC target-layout document.
targetNumberNames :: [BS.ByteString]
targetNumberNames =
  [ "infoTableBytes", "infoTablePtrsOffset", "infoTablePtrsBytes"
  , "infoTableNptrsOffset", "infoTableNptrsBytes", "infoTableTypeOffset"
  , "infoTableTypeBytes", "infoTableSrtOffset", "infoTableSrtBytes"
  , "infoProvEntBytes", "infoProvBytes", "infoProvEntInfoOffset"
  , "infoProvEntProvOffset", "infoProvNameOffset", "infoProvDescOffset"
  , "infoProvDescBytes", "infoProvTyDescOffset", "infoProvLabelOffset"
  , "infoProvUnitOffset", "infoProvModuleOffset", "infoProvFileOffset"
  , "infoProvSpanOffset", "closureRetBco", "closureRetSmall"
  , "closureRetBig", "closureRetFun", "closureUpdateFrame"
  , "closureCatchFrame", "closureUnderflowFrame", "closureStopFrame"
  , "closureStack", "closureAtomicallyFrame", "closureCatchRetryFrame"
  , "closureCatchStmFrame", "closureAnnFrame", "stackHeaderBytes"
  , "stackCatchHandlerBytes", "stackCatchFrameBytes", "stackCatchStmCodeBytes"
  , "stackCatchStmHandlerBytes", "stackCatchStmFrameBytes", "stackUpdateeBytes"
  , "stackUpdateFrameBytes", "stackAtomicallyCodeBytes", "stackAtomicallyResultBytes"
  , "stackAtomicallyFrameBytes", "stackCatchRetryAltCodeBytes"
  , "stackCatchRetryFirstCodeBytes", "stackCatchRetryAltBytes"
  , "stackCatchRetryFrameBytes", "stackRetFunSizeBytes", "stackRetFunFunBytes"
  , "stackRetFunPayloadBytes", "stackRetFunFrameBytes", "stackAnnPayloadBytes"
  , "stackAnnFrameBytes", "stackClosurePayloadBytes"
  ]
