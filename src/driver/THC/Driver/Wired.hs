-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE CPP #-}
{-# LANGUAGE TemplateHaskell #-}

-- |
-- Module      : THC.Driver.Wired
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Host filesystem/process services and the configured native toolchain
--
-- Produce wired-module and source artifacts used by project Core acquisition.
module THC.Driver.Wired
  ( WiredArtifacts(..), bootSources, moduleSources, sourceHashes, pinnedSourcePath
  , exportPinnedCore, exportPinnedWindowsCore, probeTargetLayout, preparePinnedInterfaces, pinnedDependencyOrder, pinnedRecipeIdentity ) where

import Control.Monad (filterM, foldM, forM, forM_, unless, when)
import Control.Exception (bracket, finally)
import qualified Crypto.Hash.SHA256 as SHA
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Aeson (Value(..), encode, eitherDecodeStrict', object, (.=))
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import Data.List (isInfixOf, isPrefixOf, isSuffixOf, nub, sort, stripPrefix)
import Data.Graph (SCC(..), stronglyConnComp)
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Distribution.InstalledPackageInfo as Package
import System.Directory (copyFile, createDirectoryIfMissing, createFileLink, doesDirectoryExist, doesFileExist,
                         findExecutable, listDirectory, removeFile, createDirectory, withCurrentDirectory, renameFile, renameDirectory)
import System.Exit (ExitCode(..))
import System.Environment (lookupEnv)
import qualified System.Info as Host
import System.FilePath ((</>), makeRelative, replaceExtension, takeDirectory, takeExtension, takeFileName)
import System.Process (CreateProcess(..), StdStream(UseHandle), proc, readProcess)
import THC.Driver.Process (runProducer)
import System.IO (hClose, openTempFile, stderr, stdout)
import GHC.IO.Handle (hDuplicate, hDuplicateTo)
import Text.Read (readEither)
import THC.Driver.Installed (InstalledContext(..), InstalledUnit(..), InterfaceWay(..),
  boundedInterfaceProcess, packageGlobalArguments, installedProvenance, installedViewIdentity)
import THC.Compact.Module (readModuleMetadata)
import THC.Driver.InstalledForeign (createView, viewContext)
import THC.Driver.PinnedFlags (pinnedLibraryFlags, pinnedConfigureOptions, pinnedPluginOptions)
import THC.Driver.PinnedSetup (configurePinnedCustom)
import THC.Driver.Lock (withLock)
import Distribution.Package (pkgName, pkgVersion)
import qualified Distribution.PackageDescription as PD
import Distribution.Simple.PackageDescription (readGenericPackageDescription)
import Distribution.Pretty (prettyShow)
import Distribution.Simple (defaultMainArgs, defaultMainWithSetupHooksArgs, autoconfSetupHooks)
import Distribution.Simple.Configure (getPersistBuildConfig)
import Distribution.Simple.LocalBuildInfo (localPkgDescr, compiler, hostPlatform,
  allComponentsInBuildOrder, componentUnitId, componentPackageDeps, componentBuildDir, allLibModules)
import Distribution.Simple.Build (writeBuiltinAutogenFiles)
import Distribution.Simple.PreProcess (preprocessComponent, knownSuffixHandlers)
import Distribution.Simple.GHC (componentGhcOptions)
import Distribution.Simple.Program.GHC (renderGhcOptions, GhcOptions(..), GhcMode(..), GhcDynLinkMode(..))
import Distribution.Simple.Setup (toFlag)
import Distribution.Types.Component (Component(..))
import Distribution.Utils.NubList (toNubListR)
import Distribution.Utils.Path (getSymbolicPath, makeSymbolicPath)
import Distribution.Verbosity (normal, silent)
import Numeric (showHex)
import Language.Haskell.TH.Syntax (addDependentFile, lift, loc_filename, location, runIO)


-- These are the original GHC 9.14.1 sources. Boot interfaces are compiled
-- first; the installed ghc-internal dynamic interfaces fill the remaining
-- dependencies without changing the installed package database.
bootSources :: [FilePath]
bootSources =
  [ "GHC/Internal/Num.hs-boot"
  , "GHC/Internal/Enum.hs-boot"
  , "GHC/Internal/Real.hs-boot"
  , "GHC/Internal/Fingerprint.hs-boot"
  , "GHC/Internal/Exception/Type.hs-boot"
  , "GHC/Internal/Stack.hs-boot"
  , "GHC/Internal/IO/Handle/Types.hs-boot"
  , "GHC/Internal/IO/Exception.hs-boot"
  , "GHC/Internal/IO.hs-boot"
  , "GHC/Internal/Exception/Backtrace.hs-boot"
  , "GHC/Internal/Exception.hs-boot"
  , "GHC/Internal/Bignum/BigNat.hs-boot"
  , "GHC/Internal/Bignum/Natural.hs-boot"
  , "GHC/Internal/Bignum/Integer.hs-boot"
  ]

-- The order is the proven private-overlay compile order. Only these modules
-- are put into the immutable ZIP; generated Core is never checked into Git.
moduleSources :: [(FilePath, String)]
moduleSources =
  [ ("GHC/Internal/Data/Typeable/Internal.hs", "GHC.Internal.Data.Typeable.Internal")
  , ("GHC/Internal/Types.hs", "GHC.Internal.Types")
  , ("GHC/Internal/Classes.hs", "GHC.Internal.Classes")
  , ("GHC/Internal/Fingerprint.hs", "GHC.Internal.Fingerprint")
  , ("GHC/Internal/Arr.hs", "GHC.Internal.Arr")
  , ("GHC/Internal/Ix.hs", "GHC.Internal.Ix")
  , ("GHC/Internal/List.hs", "GHC.Internal.List")
  , ("GHC/Internal/Data/Tuple.hs", "GHC.Internal.Data.Tuple")
  , ("GHC/Internal/Foreign/Storable.hs", "GHC.Internal.Foreign.Storable")
  , ("GHC/Internal/Foreign/Marshal/Alloc.hs", "GHC.Internal.Foreign.Marshal.Alloc")
  , ("GHC/Internal/Bignum/Natural.hs", "GHC.Internal.Bignum.Natural")
  , ("GHC/Internal/Bignum/Integer.hs", "GHC.Internal.Bignum.Integer")
  , ("GHC/Internal/Base.hs", "GHC.Internal.Base")
  , ("GHC/Internal/Num.hs", "GHC.Internal.Num")
  , ("GHC/Internal/IO/Unsafe.hs", "GHC.Internal.IO.Unsafe")
  , ("GHC/Internal/Show.hs", "GHC.Internal.Show")
  , ("GHC/Internal/Err.hs", "GHC.Internal.Err")
  , ("GHC/Internal/Enum.hs", "GHC.Internal.Enum")
  , ("GHC/Internal/Real.hs", "GHC.Internal.Real")
  , ("GHC/Internal/Numeric.hs", "GHC.Internal.Numeric")
  , ("GHC/Internal/Ptr.hs", "GHC.Internal.Ptr")
  , ("GHC/Internal/Data/Either.hs", "GHC.Internal.Data.Either")
  , ("GHC/Internal/Word.hs", "GHC.Internal.Word")
  , ("GHC/Internal/ClosureTypes.hs", "GHC.Internal.ClosureTypes")
  , ("GHC/Internal/Heap/Constants.hsc", "GHC.Internal.Heap.Constants")
  , ("GHC/Internal/Heap/InfoTable/Types.hsc", "GHC.Internal.Heap.InfoTable.Types")
  , ("GHC/Internal/Heap/InfoTable.hsc", "GHC.Internal.Heap.InfoTable")
  , ("GHC/Internal/Heap/Closures.hs", "GHC.Internal.Heap.Closures")
  , ("GHC/Internal/Stack/Constants.hsc", "GHC.Internal.Stack.Constants")
  , ("GHC/Internal/Stack/Annotation.hs", "GHC.Internal.Stack.Annotation")
  , ("GHC/Internal/Stack/CloneStack.hs", "GHC.Internal.Stack.CloneStack")
  , ("GHC/Internal/ForeignPtr.hs", "GHC.Internal.ForeignPtr")
  , ("GHC/Internal/IO/Encoding/Types.hs", "GHC.Internal.IO.Encoding.Types")
  , ("GHC/Internal/IO/Encoding/Failure.hs", "GHC.Internal.IO.Encoding.Failure")
  , ("GHC/Internal/Foreign/C/String/Encoding.hs", "GHC.Internal.Foreign.C.String.Encoding")
  , ("GHC/Internal/IO/Encoding/UTF8.hs", "GHC.Internal.IO.Encoding.UTF8")
  , ("GHC/Internal/InfoProv/Types.hsc", "GHC.Internal.InfoProv.Types")
  , ("GHC/Internal/Data/Maybe.hs", "GHC.Internal.Data.Maybe")
  , ("GHC/Internal/Data/OldList.hs", "GHC.Internal.Data.OldList")
  , ("GHC/Internal/Stack/Types.hs", "GHC.Internal.Stack.Types")
  , ("GHC/Internal/Stack/CCS.hsc", "GHC.Internal.Stack.CCS")
  , ("GHC/Internal/ExecutionStack/Internal.hsc", "GHC.Internal.ExecutionStack.Internal")
  , ("GHC/Internal/Stack/Decode.hs", "GHC.Internal.Stack.Decode")
  , ("GHC/Internal/Exception/Type.hs", "GHC.Internal.Exception.Type")
  , ("GHC/Internal/IO/Exception.hs", "GHC.Internal.IO.Exception")
  , ("GHC/Internal/IO.hs", "GHC.Internal.IO")
  , ("GHC/Internal/Control/Monad/Fail.hs", "GHC.Internal.Control.Monad.Fail")
  , ("GHC/Internal/Exception/Context.hs", "GHC.Internal.Exception.Context")
  , ("GHC/Internal/Exception.hs", "GHC.Internal.Exception")
  , ("GHC/Internal/Stack.hs", "GHC.Internal.Stack")
  , ("GHC/Internal/IO/Handle/Types.hs", "GHC.Internal.IO.Handle.Types")
  , ("GHC/Internal/IO/Encoding.hs", "GHC.Internal.IO.Encoding")
  , ("GHC/Internal/Exception/Backtrace.hs", "GHC.Internal.Exception.Backtrace")
  , ("GHC/Internal/CString.hs", "GHC.Internal.CString")
  ]

-- Exact upstream SHA-256 pins, including the unmodified upstream license.
sourceHashes :: [(FilePath, String)]
sourceHashes =
  [ ("GHC/Internal/Arr.hs", "d91e8d645309242c5a7b849d6d14884816cd4b04f4fc741e21f62d75a4d2bc4d")
  , ("GHC/Internal/Base.hs", "bc38ea9356f90aeb38298ef1269fbdc2dee433528374948375da112b273a89e2")
  , ("GHC/Internal/Bignum/BigNat.hs-boot", "230a6ac303323e0d0716a39eef450af81bc81494a72192cbe9d20e74f5af45d4")
  , ("GHC/Internal/Bignum/Integer.hs", "1f8ec2a8e12ecbab7eb0b59f249fae663d8066f2fb14224177a4538653bb17f4")
  , ("GHC/Internal/Bignum/Integer.hs-boot", "f486bbc9637cbcc4b03ea5dcaab9dba71286cadd4a29df3e4f68d2d098eee779")
  , ("GHC/Internal/Bignum/Natural.hs", "6895337089fc3ab8a5102b0853c28a4b70281b49ba7705b00eb9750a7e13d8df")
  , ("GHC/Internal/Bignum/Natural.hs-boot", "2e7bb92e28f5fa9601b6874b449cfd75bad66a3144bfddb4a445996eaa991026")
  , ("GHC/Internal/ClosureTypes.hs", "38ca5c8e07a5b807efdce7ac283f42f281347f36195b447067c71dd3c59647ca")
  , ("GHC/Internal/Classes.hs", "9f07eab1df0c2c9892ba5b8e35236e45868f738cb6a755234a7c49e70fb150cd")
  , ("GHC/Internal/CString.hs", "3b2e7a0fb2880d8f98cb002adfbaa36a8469667b7494f8f695fe1a6f181de573")
  , ("GHC/Internal/Control/Monad/Fail.hs", "695c8290891399db66075b86ecb1fd0a6242788985023d3edfb9c7155b97ecb7")
  , ("GHC/Internal/Data/Either.hs", "cbac81710a7e01a8a43616d8d88f9a387d6b0e1bbe5f4410dc1f172dc5f765e6")
  , ("GHC/Internal/Data/Maybe.hs", "c1e3833e2becd8d0ac4ee9ccb9e7615cb7f469c25cf9cfde500e21b46f586057")
  , ("GHC/Internal/Data/OldList.hs", "4b519356f7bcbd705866551e26552c84b08d527fe93b060cc89b27882aec6248")
  , ("GHC/Internal/Data/Typeable/Internal.hs", "ff1a002f75f349dbc4699273c3ce6e8e4dba9841aa5ad4aee20d3beb8d8b54e8")
  , ("GHC/Internal/Data/Tuple.hs", "97658f1c5b9be7910c23480641fb8f1aedbdbe96d0e2d68b5c6106aa48e7a7ad")
  , ("GHC/Internal/Enum.hs", "e4dcf86915b01dcc732ed319fe02759858aea1534c68427826ba5f9c6908860f")
  , ("GHC/Internal/Enum.hs-boot", "47353434d99287294958f62ae98303fa3cf6775dc416f95055bd41a2f95a8449")
  , ("GHC/Internal/Err.hs", "f109ac925928a0e7fed063bcfd03c93e3d8629d8054d984e600aab26c0122478")
  , ("GHC/Internal/ExecutionStack/Internal.hsc", "1ef77fe1313327ecb505c3d600e8324610bf31fc3de11266f767abe5da57442f")
  , ("GHC/Internal/Exception/Backtrace.hs", "2f062109b7a7b40db88bffaef4c9529123008403600bbf6c87b529305625fba8")
  , ("GHC/Internal/Exception/Backtrace.hs-boot", "7a57658044a20df0c72ea4ce2b15168b61542c94ed0a8befdf6e29792b549df7")
  , ("GHC/Internal/Exception/Context.hs", "49ac1971d2e8f4947ef3d5dd28fe9497d178f24ac367f2767542257799fb4dc6")
  , ("GHC/Internal/Exception/Type.hs", "70180e8a8a9a8c74e057a8ec1f4dce5f810eedffeff4ce8370cf33118d222d38")
  , ("GHC/Internal/Exception/Type.hs-boot", "f2a0440d35e33a8688d692cf50e4d33e74e92f08e84b04ab8aec20fd4861d2e0")
  , ("GHC/Internal/Exception.hs", "890128de0336c4762b44f709131a8959bf47bc0313b55bed4c071a17b62a9327")
  , ("GHC/Internal/Exception.hs-boot", "7422fa92308439db3c0ca034b02522c96e7961cff00754d0bdca964a4f98bc15")
  , ("GHC/Internal/Fingerprint.hs", "d97e24beb911ef802c3020690480eb4ac59ec0757ab9b482337c94a6963a7e5b")
  , ("GHC/Internal/Foreign/C/String/Encoding.hs", "a1956c04e77737b5796af679df63e884fdb8d0acc6bff40373dbbbd732837d57")
  , ("GHC/Internal/Foreign/Marshal/Alloc.hs", "76bbfcaf09561b667f49c595b9669f1e0808ac495b5754184bd6daa2a961458f")
  , ("GHC/Internal/Foreign/Storable.hs", "dda27f3c55cda6fbce4d44c127b6b26f5f18aa1e8b7ade6510e51cd4eec9e9cd")
  , ("GHC/Internal/ForeignPtr.hs", "8b7b040cd30b3e72c81957b616c13587e14caf9db9dec25e4a7580dac3ca3272")
  , ("GHC/Internal/Heap/Closures.hs", "d2e891979f6c561db40168bdb736a9f3ce67e915459f7ca301a57b1a547c0539")
  , ("GHC/Internal/Heap/Constants.hsc", "fe6012d406045f3808c8e2cb693b0bdc627a0bd524b44e0708769e59317a5b77")
  , ("GHC/Internal/Heap/InfoTable.hsc", "3936cce70289fa88996eed01ecef793faa4a5f750eb182d195b9e6dbd33ee5a0")
  , ("GHC/Internal/Heap/InfoTable/Types.hsc", "f1de0789ad600bd757fd27be9fc92bd9ef77e4520a17f4df2d759ef25b9ad8ef")
  , ("GHC/Internal/InfoProv/Types.hsc", "63f455d41df424cf2dc20713a34d43991a261fb012a3f7f56895233a9b594315")
  , ("GHC/Internal/Fingerprint.hs-boot", "72b19673ee571ca87ebc67470efc62b1ec75c4d4dfe579b4c33d7ac1a90bb246")
  , ("GHC/Internal/IO/Exception.hs", "39aded90d3cce7be4282ea6df2fa0cbf754942ab1aa7c740b5409021170b7ce6")
  , ("GHC/Internal/IO/Encoding.hs", "1b517e6f7c3cd2753c5dd9fe210873cfed7a545eaaf92605d8909d2643539b64")
  , ("GHC/Internal/IO/Encoding/Types.hs", "17ab1ca2385becc376ed5727204999e07989848349fb9235abbb092a7eaabd8a")
  , ("GHC/Internal/IO/Encoding/Failure.hs", "ce27efe1405d4e299d45af597cd6fddea3d205638d766f673241f7aa7f8c5f10")
  , ("GHC/Internal/IO/Encoding/UTF8.hs", "00db78df7e4a9a5404bd3dc9e379d59392ab698c907393a5fe61d74b9020194a")
  , ("GHC/Internal/IO/Exception.hs-boot", "bda7e1dd1ac680f0f1126b467207fd4682175ecf1e6871c7b2786b7a25506dcf")
  , ("GHC/Internal/IO/Handle/Types.hs", "4c719b6081b5e689219380974f72ed294b231521312f5b9a53bd5202019c9c35")
  , ("GHC/Internal/IO/Handle/Types.hs-boot", "8a319eb137cc03c8dbbb9d37b77703f3a962d894092cbd4d4e44c8381eaf3767")
  , ("GHC/Internal/IO/Unsafe.hs", "407dad2a8abda44be6e689f6ac45079c9f6cdf6147e847a4a0f9147ecfb8330f")
  , ("GHC/Internal/IO.hs", "e621ee438883f255d6a540ef761b9d462060f2ad3ed9557e118d55cf964ea25e")
  , ("GHC/Internal/IO.hs-boot", "a687801a14b3b423d45bca16ea03facd5fa0a428f049bcf04c2d3726e272c702")
  , ("GHC/Internal/Ix.hs", "485f592cb602e532a1a13a603aedb7f59b8d215123e8aa8982cd39320fed8c16")
  , ("GHC/Internal/List.hs", "ae9f56a758942b6e937e7b430ac1137e3ebea171762120ad9c31ef1f4904ba39")
  , ("GHC/Internal/Num.hs-boot", "b765e848138b1d4a22710c45db2e446d3e2c5c07c6b774a8d5cf83a5c4a9b92f")
  , ("GHC/Internal/Num.hs", "a35a0fac5e44dde385bc98024d85a82924dac33c59de0996c9978e4295e4077a")
  , ("GHC/Internal/Numeric.hs", "d4f2fdc9caff154a8c849724736c7acfc2f1e66825409cffd22bbdc54752a8fa")
  , ("GHC/Internal/Ptr.hs", "92ef7f10fc4f23ffa860a729c3b2fe13a269c1288dc3d6a017a358c894ec23c6")
  , ("GHC/Internal/Real.hs-boot", "843ed3133589748fbc65e0d7ef7e5a5491dc131b55ff73b67f6e3c3516bb99f4")
  , ("GHC/Internal/Real.hs", "7546f4b80b562ba06feaab6be207e5320258f8734d8be870610c57eae4c8c5e1")
  , ("GHC/Internal/Show.hs", "b37f6d9d376e837785d207f2cf784daf0a53a04db26723a456c073616512be98")
  , ("GHC/Internal/Stack/Decode.hs", "0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f")
  , ("GHC/Internal/Stack/Annotation.hs", "96ad02226d0f5a9dd57b4ad873ceb2056e3e27ce788149f1e94ba29d0c83bb15")
  , ("GHC/Internal/Stack/CCS.hsc", "f146896b0038ad0a645e6b6dca39099d300157f77e443a0ac28545ae44fee5be")
  , ("GHC/Internal/Stack/CloneStack.hs", "1d3bd4ca3252beb53f308bb68abb2a6adfdb21d232849b63bc7009dbc77f4cea")
  , ("GHC/Internal/Stack/Constants.hsc", "7b02d3388f0a0c129bb73c10759a30733e641788ade399a8985f1063746fc4e6")
  , ("GHC/Internal/Stack/Types.hs", "8a035fa9a684c3c3e0ebcc7bc418992503fc322c7ef3d5d6a24023870b29fc25")
  , ("GHC/Internal/Stack.hs", "ab43f19c8fab1732afde9973838dcdfeb625a9af8565fcc493444574a3a8bb5c")
  , ("GHC/Internal/Stack.hs-boot", "7d92acf93446e191068370934f911fea3bfc26a0fc3a78b251f7b399c644677f")
  , ("GHC/Internal/Types.hs", "a8fe6ab5c7a84be9b0710078b549aa63e511e7cb12d056cef91403d05ca6d2bb")
  , ("GHC/Internal/Word.hs", "9ca67ff65c2cc2a0f28442e4fedd7685b7bfac387d2c24f5333477ba38221fad")
  , ("LICENSE", "768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d")
  , ("include/WordSize.h", "16e46daa3e38bfc98adb9360e54af211cada707d551a7720c00af4af907af090")
  ]

-- | Locate a logical module path in the upstream package layout. Auxiliary
-- files such as @include/WordSize.h@ remain relative to the package root.
pinnedSourcePath :: FilePath -> FilePath
pinnedSourcePath path = if "GHC/" `isPrefixOf` path then "src" </> path else path

data WiredArtifacts = WiredArtifacts
  { generatedSources :: [(FilePath, FilePath)]
  , targetLayout :: FilePath
  , sourceBuildReceipt :: Maybe Value
  }

exportPinnedCore :: FilePath -> FilePath -> FilePath -> FilePath -> String ->
                    FilePath -> FilePath -> IO WiredArtifacts
exportPinnedCore = exportPinnedUsing

-- The Windows compiler has no dynamic plugin way. Compile unchanged pinned
-- sources with full Core and read their actual vanilla interfaces through GHC.
exportPinnedWindowsCore :: FilePath -> [FilePath] -> FilePath -> FilePath -> FilePath -> [String] -> FilePath -> FilePath -> IO WiredArtifacts
exportPinnedWindowsCore upstream sourceFiles ghc ghcPkg helper expected layoutRecipe staging = do
  let source = staging </> "src"
      overlay = staging </> "interfaces"
      core = staging </> "core"
  forM_ (filter ("src/" `isPrefixOf`) sourceFiles) $ \path -> do
    let target = source </> drop 4 path
    createDirectoryIfMissing True (takeDirectory target)
    copyFile (upstream </> path) target
  createDirectoryIfMissing True overlay
  createDirectoryIfMissing True core
  internalRegistration <- package "ghc-internal"
  installed <- case Package.importDirs internalRegistration of
    [path] -> pure path
    _ -> fail "Windows ghc-internal requires one registered interface directory"
  backend <- readProcess ghc ["--show-iface", installed </> "GHC/Internal/Bignum/Backend/Selected.hi"] ""
  unless ("GHC.Internal.Bignum.Backend.GMP" `isInfixOf` backend)
    (fail "Selected Windows GHC does not use the pinned GMP backend")
  linkInterfaces True installed overlay installed
  rts <- package "rts"
  let includes = Package.includeDirs rts ++ Package.includeDirs internalRegistration ++ [upstream </> "include"]
  let hsc2hs = takeDirectory ghc </> "hsc2hs.exe"
      definitions = ["BIGNUM_GMP", "_WIN32_WINNT=0x06010000", "mingw32_HOST_OS", "x86_64_HOST_ARCH",
                     "__GLASGOW_HASKELL__=914", "__IO_MANAGER_WINIO__=1", "__IO_MANAGER_MIO__=1"]
  inputs <- treeFiles source
  generated <- forM (filter ((== ".hsc") . takeExtension) inputs) $ \path -> do
    let output = replaceExtension path "hs"
    checked hsc2hs ([path, "-o", output] ++ map ("--cflag=-D" ++) definitions ++ map ("--cflag=-I" ++) includes)
    pure ("src" </> makeRelative source path, output)
  libdir <- oneLine <$> readProcess ghc ["--print-libdir"] ""
  (status, bytes, diagnostic) <- boundedInterfaceProcess helper
    (["--windows-ghc-source-graph", libdir, source, overlay] ++ includes)
  graph <- case eitherDecodeStrict' bytes of
    Right (Object fields) | status == ExitSuccess, Just nodes <- KeyMap.lookup "nodes" fields,
      KeyMap.lookup "unit" fields == Just (String "ghc-internal") -> case Aeson.fromJSON nodes of
        Aeson.Success values -> mapM graphNode values
        Aeson.Error message -> fail message
    _ -> fail ("GHC source graph failed: " ++ show diagnostic)
  let names = [name | (name, False) <- graph]
  unless (sort names == sort expected && length graph == length (nub graph))
    (fail "Compiler source graph differs from the pinned Windows module inventory")
  BL.writeFile (staging </> "source-graph.json") (encode graph)
  let common = ["-c", "-O2", "-g", "-dcore-lint", "-fwrite-if-simplified-core", "-fforce-recomp",
        "-XNoImplicitPrelude", "-XNoPolyKinds", "-DBIGNUM_GMP", "-D_WIN32_WINNT=0x06010000",
        "-this-unit-id", "ghc-internal", "-package", "ghc-internal", "-i" ++ overlay,
        "-odir", overlay, "-hidir", overlay] ++ map ("-I" ++) includes
      relative name = map (\c -> if c == '.' then '/' else c) name
      command (name, boot) = common ++ [source </> relative name ++ if boot then ".hs-boot" else ".hs"]
  forM_ graph $ \node -> checked ghc (command node)
  forM_ names $ \name -> do
    (code, payload, errors) <- boundedInterfaceProcess helper
      ["--libdir", libdir, "--unit", "ghc-internal", "--module", name,
       "--interface", overlay </> relative name ++ ".hi", "--way", "vanilla", "--source-notes",
       "--home-interfaces", overlay]
    case readModuleMetadata payload of
      Right (_,Object fields) | code == ExitSuccess,
        KeyMap.lookup "unit" fields == Just (String "ghc-internal"),
        KeyMap.lookup "module" fields == Just (String (Text.pack name)) -> BS.writeFile (core </> name ++ ".cbd") payload
      _ -> fail ("Windows source interface lacks genuine complete Core: " ++ name ++ " " ++ show errors)
  layout <- probeTargetLayout includes layoutRecipe staging
  pure (WiredArtifacts generated layout (Just (object
    ["schema" .= (1 :: Int), "compiler" .= ghc, "hsc2hs" .= hsc2hs,
     "hscDefinitions" .= definitions, "includeDirectories" .= includes,
     "graphCommand" .= ([helper, "--windows-ghc-source-graph", libdir, source, overlay] ++ includes),
     "steps" .= [object ["module" .= name, "boot" .= boot, "arguments" .= command node,
                          "exit" .= (0 :: Int)] | node@(name, boot) <- graph]])))
  where
    package name = do
      description <- readProcess ghcPkg ["--expand-pkgroot", "describe", name] ""
      (_, registered) <- either (fail . show) pure
        (Package.parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack description)))
      pure registered
    oneLine = reverse . dropWhile (`elem` ("\r\n" :: String)) . reverse
    checked program arguments = do
      status <- runProducer (proc program arguments)
      unless (status == ExitSuccess) (fail ("Windows source build failed: " ++ program ++ " " ++ show arguments))
    graphNode (Object fields) | Just (String name) <- KeyMap.lookup "module" fields,
      Just (Bool boot) <- KeyMap.lookup "boot" fields = case Aeson.fromJSON (String name) of
        Aeson.Success value -> pure (value, boot)
        Aeson.Error message -> fail message
    graphNode _ = fail "Invalid source graph node"

treeFiles :: FilePath -> IO [FilePath]
treeFiles directory = do
  names <- sort <$> listDirectory directory
  concat <$> forM names (\name -> do
    let path = directory </> name
    isDirectory <- doesDirectoryExist path
    if isDirectory then treeFiles path else pure [path])

exportPinnedUsing :: FilePath -> FilePath -> FilePath -> FilePath -> String -> FilePath -> FilePath -> IO WiredArtifacts
exportPinnedUsing packageRoot ghc ghcPkg pluginLibrary pluginUnit layoutRecipe staging = do
  let sourceRoot = packageRoot </> "src"
      overlay = staging </> "interfaces"
      core = staging </> "core"
  createDirectoryIfMissing True overlay
  createDirectoryIfMissing True core
  installedText <- readProcess ghcPkg
    ["field", "ghc-internal", "import-dirs", "--simple-output"] ""
  installed <- case lines installedText of
    [path] | not (null path) -> pure path
    _ -> fail "ghc-internal must have one installed interface directory"
  linkInterfaces False installed overlay installed
  includeDirs <- concatMap words <$> mapM (\package ->
    readProcess ghcPkg ["field", package, "include-dirs", "--simple-output"] "")
    ["rts", "ghc-internal"]
  when (null includeDirs) (fail "GHC target include directories are unavailable")
  let sibling = takeDirectory ghc </> if Host.os == "mingw32" then "hsc2hs.exe" else "hsc2hs"
  siblingExists <- doesFileExist sibling
  hsc2hs <- if siblingExists then pure sibling else
    findExecutable "hsc2hs" >>= maybe (fail "hsc2hs is unavailable") pure
  generated <- forM [path | (path, _) <- moduleSources, takeExtension path == ".hsc"] $ \path -> do
    let output = staging </> "generated" </> replaceExtension path "hs"
        flags = [path, "-o", output] ++ map ("--cflag=-I" ++) includeDirs
    createDirectoryIfMissing True (takeDirectory output)
    status <- runProducer (proc hsc2hs flags) { cwd = Just sourceRoot }
    when (status /= ExitSuccess) (fail ("GHC source preprocessing failed: " ++ path))
    pure (path, output)
  layoutJson <- probeTargetLayout includeDirs layoutRecipe staging
  let common = ["-c", "-dynamic", "-fforce-recomp", "-XNoPolyKinds",
                "-this-unit-id", "ghc-internal", "-package", "ghc-internal",
                "-odir", overlay, "-hidir", overlay, "-I" ++ (packageRoot </> "include")]
      exportFlags = ["-fplugin-library=" ++ pluginLibrary ++ ";" ++ pluginUnit ++ ";THC.Plugin;" ++ BL.unpack
        (encode [core, "post-tidy", "source-notes", "foreign-import-provenance"])]
      compile boot path = do
        let target = overlay </> replaceExtension path (if boot then "hi-boot" else "hi")
        exists <- doesFileExist target
        when exists (removeFile target)
        let flags = if boot then [] else
              ["-O2", "-dcore-lint", "-g"] ++ exportFlags ++
              ["-XNoImplicitPrelude" | path `elem`
                ["GHC/Internal/Stack/Decode.hs", "GHC/Internal/ExecutionStack/Internal.hsc"]]
            source = maybe (sourceRoot </> path) id (lookup path generated)
        status <- runProducer (proc ghc (common ++ flags ++ [source]))
        when (status /= ExitSuccess) $ fail ("pinned GHC source export failed: " ++ path)
  forM_ bootSources (compile True)
  forM_ moduleSources (compile False . fst)
  pure (WiredArtifacts generated layoutJson Nothing)

probeTargetLayout :: [FilePath] -> FilePath -> FilePath -> IO FilePath
probeTargetLayout includeDirs recipe staging = do
  selected <- if Host.os == "mingw32" then lookupEnv "THC_CLANG" else pure Nothing
  layoutCompiler <- maybe (findExecutable (if Host.os == "mingw32" then "clang" else "cc") >>=
    maybe (fail "C compiler is unavailable") pure) pure selected
  createDirectoryIfMissing True staging
  let binary = staging </> if Host.os == "mingw32" then "target-layout.exe" else "target-layout"
      receipt = staging </> "target-layout.json"
  status <- runProducer (proc layoutCompiler
    (["-Wall", "-Werror"] ++ map ("-I" ++) includeDirs ++ [recipe, "-o", binary]))
  when (status /= ExitSuccess) (fail "GHC target layout receipt compilation failed")
  writeFile receipt =<< readProcess binary [] ""
  pure receipt

linkInterfaces :: Bool -> FilePath -> FilePath -> FilePath -> IO ()
linkInterfaces vanilla installed overlay directory = do
  names <- listDirectory directory
  forM_ names $ \name -> do
    let source = directory </> name
    isDirectory <- doesDirectoryExist source
    if isDirectory then linkInterfaces vanilla installed overlay source
    else when ((if vanilla then ".hi" else ".dyn_hi") `isSuffixOf` name) $ do
      let target = overlay </> replaceExtension (makeRelative installed source) "hi"
      createDirectoryIfMissing True (takeDirectory target)
      if vanilla then copyFile source target else createFileLink source target

-- | Order selected registrations before extending their shared interface view.
pinnedDependencyOrder :: [InstalledUnit] -> Either String [InstalledUnit]
pinnedDependencyOrder units
  | Set.size (Set.fromList (map registeredId units)) /= length units = Left "duplicate pinned installed registrations"
  | otherwise = traverse ordered (stronglyConnComp
      [(unit, registeredId unit, installedDepends unit) | unit <- units])
  where
    ordered (AcyclicSCC unit) = Right unit
    ordered (CyclicSCC members) = Left ("pinned installed dependency cycle: " ++ show (map registeredId members))

-- | Core/native acquisition code shared with installed bundle assembly. The
-- plugin, interface helper, selected compiler and installed view are separate
-- inputs. Pinned source compilation below uses its narrower operation recipe.
pinnedRecipeIdentity :: Value
pinnedRecipeIdentity = object
  ["sourceHash" .= recipeHash, "Cabal" .= (VERSION_Cabal :: String),
   "Cabal-syntax" .= (VERSION_Cabal_syntax :: String)]
  where
    recipeHash :: String
    recipeHash = $(do
      source <- loc_filename <$> location
      let root = iterate takeDirectory source !! 5
          files = ["src/cbd/THC/Compact/" ++ name | name <- ["Annotations.hs", "Compression.hs", "Core.hs", "Debug.hs", "Decode.hs", "Encode.hs", "Facts.hs", "Inspect.hs", "JSON.hs", "Module.hs", "Wire.hs", "Writer.hs", "Zip.hs"]] ++
            ["src/core-symbols/THC/" ++ name | name <- ["CoreSymbols.hs"]] ++
            ["src/driver/THC/Driver/" ++ name | name <- ["GhcProxy.hs", "Installed.hs", "InstalledForeign.hs", "Lock.hs", "NativeArgumentBridge.hs", "NativeCache.hs", "NativeDependencies.hs", "NativeLibrarySources.hs", "NativeRecipe.hs", "PackageNative.hs", "PinnedFlags.hs", "PinnedSetup.hs", "RuntimeShim.hs", "ScalarBitcode.hs", "Wired.hs"]] ++
            ["src/main/resources/thc/" ++ name | name <- ["core-native-overrides.json"]]
      records <- forM files $ \name -> do
        let path = root </> name
        addDependentFile path
        bytes <- runIO (BS.readFile path)
        pure (name, BS.unpack (SHA.hash bytes))
      let bytes = SHA.hash (BL.toStrict (encode records))
      lift (concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value)
            (BS.unpack bytes)))

-- Pinned interfaces are built by the selected GHC, not the native capture/link
-- path. GhcProxy contributes only directPlugin's option formatting here. Keep
-- native export changes out of this key while retaining every compilation/view
-- owner and Cabal's configuration implementation. Installed discovery is
-- represented by its consumed outputs in each preparation key below.
pinnedInterfaceRecipeIdentity :: Value
pinnedInterfaceRecipeIdentity = object
  ["sourceHash" .= recipeHash, "Cabal" .= (VERSION_Cabal :: String),
   "Cabal-syntax" .= (VERSION_Cabal_syntax :: String)]
  where
    recipeHash :: String
    recipeHash = $(do
      source <- loc_filename <$> location
      let root = iterate takeDirectory source !! 5
          files = ["src/driver/THC/Driver/" ++ name | name <-
            ["GhcProxy.hs", "InstalledForeign.hs", "Lock.hs", "PinnedFlags.hs", "PinnedSetup.hs", "Wired.hs"]]
      records <- forM files $ \name -> do
        let path = root </> name
        addDependentFile path
        bytes <- runIO (BS.readFile path)
        pure (name, BS.unpack (SHA.hash bytes))
      let bytes = SHA.hash (BL.toStrict (encode records))
      lift (concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value)
            (BS.unpack bytes)))

-- | Rebuild the selected boot-library closure from the exact GHC release,
-- retaining Cabal identities and native registrations in a private interface
-- view, leaving the selected compiler and its package database untouched.
preparePinnedInterfaces :: FilePath -> FilePath -> String -> FilePath ->
                           InstalledContext -> [InstalledUnit] -> IO InstalledContext
preparePinnedInterfaces cache pluginDb pluginUnit pluginLibrary original units = do
  ordered <- either fail pure (pinnedDependencyOrder units)
  settings <- either fail pure . readEither =<< readProcess (installedGhc original) ["--info"] ""
  source <- pinnedRelease cache
  pluginHash <- digest <$> BS.readFile pluginLibrary
  helperHash <- digest <$> BS.readFile (installedHelper original)
  foldM (prepare source pluginHash helperHash settings) original { installedSource = Just source } ordered
  where
    prepare source pluginHash helperHash settings context unit
      | null (installedInterfaces unit) = pure context
      | otherwise = do
          (_, registered) <- either (fail . show) pure
            (Package.parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack (registration unit))))
          let name = prettyShow (pkgName (Package.sourcePackageId registered))
              version = prettyShow (pkgVersion (Package.sourcePackageId registered))
          flags <- either fail pure (pinnedLibraryFlags name (map fst (installedInterfaces unit)) settings)
          backend <- if name /= "ghc-internal" then pure [] else do
            path <- maybe (fail "selected ghc-internal has no bignum backend") pure
              (lookup "GHC.Internal.Bignum.Backend.Selected" (installedInterfaces unit))
            iface <- readProcess (installedGhc original) ["--show-iface", path] ""
            case [flag | (flag, moduleName) <- [("gmp","GMP"),("native","Native"),("ffi","FFI")],
                  ("GHC.Internal.Bignum.Backend." ++ moduleName) `isInfixOf` iface] of
              [flag] -> pure ["-fbignum-" ++ flag]
              _ -> fail "cannot identify selected ghc-internal bignum backend"
          cppFlags <- maybe "" id <$> lookupEnv "CPPFLAGS"
          let selectedFlags = flags ++ backend
              dynamic = installedInterfaceWay context == DynamicInterfaces
              suffixes = if dynamic then ["hi", "dyn_hi"] else ["hi"]
              installedInputs = object
                ["provenance" .= installedProvenance context unit,
                 "packageGlobalArguments" .= packageGlobalArguments context,
                 "ghc" .= installedGhc original, "packageTool" .= installedPackageTool context,
                 "helper" .= installedHelper context, "packageDatabases" .= installedDatabases context]
              key = digest (BL.toStrict (encode
                ("pinned-library-core-v9" :: String, pinnedReleaseIdentity, pinnedInterfaceRecipeIdentity, pluginDb, pluginUnit, pluginHash, helperHash,
                 installedCompiler original, settings, selectedFlags, cppFlags, installedViewIdentity context, registration unit, installedInputs)))
              destination = cache </> "pinned-libraries/v1" </> key
              receipt = destination </> "complete"
          createDirectoryIfMissing True (takeDirectory destination)
          withLock (destination ++ ".lock") $ do
            ready <- doesFileExist receipt
            if ready then pure (viewContext context (destination </> "view")) else do
              exists <- doesDirectoryExist destination
              when exists $ do
                -- An interrupted compiler/tool failure must not poison this
                -- immutable key. Retain its evidence before a clean retry.
                (retained, handle) <- openTempFile (takeDirectory destination) (takeFileName destination ++ ".failed-")
                hClose handle
                removeFile retained
                renameDirectory destination retained
                writeFile (retained </> "original-location") (destination ++ "\n")
              createDirectory destination
              BL.writeFile (destination </> "inputs.json") (encode (object
                ["source" .= pinnedReleaseIdentity, "compiler" .= installedCompiler original,
                 "registration" .= registration unit, "dependencyView" .= installedViewIdentity context,
                 "installedInputs" .= installedInputs, "recipe" .= pinnedInterfaceRecipeIdentity, "pluginDb" .= pluginDb, "pluginHash" .= pluginHash, "helperHash" .= helperHash,
                 "flags" .= selectedFlags, "cppFlags" .= cppFlags, "settings" .= settings]))
              let package = destination </> "source"
                  dist = destination </> "dist"
              let roots = [source </> parent </> name | parent <-
                    ["libraries", "libraries/Cabal", "utils", "utils/haddock"]] ++
                    [source </> "compiler" | name == "ghc"]
              candidates <- filterM (\path -> (||)
                <$> doesFileExist (path </> name ++ ".cabal")
                <*> doesFileExist (path </> name ++ ".cabal.in"))
                (nub (concatMap (\path -> [path, path </> name]) roots))
              origin <- case candidates of
                [path] -> pure path
                _ -> fail ("GHC 9.14.1 source has no unique package " ++ name)
              copyTree origin package
              let cabalFile = package </> name ++ ".cabal"
              hasCabal <- doesFileExist cabalFile
              unless hasCabal $ do
                template <- readFile (cabalFile ++ ".in")
                writeFile cabalFile (replace "@Suffix@" "" (replace "@SourceRoot@" "."
                  (replace "@ProjectVersionForLib@" "9.1401"
                    (replace "@ProjectVersionMunged@" "9.14.1" (replace "@ProjectVersion@" "9.14.1" template)))))
              generic <- readGenericPackageDescription normal Nothing (makeSymbolicPath cabalFile)
              let description = PD.packageDescription generic
              unless (prettyShow (PD.package description) == name ++ "-" ++ version)
                (fail ("Pinned package version differs from selected registration: " ++ name))
              when (name == "ghc-internal") $ do
                -- GHC's top-level configure copies these shared utility
                -- sources into ghc-internal before its package configure.
                copyFile (source </> "utils/fs/fs.c") (package </> "cbits/fs.c")
                copyFile (source </> "utils/fs/fs.h") (package </> "include/fs.h")
                generatePrimitiveWrappers source package destination (installedGhc original)
              dependencyOptions <- forM (installedDepends unit) $ \identifier -> do
                text <- readProcess (installedPackageTool context)
                  (packageGlobalArguments context ++ ["--ipid", "describe", identifier]) ""
                (_, dependency) <- either (fail . show) pure
                  (Package.parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack text)))
                pure ("--dependency=" ++ prettyShow (pkgName (Package.sourcePackageId dependency)) ++ "=" ++ identifier)
              configureOptions <- if PD.buildType description == PD.Configure
                then either fail pure (pinnedConfigureOptions Host.os settings)
                else pure []
              let includes = nub (package : Package.includeDirs registered)
                  options = ["configure",
                    "--builddir=" ++ dist, "--with-compiler=" ++ installedGhc original,
                    "--with-hc-pkg=" ++ installedPackageTool context,
                    "--package-db=clear", "--package-db=" ++ installedGlobalDb context,
                    "--ipid=" ++ registeredId unit, if dynamic then "--enable-shared" else "--disable-shared",
                    "--disable-library-profiling", "--ghc-options=-O2 -fwrite-if-simplified-core"] ++
                    map ("--package-db=" ++) (installedDatabases context) ++ dependencyOptions ++ selectedFlags ++ configureOptions ++
                    map ("--extra-include-dirs=" ++) includes ++
                    -- Cabal runs Configure from dist/build; the genuine source
                    -- headers live beside dist. Keep paths with spaces out of CPPFLAGS.
                    ["--configure-option=CPPFLAGS=" ++ cppFlags ++ " -I../../source" | PD.buildType description == PD.Configure]
              case PD.buildType description of
                PD.Custom -> configurePinnedCustom source package (destination </> "setup") (installedGhc original) options
                buildType -> do
                  setup <- case buildType of
                    PD.Simple -> pure defaultMainArgs
                    PD.Configure -> pure (defaultMainWithSetupHooksArgs autoconfSetupHooks)
                    _ -> fail ("Unsupported pinned package build type: " ++ prettyShow buildType)
                  withCurrentDirectory package $
                    bracket (hDuplicate stdout)
                      (\saved -> hDuplicateTo saved stdout `finally` hClose saved) $ \_ -> do
                        hDuplicateTo stderr stdout
                        setup options
              configured <- getPersistBuildConfig Nothing (makeSymbolicPath dist)
              let configuredPackage = localPkgDescr configured
              lib <- maybe (fail "pinned installed unit is not a library") pure (PD.library configuredPackage)
              component <- case allComponentsInBuildOrder configured of
                [value] -> pure value
                _ -> fail "pinned library has multiple configured components"
              unless (prettyShow (componentUnitId component) == registeredId unit &&
                      sort (map (prettyShow . fst) (componentPackageDeps component)) == sort (installedDepends unit))
                (fail "pinned Cabal configuration changed installed unit/dependency identities")
              let realModule m = prettyShow m /= "GHC.Internal.Prim"
                  selected = filter realModule (allLibModules lib component)
                  expected = filter (/= "GHC.Internal.Prim") (map fst (installedInterfaces unit))
                  info = PD.libBuildInfo lib
                  executableLibrary = lib { PD.exposedModules = filter realModule (PD.exposedModules lib),
                    PD.libBuildInfo = info { PD.otherModules = filter realModule (PD.otherModules info),
                                            PD.autogenModules = filter realModule (PD.autogenModules info) } }
                  output = componentBuildDir configured component
              unless (sort (map prettyShow selected) == sort expected)
                (fail ("configured pinned module inventory differs from installed package " ++ name))
              -- The finder still needs the selected compiler's real primitive
              -- interface at the home-unit search path. GHC supplies its actual
              -- declarations; it has no source target or executable bodies.
              forM_ (lookup "GHC.Internal.Prim" (installedInterfaces unit)) $ \primitive ->
                forM_ suffixes $ \suffix -> do
                  let target = getSymbolicPath output </> "GHC/Internal/Prim." ++ suffix
                  createDirectoryIfMissing True (takeDirectory target)
                  copyFile (replaceExtension primitive suffix) target
              withCurrentDirectory package $ do
                createDirectoryIfMissing True (getSymbolicPath output)
                writeBuiltinAutogenFiles silent configuredPackage configured component
                preprocessComponent configuredPackage (CLib executableLibrary) configured component False silent knownSuffixHandlers
                let base = componentGhcOptions normal configured info component output
                    options' = base <> mempty
                      { ghcOptMode = toFlag GhcModeMake, ghcOptNoLink = toFlag True,
                        ghcOptInputModules = toNubListR selected,
                        ghcOptDynLinkMode = toFlag (if dynamic then GhcStaticAndDynamic else GhcStaticOnly), ghcOptFPic = toFlag True,
                        ghcOptDynHiSuffix = toFlag "dyn_hi", ghcOptDynObjSuffix = toFlag "dyn_o" }
                    core = destination </> "core"
                    rendered = renderGhcOptions (compiler configured) (hostPlatform configured) options'
                    graphArguments = filter (`notElem` ["--make", "-no-link"]) rendered
                    request = destination </> "source-graph-request.json"
                    exportFlags = ["-fplugin-trustworthy"] ++ pinnedPluginOptions Host.os name pluginDb pluginUnit pluginLibrary
                         [core,"post-tidy","unit-qualified","source-notes","foreign-import-provenance",
                          "foreign-export-associations","foreign-export-registration"]
                createDirectoryIfMissing True core
                BL.writeFile request (encode graphArguments)
                graphText <- readProcess (installedHelper context)
                  ["--source-graph", installedLibdir context, request] ""
                graph <- either fail pure (Aeson.eitherDecode (BL.pack graphText) :: Either String [Value])
                nodes <- forM graph $ \node -> case node of
                  Object fields -> do
                    let get :: Aeson.FromJSON a => String -> IO a
                        get fieldName = case KeyMap.lookup (Key.fromString fieldName) fields of
                          Just value -> case Aeson.fromJSON value of
                            Aeson.Success parsed -> pure parsed
                            Aeson.Error message -> fail message
                          Nothing -> fail ("source graph lacks " ++ fieldName)
                    (,,) <$> get "module" <*> get "source" <*> get "boot"
                  _ -> fail "invalid source graph node"
                unless (sort [moduleName | (moduleName, _, False) <- nodes] == sort expected)
                  (fail "GHC source graph differs from complete Cabal module inventory")
                BL.writeFile (destination </> "source-graph.json") (encode graph)
                let coreFlags = ["-O2", "-fwrite-if-simplified-core", "-dcore-lint", "-fforce-recomp"] ++
                      [option | name == "ghc-internal", option <- ["-package-id", registeredId unit]]
                    compileFlags = filter (`notElem` ("--make" : "-no-link" : map prettyShow selected)) rendered ++ ["-c"] ++ coreFlags
                if name /= "ghc-internal"
                  -- Cabal's non-boot module roots retain the complete validated
                  -- graph while GHC loads the plugin once per package.
                  -- Keep the wired ghc-internal bootstrap in explicit graph
                  -- order: GHC 9.14.1 --make panics with <<loop>> on Windows.
                  then checkedIn package (installedGhc original) (rendered ++ coreFlags ++ exportFlags)
                  else forM_ nodes $ \(_, path, boot) ->
                    checkedIn package (installedGhc original) (compileFlags ++ (if boot then [] else exportFlags) ++ [path])
              createDirectory (destination </> "interfaces")
              forM_ expected $ \moduleName -> forM_ suffixes $ \extension -> do
                let relative = map (\c -> if c == '.' then '/' else c) moduleName ++ "." ++ extension
                    target = destination </> "interfaces" </> relative
                createDirectoryIfMissing True (takeDirectory target)
                (if Host.os == "mingw32" then copyFile else createFileLink) (getSymbolicPath output </> relative) target
              view <- createView context unit destination expected
              writeFile receipt (show (registeredId unit, expected) ++ "\n")
              pure (viewContext context view)

-- The release tarball carries upstream-generated parser sources/configure
-- scripts. Its immutable checksum ties those products to the pinned release;
-- users do not need a bootstrap GHC build or local Alex/Happy installation.
pinnedReleaseIdentity :: Value
pinnedReleaseIdentity = object
  ["version" .= ("9.14.1" :: String),
   "gitCommit" .= ("902339d332fb4ce2b3c87dcac1ee6495d41ad886" :: String),
   "sha256" .= pinnedReleaseChecksum]

pinnedReleaseChecksum :: String
pinnedReleaseChecksum = "2a83779c9af86554a3289f2787a38d6aa83d00d136aa9f920361dd693c101e77"

pinnedRelease :: FilePath -> IO FilePath
pinnedRelease cache = do
  let checksum = pinnedReleaseChecksum
      directory = cache </> "pinned-sources" </> checksum
      archive = directory </> "ghc-9.14.1-src.tar.xz"
      source = directory </> "ghc-9.14.1"
      ready = directory </> "complete"
  createDirectoryIfMissing True directory
  withLock (directory </> ".lock") $ do
    exists <- doesFileExist ready
    unless exists $ do
      present <- doesFileExist archive
      valid <- if present then (== checksum) . digest <$> BS.readFile archive else pure False
      unless valid $ bracket (openTempFile directory "download-")
        (\(temporary, _) -> do remaining <- doesFileExist temporary; when remaining (removeFile temporary)) $
        \(temporary, handle) -> do
          hClose handle
          checkedIn directory "curl"
            ["--fail","--location","--output",temporary,"https://downloads.haskell.org/ghc/9.14.1/ghc-9.14.1-src.tar.xz"]
          actual <- digest <$> BS.readFile temporary
          unless (actual == checksum) (fail "pinned GHC source archive checksum differs")
          renameFile temporary archive
      checkedIn directory "tar" ["-xJf",archive]
      writeFile ready (checksum ++ "\n")
    pure source

generatePrimitiveWrappers :: FilePath -> FilePath -> FilePath -> FilePath -> IO ()
generatePrimitiveWrappers source package destination ghc = do
  let directory = destination </> "generator"
      generator = directory </> "genprimopcode"
      primops = directory </> "primops.txt"
      upstream = source </> "utils/genprimopcode"
  createDirectory directory
  checkedIn upstream ghc ["--make","-O0","-i" ++ upstream,"-outputdir",directory,
                         upstream </> "Main.hs","-o",generator]
  checkedIn source ghc ["-E","-cpp","-optP-P","-x","hs",
    source </> "compiler/GHC/Builtin/primops.txt.pp","-o",primops]
  input <- unlines . filter (not . isPrefixOf "{-# LINE ") . lines <$> readFile primops
  output <- readProcess generator ["--make-haskell-wrappers"] input
  writeFile (package </> "src/GHC/Internal/PrimopWrappers.hs") output

copyTree :: FilePath -> FilePath -> IO ()
copyTree source target = do
  createDirectoryIfMissing True target
  names <- listDirectory source
  forM_ names $ \name -> do
    let from = source </> name; to = target </> name
    directory <- doesDirectoryExist from
    if directory then copyTree from to else copyFile from to

checkedIn :: FilePath -> FilePath -> [String] -> IO ()
checkedIn directory program arguments = do
  status <- runProducer (proc program arguments) { cwd = Just directory, std_out = UseHandle stderr }
  unless (status == ExitSuccess) (fail ("pinned library command failed: " ++ program ++ " " ++ show arguments))

digest :: BS.ByteString -> String
digest = concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value) . BS.unpack . SHA.hash

replace :: String -> String -> String -> String
replace old new = go where
  go [] = []
  go input | Just rest <- stripPrefix old input = new ++ go rest
  go (c:rest) = c : go rest
