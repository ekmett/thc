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
import THC.Compact.Types (RecoveryFacts)

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
  , factsPendingProvenance :: ![Presence ModuleProvenance]
  , factsClosureProvenance :: !(Maybe ClosureProvenance)
  , factsBackendPolicy :: !(Maybe BackendPolicy)
  , factsRecovery :: !(Maybe RecoveryFacts)
  } deriving (Eq, Show)

-- | Root selection only; GHC workers and inlined copies do not inherit overrides.
data Backend = AstBackend | BytecodeBackend deriving (Eq, Ord, Show)
data BackendPolicy = BackendPolicy !(Maybe Backend) ![(BS.ByteString,Backend)]
  deriving (Eq, Show)

-- | Actual interface frontier and binding origins used by source acquisition
-- and provenance audits. These are facts, not executable bodies or display maps.
data ClosureProvenance = ClosureProvenance !(Presence [BS.ByteString])
  !(Presence [BS.ByteString]) !(Presence [MissingDefinition]) ![BindingOrigin]
  deriving (Eq, Show)
data MissingDefinition = MissingDefinition !BS.ByteString !BS.ByteString !BS.ByteString
  deriving (Eq, Show)
data BindingOrigin = BindingOrigin !BS.ByteString !(Presence BS.ByteString) !(Presence BS.ByteString)
  deriving (Eq, Show)

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

data ModuleProvenance = ImportsRecord !ImportProof | ExportsRecord !Exports
  | RegistrationRecord !Registration | ForeignLinkRecord !ForeignLink
  | ScalarLinkRecord !ScalarLink | NativeLinkRecord !NativeLink
  | NativeArchiveRecord !NativeArchive deriving (Eq, Show)
data ForeignLink = ForeignLink !Word64 !BS.ByteString !BS.ByteString !BS.ByteString
  !BS.ByteString !BS.ByteString !BS.ByteString !BS.ByteString ![BS.ByteString]
  ![(BS.ByteString,BS.ByteString)] !(Presence [(BS.ByteString,BS.ByteString)])
  deriving (Eq, Show)
data LinkPayload = LinkPayload !Word64 !BS.ByteString !BS.ByteString !BS.ByteString
  !BS.ByteString !BS.ByteString !BS.ByteString !BS.ByteString deriving (Eq, Show)
data ScalarABI = ScalarABI !BS.ByteString !BS.ByteString ![BS.ByteString] !BS.ByteString
  deriving (Eq, Show)
data ScalarLink = ScalarLink !LinkPayload ![ScalarABI] deriving (Eq, Show)
data NativeABI = NativeABI !BS.ByteString !BS.ByteString !Convention !Safety
  ![BS.ByteString] !BS.ByteString deriving (Eq, Show)
data NativeLink = NativeLink !LinkPayload ![NativeABI] !(Presence NativeBuildInputs)
  !(Presence (BS.ByteString,BS.ByteString)) !(Presence [BS.ByteString]) ![BS.ByteString]
  !(Maybe ([BS.ByteString],[NativeComponent])) !(Maybe [NativeCallSeed]) deriving (Eq, Show)
data NativeCallSeed = NativeCallSeed !BS.ByteString !BS.ByteString !BS.ByteString
  !(Maybe (BS.ByteString,BS.ByteString,BS.ByteString)) deriving (Eq, Show)
data NativeComponent = NativeComponent !LinkPayload ![BS.ByteString] ![NativeComponent]
  !(Presence (BS.ByteString,BS.ByteString)) deriving (Eq, Show)
data NativeBuildInputs = NativeBuildInputs ![CompileGroup] ![NativeProvider]
  !NativeBuildDependencies ![NativeLibrary] ![BS.ByteString] ![ArgumentBridge]
  deriving (Eq, Show)
data NativeBuildDependencies = ArchiveBuildDependencies !(Presence [NativeDependency])
  | ComponentBuildDependencies !(Presence [NativeDependencyRef]) !(Presence NativeDependency)
  deriving (Eq, Show)
data NativeDependencyRef = NativeDependencyRef ![BS.ByteString] !BS.ByteString !BS.ByteString !BS.ByteString
  deriving (Eq, Show)
data CompileGroup = SingleCompile !CompileInput | GroupCompile ![CompileInput] deriving (Eq, Show)
data CompileInput = CompileInput !BS.ByteString !BS.ByteString ![BS.ByteString]
  !(Presence BS.ByteString) !BS.ByteString !BS.ByteString ![(BS.ByteString,BS.ByteString)]
  deriving (Eq, Show)
data NativeProvider = NativeProvider !BS.ByteString ![BS.ByteString] !BS.ByteString
  !BS.ByteString !BS.ByteString !CompileInput deriving (Eq, Show)
data NativeLibrary = NativeLibrary !BS.ByteString ![BS.ByteString] !BS.ByteString
  !BS.ByteString ![BS.ByteString] !(Presence [BS.ByteString]) !(Presence BS.ByteString)
  !(Presence BS.ByteString) !(Presence [[BS.ByteString]]) deriving (Eq, Show)
data ArgumentBridge = ArgumentBridge !BS.ByteString !BS.ByteString !BS.ByteString
  !BS.ByteString ![[BS.ByteString]] deriving (Eq, Show)
data NativeDependency = NativeDependency !BS.ByteString !BS.ByteString !SourceIdentity
  !BS.ByteString !BS.ByteString ![ArchiveProduct] ![NativeProduct] deriving (Eq, Show)
data SourceIdentity = SourceIdentity !(Presence BS.ByteString) !(Presence [BS.ByteString])
  !(Presence BS.ByteString) !(Presence BS.ByteString) !(Presence BS.ByteString)
  !(Presence BS.ByteString) !(Presence [(BS.ByteString,Bool)]) !(Presence BS.ByteString)
  !(Presence BS.ByteString) !(Presence BS.ByteString) !(Presence NativeSource) deriving (Eq, Show)
data NativeSource = NativeSource !BS.ByteString !(Presence BS.ByteString)
  !(Presence (BS.ByteString,BS.ByteString)) deriving (Eq, Show)
data ArchiveProduct = ArchiveProduct !BS.ByteString !BS.ByteString ![(BS.ByteString,BS.ByteString)] deriving (Eq, Show)
data NativeProduct = NativeProduct !NativePiece !BS.ByteString deriving (Eq, Show)
data NativePiece = NativePiece !BS.ByteString !BS.ByteString !BS.ByteString
  !BS.ByteString !BS.ByteString !CompileInput deriving (Eq, Show)
data NativeArchive = NativeArchive !Word64 !BS.ByteString !BS.ByteString !BS.ByteString !BS.ByteString
  ![EmittedCall] !(Presence BS.ByteString) ![BS.ByteString] !(Presence NativeLink)
  !(Presence [EmittedCall]) deriving (Eq, Show)
data QualifiedName = QualifiedName !BS.ByteString !BS.ByteString !BS.ByteString !BS.ByteString
  deriving (Eq, Show)
data ForeignType = ForeignTyCon !QualifiedName ![ForeignType]
  | ForeignApplication !ForeignType !ForeignType
  | ForeignArrow !ForeignType !ForeignType !ForeignType
  | ForeignVariable !Word64 | ForeignForall !ForeignType !ForeignType
  deriving (Eq, Show)
data EmittedCall = EmittedCall !BS.ByteString !(Presence BS.ByteString) !Convention !Safety
  ![BS.ByteString] ![BS.ByteString] deriving (Eq, Show)
data ImportAssociation = ImportAssociation !QualifiedName !(Presence BS.ByteString)
  !BS.ByteString !(Presence BS.ByteString) !Bool !Convention !Safety !ForeignType !ForeignType
  !BS.ByteString !EmittedCall deriving (Eq, Show)
data ImportStatus = ImportsUnclassified !BS.ByteString | ImportsRejected !BS.ByteString
  | ImportsVerified !Word64 !ForeignArtifacts ![ImportAssociation] ![ForeignCall] ![AddressAssociation] ![WrapperAssociation] !(Maybe ForeignArtifacts)
  deriving (Eq, Show)
data AddressAssociation = AddressAssociation !QualifiedName !(Presence BS.ByteString)
  !BS.ByteString !Bool !Convention !ForeignType !ForeignType !BS.ByteString
  !(Maybe ([BS.ByteString],BS.ByteString)) deriving (Eq, Show)
data ImportProof = ImportProof !Word64 !BS.ByteString !BS.ByteString !BS.ByteString
  !BS.ByteString !BS.ByteString !ImportStatus deriving (Eq, Show)
data ExportEffect = PureExport | IOExport deriving (Eq, Ord, Enum, Bounded, Show)
data ExportAssociation = ExportAssociation !QualifiedName !BS.ByteString !Convention
  !ForeignType !ForeignType !BS.ByteString ![ForeignType] !ForeignType !ExportEffect
  deriving (Eq, Show)
data WrapperAssociation = WrapperAssociation !ExportAssociation !BS.ByteString deriving (Eq, Show)
data Exports = Exports !Word64 !BS.ByteString !BS.ByteString !BS.ByteString
  !BS.ByteString !BS.ByteString ![ExportAssociation] deriving (Eq, Show)
data RegistrationStatus = RegistrationUnclassified !BS.ByteString | RegistrationRejected !BS.ByteString
  | RegistrationVerified ![QualifiedName] !Word64 !ForeignArtifacts !Exports deriving (Eq, Show)
data Registration = Registration !Word64 !BS.ByteString !BS.ByteString !BS.ByteString
  !RegistrationStatus deriving (Eq, Show)

-- | Fixed typed-header provenance slot order. Known payloads without a typed
-- variant still reject; absent and null remain distinct for every slot.
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

-- | The numeric vector is unframed: its schema fixes the exact field count.
targetNumberNamesFor :: Word64 -> Either String [BS.ByteString]
targetNumberNamesFor schema = case schema of
  1 -> Right targetNumberNames
  2 -> Right (targetNumberNames ++
    ["rtsFlagsBytes", "traceFlagsBytes", "rtsTraceFlagsOffset", "rtsTraceFlagsBytes", "traceUserOffset", "traceUserBytes"])
  _ -> Left "Unsupported GHC target layout schema"
