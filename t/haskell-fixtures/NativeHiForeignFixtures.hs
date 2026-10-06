-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Build the native component used by the direct-interface execution check.
-- Inputs: the fixture C source, selected LLVM tools, and production ABI adapters.
-- Outputs: component bitcode/descriptor, adapter source and compiler dependencies.
-- The native GHC oracle compiles the same C implementation independently.
module NativeHiForeignFixtures (prepareNativeHiForeign) where

import Control.Monad (forM)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (object, (.=), encode)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (nub, sort)
import FixtureSupport (hashFile, hexBytes, writeJson)
import System.Directory (createDirectoryIfMissing)
import System.FilePath ((</>))
import System.Process (callProcess, readProcess)
import THC.Driver.PackageNative (nativeWrapperSource, nativeAddressSource, tool)
import THC.Driver.ScalarBitcode (sulongScalarTarget, readDependencies)

-- | Reuse the package linker's ABI adapters for a function and a data address.
-- No guest package acquisition or Core export is involved.
prepareNativeHiForeign :: FilePath -> IO ()
prepareNativeHiForeign root = do
  clang <- tool "THC_CLANG" "clang"
  llvmLink <- tool "THC_LLVM_LINK" "llvm-link"
  machine <- readProcess clang ["-dumpmachine"] ""
  target <- case lines machine of
    [value] -> pure (sulongScalarTarget value)
    _ -> fail "clang returned no unique target triple"
  clangVersion <- readProcess clang ["--version"] ""
  linkVersion <- readProcess llvmLink ["--version"] ""
  let out = root </> "build/native-hi-execution"
      source = root </> "t/fixtures/compiler/native-hi-foreign.c"
      adapter = out </> "native-hi-adapters.c"
      component = out </> "native-hi-foreign.bc"
      descriptor = out </> "native-hi-foreign.json"
      signature = ("native_hi_step", "ccall", "unsafe", ["IntRep"], "IntRep")
  sourceHash <- hashFile source
  -- Hash the actual adapter recipe before introducing its namespaced entries.
  recipeCalls <- either fail pure (nativeWrapperSource [(signature, "entry1", Nothing)])
  recipeAddress <- either fail pure (nativeAddressSource [("native_hi_constant", False, "entry0")])
  let identity = hexBytes . SHA.hash . BL.toStrict . encode $ object
        ["source" .= sourceHash, "target" .= target, "clang" .= clangVersion,
         "llvmLink" .= linkVersion, "adapters" .= (recipeCalls ++ recipeAddress)]
      entry index = "thc_native_" ++ identity ++ "_" ++ show (index :: Int)
  calls <- either fail pure (nativeWrapperSource [(signature, entry 1, Nothing)])
  address <- either fail pure (nativeAddressSource [("native_hi_constant", False, entry 0)])
  createDirectoryIfMissing True out
  writeFile adapter ("#include <stdint.h>\n" ++ calls ++ address)
  compiled <- forM [(source, "native-hi-source"), (adapter, "native-hi-adapters")] $ \(input, name) -> do
    let bitcode = out </> name ++ ".bc"
        dependencies = out </> name ++ ".d"
    callProcess clang ["-O1", "-target", target, "-emit-llvm", "-c", "-MD", "-MT", "thc_scalar_input",
      "-MF", dependencies, input, "-o", bitcode]
    inputs <- readDependencies dependencies
    pure (bitcode, inputs)
  callProcess llvmLink (map fst compiled ++ ["-o", component])
  bytes <- BS.readFile component
  let abi symbol index arguments result = object
        ["symbol" .= (symbol :: String), "entry" .= entry index, "convention" .= ("ccall" :: String),
         "safety" .= ("unsafe" :: String), "arguments" .= (arguments :: [String]), "result" .= (result :: String)]
  writeJson descriptor $ object
    ["schema" .= (1 :: Int), "format" .= ("llvm-bitcode" :: String),
     "profile" .= ("thc-package-c-ffi-v1" :: String), "unit" .= ("thc-native-hi-scalar" :: String),
     "target" .= target, "componentSha256" .= identity, "bitcodeSha256" .= hexBytes (SHA.hash bytes),
     "bitcodeHex" .= hexBytes bytes, "dataSymbols" .= [entry 0],
     "abi" .= [abi "native_hi_constant" 0 [] "AddrRep", abi "native_hi_step" 1 ["IntRep"] "IntRep"]]
  -- Generated adapter source belongs to this edge, so it is not its own input.
  let inputs = filter (/= adapter) . sort . nub $ concatMap snd compiled
  writeFile (out </> "native-hi-foreign.d") (escape descriptor ++ ": " ++ unwords (map escape inputs) ++ "\n")
  where
    escape = concatMap $ \c -> case c of
      '$' -> "$$"
      _ | c `elem` (" \\#:" :: String) -> ['\\', c]
        | otherwise -> [c]
