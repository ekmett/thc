-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : PackageNativeTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and driver test dependencies
--
-- Tests for package native.
module PackageNativeTests (tests) where

import Control.Monad (forM_)
import Control.Exception (bracket)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, (.=))
import qualified Data.ByteString as BS
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import Data.Either (isLeft)
import Data.List (isInfixOf, isPrefixOf)
import qualified Data.Text as Text
import GHC.ResponseFile (escapeArgs)
import System.Directory (findExecutable, getCurrentDirectory, createDirectory, createDirectoryIfMissing, removeFile, getModificationTime, canonicalizePath,
  makeAbsolute, withCurrentDirectory, createFileLink)
import System.Environment (getEnv, lookupEnv, setEnv, unsetEnv)
import System.IO (openTempFile, hClose)
import System.IO.Error (tryIOError)
import System.FilePath ((</>), searchPathSeparator)
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode, readProcess, readProcessWithExitCode)
import System.Exit (ExitCode(..))
import qualified System.Info as Host
import Test.HUnit
import THC.Driver.PackageNative
import THC.Driver.NativeCache (nativeCompilerEnvironment, nativeCompilerFlags)
import THC.Driver.NativeLibrarySources (nativeLinkOptions, nativePackageOptions,
  nativePackageSelectors, packageNativeLibraries)
import THC.Driver.NativeArgumentBridge (nativeArgumentBridge, nativeCallWitness, nativeProviderForwarding)
import THC.Driver.NativeDependencies (NativePieceSelection(..), selectNativePiecesAvailable, selectNativePieces, nativeSymbolArchives)
import THC.Driver.Installed (installedContext, InstalledContext(..))
import THC.Driver.Project (selectedPackageTool)
import THC.Compact.Module (readModuleValue)
import THC.Compact.Inspect (unpackContainer)
import NativeCacheTests (withScratch, withEnvironment, writeExecutable)

tests :: Test
tests = TestLabel "package-owned native C acquisition" $ TestList
  [ TestLabel "normal captured GHC calls publish typed unlinked seeds and one C provider" $ TestCase $ withScratch $ \root -> do
      repository <- lookupEnv "THC_TEST_ROOT" >>= maybe getCurrentDirectory pure
      compiler <- tool "GHC" "ghc"
      packageTool <- tool "GHC_PKG" "ghc-pkg"
      helper <- tool "THC_TEST_INTERFACE" "thc-interface"
      libdir <- readProcess compiler ["--print-libdir"] "" >>= \output -> case lines output of
        [selectedLibdir] -> pure selectedLibdir
        _ -> assertFailure "selected GHC must report one library directory"
      let directory = root </> "acquired"
          objects = directory </> "objects"
          source = root </> "Demand.hs"
          cSource = root </> "provider.c"
          nativeObject = objects </> "provider.o"
          arguments = ["-odir",objects,"-hidir",objects] ++
            ["-dynamic" | Host.os /= "mingw32"] ++ ["-this-unit-id","fixture-unit"]
      createDirectoryIfMissing True objects
      writeFile source $ unlines
        ["{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedFFITypes, ForeignFunctionInterface #-}",
         "module Demand (next, nextSafe) where", "import GHC.Exts", "import GHC.IO (IO(..))",
         "foreign import ccall unsafe \"next\" raw :: IO Int",
         "foreign import ccall safe \"next\" rawSafe :: IO Int",
         "next :: State# RealWorld -> Int#", "next s = case raw of IO action -> case action s of (# _, I# value #) -> value",
         "nextSafe :: State# RealWorld -> Int#", "nextSafe s = case rawSafe of IO action -> case action s of (# _, I# value #) -> value"]
      writeFile cSource $ unlines
        ["#include <stdint.h>", "static uintptr_t state;",
         "__attribute__((constructor)) static void initialize(void) { state = 40; }",
         "__attribute__((noinline)) intptr_t next(void) { return ++state; }"]
      let nativeArguments = ["-c",cSource,"-o",nativeObject]
      (nativeStatus,_,nativeErrors) <- readProcessWithExitCode compiler nativeArguments ""
      assertEqual nativeErrors ExitSuccess nativeStatus
      canonicalObject <- canonicalizePath nativeObject
      withEnvironment [("THC_CORE_OUT",directory </> "core"),("THC_GHC_OUT",objects)] $ do
        let exportArguments = ["-this-unit-id=fixture-unit","-fwrite-if-simplified-core",
              "-fplugin-opt=THC.Plugin:post-tidy","-fplugin-opt=THC.Plugin:unit-qualified",
              "-fplugin-opt=THC.Plugin:foreign-import-provenance",source]
        (status,_,diagnostic) <- if Host.os == "mingw32"
          then do
            powershell <- maybe "powershell.exe" id <$> findExecutable "pwsh"
            let response = root </> "export.args"
            writeFile response (escapeArgs exportArguments)
            readProcessWithExitCode powershell
              ["-NoProfile","-File",repository </> "bin/export-core.ps1","@" ++ response] ""
          else readProcessWithExitCode (repository </> "bin/export-core.sh") exportArguments ""
        assertEqual diagnostic ExitSuccess status
      withCurrentDirectory root $ do
        captureNativeObject (root </> "pieces") compiler nativeArguments
        capturePackageNative repository helper libdir compiler arguments "fixture-unit" directory
      let cbd = directory </> "core/units/u-fixture-unit/Demand.cbd"
      before <- BS.readFile cbd
      original <- either assertFailure pure (readModuleValue before)
      signatures <- either assertFailure pure (nativeSignatures "fixture-unit" [original])
      assertEqual "both actual GHC emitted safety variants survive hydration" 2 (length signatures)
      (_,descriptor) <- finishPackageNativeWithDependencies packageTool Nothing [] [] (root </> "pieces")
        directory directory "fixture-unit" (Just [canonicalObject]) [("Demand",cbd)]
      assertBool "normal producer publishes a canonical actual C component" (descriptor /= Nothing)
      after <- BS.readFile cbd
      acquired <- either assertFailure pure (readModuleValue after)
      (_,_,originalSegments) <- either assertFailure pure (unpackContainer before)
      (_,_,acquiredSegments) <- either assertFailure pure (unpackContainer after)
      assertEqual "native publication preserves all six original body/debug/source segments"
        originalSegments acquiredSegments
      let proof = maybe (error "normal producer native link") id (lookupField "packageNativeLink" acquired)
      assertEqual "ordinary calls are typed unlinked acquisition seeds, not eager native roots"
        (Just (toJSON (3::Int))) (lookupField "schema" proof)
      case lookupField "callSeeds" proof of
        Just (Array seeds) -> assertEqual "one immutable seed per actual emitted ABI" 2 (length seeds)
        _ -> assertFailure "normal CBD lacks actual call seeds"
      assertEqual "original typed foreign import inventory remains unchanged"
        (lookupField "staticForeignImports" original) (lookupField "staticForeignImports" acquired)
      let cache = directory </> "native/component-link.json"
          forwarding = directory </> "native/demand/forwarding.bc"
      cached <- BS.readFile cache
      produced <- getModificationTime forwarding
      -- A new staging copy of the same immutable original module has the same
      -- normal acquisition identity, without blessing finalized metadata.
      BS.writeFile cbd before
      (_,again) <- finishPackageNativeWithDependencies packageTool Nothing [] [] (root </> "pieces")
        directory directory "fixture-unit" (Just [canonicalObject]) [("Demand",cbd)]
      assertEqual "normal acquisition reuses the canonical component descriptor" descriptor again
      assertEqual "hit leaves the verified canonical cache receipt unchanged" cached =<< BS.readFile cache
      assertEqual "hit performs no forwarding/LLVM construction" produced =<< getModificationTime forwarding
      assertEqual "identical immutable input produces identical qualified CBD" after =<< BS.readFile cbd
      writeFile (root </> "qualified-cbd.path") cbd
  , TestLabel "capture verifies the sole adapter root, ABI and final canonical provider" $ TestCase $ do
      withScratch $ \root -> do
        clang <- tool "THC_CLANG" "clang"
        nm <- tool "THC_LLVM_NM" "llvm-nm"
        opt <- tool "THC_LLVM_OPT" "opt"
        let target = "x86_64-unknown-linux-gnu"
            provider = "thc_provider_verified_next"
            entryName = "thc_native_verified_0"
            signature = ("next","ccall","unsafe",[],"IntRep")
            compile name body = do
              let input = root </> name ++ ".c"; output = root </> name ++ ".bc"
                  ir = root </> name ++ ".ll"
              writeFile input body
              (status,_,diagnostic) <- readProcessWithExitCode clang
                ["--target=" ++ target,"-O1","-emit-llvm","-c",input,"-o",output] ""
              assertEqual diagnostic ExitSuccess status
              (verified,_,verificationErrors) <- readProcessWithExitCode opt ["-S","-passes=verify",output,"-o",ir] ""
              assertEqual verificationErrors ExitSuccess verified
              readFile ir
            names flags = do
              output <- readProcess nm [flags,"--format=posix",root </> "seed.bc"] ""
              pure [name | line <- lines output, name:_ <- [words line]]
        providerSource <- compile "provider" $
          "static unsigned long state = 40; __attribute__((noinline)) long next(void) { return ++state; }\n" ++
          "long " ++ provider ++ "(void) { return next(); }\n"
        wrapper <- either assertFailure pure $
          nativeWrapperSource [((provider,"ccall","unsafe",[],"IntRep"),entryName,Nothing)]
        seedSource <- compile "seed" ("#include <stdint.h>\n" ++ wrapper)
        definitions <- names "--defined-only"
        externals <- names "--undefined-only"
        let witness actualTarget actualSignature actualProvider actualDefinitions actualExternals =
              nativeCallSeedWitness actualTarget actualSignature entryName actualProvider
                providerSource seedSource actualDefinitions actualExternals
        assertBool "actual compiled adapter binds exactly its canonical provider"
          (witness target signature provider definitions externals)
        assertBool "safe calls retain the same actual C ABI"
          (witness target ("next","ccall","safe",[],"IntRep") provider definitions externals)
        forM_ [("target",witness "aarch64-unknown-linux-gnu" signature provider definitions externals),
          ("convention",witness target ("next","capi","unsafe",[],"IntRep") provider definitions externals),
          ("safety",witness target ("next","ccall","interruptible",[],"IntRep") provider definitions externals),
          ("result",witness target ("next","ccall","unsafe",[],"FloatRep") provider definitions externals),
          ("arguments",witness target ("next","ccall","unsafe",["IntRep"],"IntRep") provider definitions externals),
          ("unknown carrier",witness target ("next","ccall","unsafe",[],"invented") provider definitions externals),
          ("canonical forwarder",witness target signature "next" definitions ["next"]),
          ("extra definition",witness target signature provider (entryName:"extra":[]) externals),
          ("extra external",witness target signature provider definitions (provider:"extra":[]))] $ \(label,accepted) ->
            assertBool ("capture rejects changed " ++ label) (not accepted)
  , TestLabel "exact forwarding uses the actual Darwin LLVM ABI and linker spelling" $ TestCase $ do
      withScratch $ \root -> do
        clang <- tool "THC_CLANG" "clang"
        nm <- tool "THC_LLVM_NM" "llvm-nm"
        opt <- tool "THC_LLVM_OPT" "opt"
        forM_ ["x86_64-apple-darwin", "arm64-apple-macosx14.0.0"] $ \selected -> do
          let provider = "thc_provider_darwin_next"
              entryName = "thc_native_darwin_0"
              compile name body = do
                let bitcode = root </> name ++ ".bc"; ir = root </> name ++ ".ll"
                (status,_,diagnostic) <- readProcessWithExitCode clang
                  ["--target=" ++ selected,"-O1","-emit-llvm","-c","-x","c","-","-o",bitcode] body
                assertEqual diagnostic ExitSuccess status
                (verified,_,diagnosticIR) <- readProcessWithExitCode opt ["-S","-passes=verify",bitcode,"-o",ir] ""
                assertEqual diagnosticIR ExitSuccess verified
                source <- readFile ir
                pure (bitcode,source)
          (_,providerSource) <- compile "darwin-provider" $
            "static unsigned long state = 40; __attribute__((noinline)) long next(void) { return ++state; }\n" ++
            "long " ++ provider ++ "(void) { return next(); }\n"
          let targets = [value | line <- lines providerSource,
                "target triple = " `isPrefixOf` line, (value,"") <- reads (drop 16 line)]
          target <- case targets of [value] -> pure value; _ -> assertFailure "actual provider target missing"
          wrapper <- either assertFailure pure $
            nativeWrapperSource [((provider,"ccall","unsafe",[],"IntRep"),entryName,Nothing)]
          (seed,seedSource) <- compile "darwin-seed" ("typedef __INTPTR_TYPE__ intptr_t;\n" ++ wrapper)
          definitions <- words <$> readProcess nm ["--defined-only","--format=just-symbols",seed] ""
          externals <- words <$> readProcess nm ["--undefined-only","--format=just-symbols",seed] ""
          assertEqual "actual Darwin nm adds one linker underscore" ['_':entryName] definitions
          assertEqual "actual Darwin nm prefixes the external provider" ['_':provider] externals
          assertBool "actual matching Darwin call and canonical provider are witnessed"
            (nativeCallSeedWitness target ("next","ccall","unsafe",[],"IntRep") entryName provider
              providerSource seedSource (map (nativeIrSymbol target) definitions) (map (nativeIrSymbol target) externals))
          fragment <- maybe (assertFailure "forwarding requires an actual verified Darwin definition") pure
            (nativeProviderForwarding target "next" provider providerSource)
          let forwarding = root </> "darwin-forwarding.ll"
              forwardingVerified = root </> "darwin-forwarding-verified.ll"
              headers = filter (\line -> any (`isPrefixOf` line) ["target triple = ","target datalayout = "])
                (lines providerSource)
          writeFile forwarding (unlines headers ++ fragment)
          (forwarded,_,forwardErrors) <- readProcessWithExitCode opt
            ["-S","-passes=verify",forwarding,"-o",forwardingVerified] ""
          assertEqual forwardErrors ExitSuccess forwarded
          forwardSource <- readFile forwardingVerified
          assertBool "actual verified forwarding preserves the provider ABI and module identity"
            (nativeCallWitness target "next" provider providerSource forwardSource /= Nothing)
          let withoutHeader prefix = unlines . filter (not . (prefix `isPrefixOf`)) . lines
              layout = [line | line <- lines seedSource, "target datalayout = " `isPrefixOf` line]
              badLayouts = [withoutHeader "target datalayout = " seedSource,
                unlines layout ++ seedSource,
                "target datalayout = \"e-p:32:32\"\n" ++ withoutHeader "target datalayout = " seedSource,
                "target datalayout = \"\"\n" ++ withoutHeader "target datalayout = " seedSource,
                "target datalayout = malformed\n" ++ seedSource,
                withoutHeader "target triple = " seedSource,
                "target triple = " ++ show target ++ "\n" ++ seedSource]
          forM_ badLayouts $ \bad -> assertBool "missing, duplicate or different module identity cannot witness a call"
            (nativeCallWitness target provider entryName providerSource bad == Nothing)
          assertBool "a selected target cannot replace the actual module target"
            (nativeCallWitness "aarch64-unknown-linux-gnu" provider entryName providerSource seedSource == Nothing)
  , TestLabel "Core-owned exact calls do not manufacture native wrappers" $ TestCase $ do
      let rep kind prim evaluated = object ["kind" .= (kind::String),
            "primReps" .= (prim::[String]), "evaluated" .= (evaluated::Bool)]
          call = object ["schema" .= (1::Int), "target" .= object
            ["kind" .= ("static"::String), "unit" .= ("ghc-internal"::String),
             "symbol" .= ("getProgArgv"::String), "isFunction" .= True],
            "convention" .= ("ccall"::String), "safety" .= ("unsafe"::String),
            "arity" .= (3::Int), "suppliedArity" .= (3::Int),
            "argumentReps" .= [rep "address" ["AddrRep"] False,
              rep "address" ["AddrRep"] False, rep "void" [] False],
            "resultRep" .= object ["kind" .= ("unknown"::String),
              "primReps" .= ([]::[String]), "evaluated" .= False,
              "aggregate" .= ("unboxed-tuple"::String), "components" .= [rep "void" [] True]]]
          moduleValue owner descriptor = object ["unit" .= (owner::String),
            "bindings" .= [object ["foreignCall" .= descriptor]]]
          target = case call of Object fields -> maybe (error "test target") id (KM.lookup "target" fields); _ -> error "test call"
          ordinaryCall = set "target" (set "unit" "ordinary-unit" target) call
      assertEqual "the actual Core handler owns this exact call, not the native companion"
        (Right []) (installedNativeSignatures "ghc-internal" (moduleValue "ghc-internal" call))
      assertEqual "same spelling in an ordinary unit remains a strict native demand"
        (Right [("getProgArgv","ccall","unsafe",["AddrRep","AddrRep"],"void")])
        (installedNativeSignatures "ordinary-unit" (moduleValue "ordinary-unit" ordinaryCall))
      assertBool "a selected runtime identity with the wrong safety is not a native RTS escape"
        (isLeft (installedNativeSignatures "ghc-internal"
          (moduleValue "ghc-internal" (set "safety" "safe" call))))
      let imported = change "binder" "unit" "ghc-internal" $
            changeEmitted "unit" "ghc-internal" (entry "getProgArgv" "ccall" ["AddrRep","AddrRep","void"] ["void"])
          typedModule = set "unit" "ghc-internal" $ set "bindings" (toJSON [object ["foreignCall" .= call]]) $
            changeProof "unit" "ghc-internal" $ changeProof "expectedCalls" (toJSON [call]) (moduleWith [imported])
      assertEqual "installed typed imports use the same full call proof" (Right [])
        (installedNativeSignatures "ghc-internal" typedModule)
      assertEqual "source typed imports omit the same exact Core wrapper" (Right [])
        (nativeSignatures "ghc-internal" [typedModule])
      forM_ ["Rts.h", "native.h"] $ \header -> do
        let retainedHeader = changeProof "imports" (toJSON [set "header" header imported]) typedModule
        assertEqual "installed typed ccall headers preserve the full Core ownership proof" (Right [])
          (installedNativeSignatures "ghc-internal" retainedHeader)
        assertEqual "source typed ccall headers preserve the full Core ownership proof" (Right [])
          (nativeSignatures "ghc-internal" [retainedHeader])
      assertBool "omission cannot bypass the complete expectedCalls inventory"
        (isLeft (nativeSignatures "ghc-internal" [changeProof "expectedCalls" (toJSON ([]::[Value])) typedModule]))
      assertBool "typed ABI drift cannot be admitted as a Core capability"
        (isLeft (nativeSignatures "ghc-internal" [changeProof "imports"
          (toJSON [changeEmitted "arguments" (toJSON (["IntRep","AddrRep","void"]::[String])) imported]) typedModule]))
  , TestLabel "actual native C references retain strict provider obligations" $ TestCase $ withScratch $ \root -> do
      clang <- tool "THC_CLANG" "clang"
      target <- takeWhile (/= '\n') <$> readProcess clang ["-dumpmachine"] ""
      compilerFlags <- nativeCompilerFlags
      compilerEnvironment <- nativeCompilerEnvironment
      let source = root </> "ordinary.c"
          output = root </> "ordinary-library"
          strict = if Host.os == "darwin" then ["-Wl,-undefined,error"] else ["-Wl,--no-undefined"]
          arguments name = compilerFlags ++ ["-shared","-fPIC",source,"-o",output] ++ strict ++ nativeRootArguments target [name]
          command name = (proc clang (arguments name)) { env = Just compilerEnvironment }
      writeFile source "long ordinary_provider(void) { return 7; }\n"
      (positive,_,positiveErrors) <- readCreateProcessWithExitCode (command "ordinary_provider") ""
      assertEqual ("selected compiler accepts the ordinary strict recipe: " ++ positiveErrors) ExitSuccess positive
      writeFile source "extern void getProgArgv(void *, void *); void ordinary_consumer(void) { getProgArgv(0, 0); }\n"
      (missing,_,missingErrors) <- readCreateProcessWithExitCode (command "ordinary_consumer") ""
      assertBool "a Core capability is not a native definition" (missing /= ExitSuccess)
      assertBool "the actual retained C reference names its missing provider"
        ("getProgArgv" `isInfixOf` missingErrors)
  , TestLabel "Windows installed scalar adapters retain the real private C dependency closure" $ TestCase $
      if Host.os /= "mingw32" then pure () else do
        scratch <- getEnv "THC_TEST_SCRATCH"
        (root,handle) <- openTempFile scratch "windows-native-dependency-"
        hClose handle; removeFile root; createDirectory root
        repository <- getEnv "THC_TEST_ROOT"
        compiler <- tool "GHC" "ghc"
        packageTool <- tool "GHC_PKG" "ghc-pkg"
        libdir <- readProcess compiler ["--print-libdir"] "" >>= \output -> case lines output of
          [selectedLibdir] -> pure selectedLibdir
          _ -> assertFailure "selected GHC must report one library directory"
        -- This actual archive member also references five RTS functions. The
        -- scalar call must retain those physical obligations, without exposing
        -- a native guest runtime or substituting the original HsInt ABI.
        archives <- nativeSymbolArchives packageTool libdir root "ghc-internal" ["-package","ghc-internal"] [("__hscore_bufsiz",True)]
        archive <- case archives of [(archivePath,_)] -> pure archivePath; _ -> assertFailure "no original buffer-size archive"
        let source = root </> "WindowsNativeDependency.hs"
            cbd = root </> "core/units/u-fixture-unit/WindowsNativeDependency.cbd"
        writeFile source $ unlines
          ["{-# LANGUAGE MagicHash, UnboxedTuples, ForeignFunctionInterface #-}",
           "module WindowsNativeDependency (bufferSize) where", "import GHC.Exts", "import GHC.IO (IO(..))",
           "foreign import ccall unsafe \"__hscore_bufsiz\" original :: IO Int",
           "bufferSize :: Int# -> Int#",
           "bufferSize input = case original of IO action -> case action realWorld# of (# _, I# value #) -> value +# input"]
        powershell <- maybe "powershell.exe" id <$> findExecutable "pwsh"
        let response = root </> "export.args"
        writeFile response (escapeArgs ["-this-unit-id=fixture-unit","-fwrite-if-simplified-core",
          "-fplugin-opt=THC.Plugin:post-tidy","-fplugin-opt=THC.Plugin:unit-qualified",
          "-fplugin-opt=THC.Plugin:foreign-import-provenance",source])
        withEnvironment [("THC_CORE_OUT",root </> "core"),("THC_GHC_OUT",root </> "objects")] $ do
          (status,_,diagnostic) <- readProcessWithExitCode powershell
            ["-NoProfile","-File",repository </> "bin/export-core.ps1","@" ++ response] ""
          assertEqual diagnostic ExitSuccess status
        let oracle = root </> "Oracle.hs"
            executable = root </> "oracle.exe"
        writeFile oracle "module Main where\nimport GHC.Internal.System.Posix.Internals (dEFAULT_BUFFER_SIZE)\nmain :: IO ()\nmain = print dEFAULT_BUFFER_SIZE\n"
        (built,_,buildErrors) <- readProcessWithExitCode compiler
          ["--make","-O2","-dcore-lint","-dstg-lint",oracle,"-odir",root,"-hidir",root,"-o",executable] ""
        assertEqual buildErrors ExitSuccess built
        (ran,expected,runErrors) <- readProcessWithExitCode executable [] ""
        assertEqual runErrors ExitSuccess ran
        writeFile (root </> "oracle.stdout") expected
        original <- BS.readFile cbd
        _ <- linkInstalledNative compiler packageTool libdir ["-package","ghc-internal","-optl" ++ archive]
          root "fixture-unit" [("WindowsNativeDependency",cbd)]
        acquired <- either assertFailure pure . readModuleValue =<< BS.readFile cbd
        let proof = maybe (error "missing Windows native proof") id (lookupField "packageNativeLink" acquired)
        assertEqual "Sulong receives raw LLVM, never a PE file labelled ELF" (Just "llvm-bitcode") (lookupField "format" proof)
        case lookupField "target" proof of
          Just (String target) -> assertBool "the adapter actually emits MSVC LLVM" ("x86_64-pc-windows-msvc" `isPrefixOf` Text.unpack target)
          _ -> assertFailure "missing actual Windows adapter target"
        (_,_,before) <- either assertFailure pure (unpackContainer original)
        (_,_,after) <- either assertFailure pure . unpackContainer =<< BS.readFile cbd
        assertEqual "native publication preserves all original Core segments" before after
        assertBool "actual native dependency DLL remains in the checked proof" (lookupField "nativeLibrary" proof /= Nothing)
        observed <- (either assertFailure pure . eitherDecodeStrict' =<< BS.readFile (root </> "native/inputs.json")) :: IO Value
        assertBool "durable RTS archive inputs survive staging cleanup"
          ("libHSrts-" `isInfixOf` show observed && "libCffi-" `isInfixOf` show observed)
        writeFile (scratch </> "qualified-windows-native-cbd.path") cbd
  , TestLabel "PE components always retain raw LLVM and omit the unused container" $ TestCase $ do
      let services = ["hs_free_stable_ptr","rtsSupportsBoundThreads","peer_export"]
      forM_ ["x86_64-w64-mingw32","x86_64-w64-windows-gnu","x86_64-pc-windows-msvc"] $ \target -> do
        assertEqual "PE never receives ELF unresolved-symbol exclusions" Nothing
          (nativeDeferredLinkArguments target services)
        assertEqual "ordinary PE components cannot use the ELF container recipe" Nothing
          (nativeDeferredLinkArguments target [])
  , TestLabel "existing ELF and Mach-O deferred exclusions remain exact" $ TestCase $ do
      let services = ["hs_free_stable_ptr","rtsSupportsBoundThreads","peer_export"]
      assertEqual "ELF exclusions apply only to the actual deferred symbols"
        (Just (concatMap (\symbol -> ["-Xlinker","--ignore-unresolved-symbol=" ++ symbol]) services))
        (nativeDeferredLinkArguments "x86_64-unknown-linux-gnu" services)
      forM_ ["aarch64-apple-darwin","aarch64-apple-macosx14.0.0"] $ \target ->
        assertEqual "Mach-O keeps its exact per-symbol undefined allowances"
          (Just (concatMap (\symbol -> ["-Xlinker","-U","-Xlinker",'_' : symbol]) services))
          (nativeDeferredLinkArguments target services)
  , TestLabel "PE companions require every ordinary native definition" $ TestCase $ do
      let ordinarySymbols = ["ordinary_provider","missing_ordinary"]
      forM_ ["x86_64-w64-mingw32","x86_64-w64-windows-gnu"] $ \target ->
        assertEqual "extraction roots cannot hide an absent ordinary provider"
          (concatMap (\symbol -> ["-Xlinker","--require-defined=" ++ symbol]) ordinarySymbols)
          (nativeRootArguments target ordinarySymbols)
      assertEqual "ELF roots are unchanged"
        (concatMap (\symbol -> ["-Xlinker","-u","-Xlinker",symbol]) ordinarySymbols)
        (nativeRootArguments "x86_64-unknown-linux-gnu" ordinarySymbols)
      assertEqual "Mach-O roots retain their original spelling"
        (concatMap (\symbol -> ["-Xlinker","-u","-Xlinker",'_' : symbol]) ordinarySymbols)
        (nativeRootArguments "aarch64-apple-darwin" ordinarySymbols)
  , TestLabel "selected tools survive a package cwd" $ TestCase $ do
      context <- installedContext "ghc" "ghc-pkg" "unused" [] Null
      identifier <- readProcess "ghc-pkg" ["field","ghc-internal","id","--simple-output"] ""
      unit <- case words identifier of [value] -> pure value; _ -> fail "expected one ghc-internal unit"
      withScratch $ \root -> withCurrentDirectory root $ do
        archives <- nativeSymbolArchives (installedPackageTool context) (installedLibdir context) root
          unit [] [("__hscore_bufsiz",True)]
        assertBool "original registered C archive remains selected" (not (null archives))
      ghc <- findExecutable "ghc" >>= maybe (fail "ghc missing") makeAbsolute
      pkg <- findExecutable "ghc-pkg" >>= maybe (fail "ghc-pkg missing") makeAbsolute
      assertEqual "stored selected compiler is absolute" ghc (installedGhc context)
      assertEqual "stored selected package tool is absolute" pkg (installedPackageTool context)
  , TestLabel "explicit separate versioned wrappers retain selection" $ TestCase $
      if Host.os == "mingw32" then pure () else withScratch $ \root -> do
        ghc <- findExecutable "ghc" >>= maybe (fail "ghc missing") makeAbsolute
        pkg <- findExecutable "ghc-pkg" >>= maybe (fail "ghc-pkg missing") makeAbsolute
        createDirectory (root </> "compiler")
        createDirectory (root </> "packages")
        let wrapper = root </> "compiler/wrapper"
            selected = root </> "compiler/ghc-9.14.1"
            packageTool = root </> "packages/ghc-pkg-9.14.1"
            wrong = root </> "packages/wrong-pkg"
        writeExecutable wrapper ("#!/bin/sh\nexec " ++ show ghc ++ " \"$@\"\n")
        createFileLink wrapper selected
        writeExecutable packageTool ("#!/bin/sh\nexec " ++ show pkg ++ " \"$@\"\n")
        writeExecutable wrong ("#!/bin/sh\nif [ \"$1\" = --version ]; then exec " ++ show pkg ++
          " \"$@\"; fi\nprintf '/different/global/database\\n'\n")
        context <- withCurrentDirectory root $
          installedContext "compiler/ghc-9.14.1" "packages/ghc-pkg-9.14.1" "unused" [] Null
        assertEqual "symlink selection is not dereferenced" selected (installedGhc context)
        assertEqual "separate package selection is retained" packageTool (installedPackageTool context)
        withCurrentDirectory (root </> "packages") $ do
          assertEqual "compiler remains callable after cwd change" "9.14.1\n" =<<
            readProcess (installedGhc context) ["--numeric-version"] ""
          _ <- readProcess (installedPackageTool context) ["--version"] ""
          identifier <- readProcess (installedPackageTool context)
            ["field","ghc-internal","id","--simple-output"] ""
          unit <- case words identifier of [value] -> pure value; _ -> fail "expected one ghc-internal unit"
          archives <- nativeSymbolArchives (installedPackageTool context) (installedLibdir context) root
            unit [] [("__hscore_bufsiz",True)]
          assertBool "separate versioned package tool selects original native archive" (not (null archives))
        rejected <- tryIOError (installedContext selected wrong "unused" [] Null)
        assertBool "mismatched selected pair is rejected" (isLeft rejected)
        assertBool "mismatch rejects the database identity, not executable lookup"
          (case rejected of Left failure -> "global databases differ" `isInfixOf` show failure; Right _ -> False)
        inheritedPath <- getEnv "PATH"
        withEnvironment [("PATH",root </> "packages" ++ [searchPathSeparator] ++ inheritedPath)] $
          bracket (lookupEnv "GHC_PKG")
            (maybe (unsetEnv "GHC_PKG") (setEnv "GHC_PKG")) $ \_ -> do
              unsetEnv "GHC_PKG"
              assertEqual "empty compiler sibling directory selects versioned PATH package tool"
                packageTool =<< selectedPackageTool selected Nothing
              rejectedExplicit <- tryIOError (selectedPackageTool selected (Just (root </> "missing-pkg")))
              assertBool "bad explicit package selection cannot fall back to compatible PATH"
                (isLeft rejectedExplicit)
              withEnvironment [("GHC_PKG",wrong)] $ do
                rejectedEnvironment <- tryIOError (selectedPackageTool selected Nothing)
                assertBool "bad environment pair cannot fall back to compatible PATH"
                  (case rejectedEnvironment of Left failure -> "global databases differ" `isInfixOf` show failure; Right _ -> False)
              let unversioned = root </> "packages/ghc-pkg"
              writeExecutable unversioned ("#!/bin/sh\nexec " ++ show pkg ++ " \"$@\"\n")
              removeFile packageTool
              withEnvironment [("PATH",root </> "packages")] $
                assertEqual "PATH may provide only the unversioned selected package tool"
                  unversioned =<< selectedPackageTool selected Nothing
              writeExecutable packageTool ("#!/bin/sh\nif [ \"$1\" = --version ]; then exec " ++ show pkg ++
                " \"$@\"; fi\nprintf '/different/global/database\\n'\n")
              rejectedDefault <- tryIOError (selectedPackageTool selected Nothing)
              assertBool "versioned PATH candidate must still match compiler database"
                (case rejectedDefault of Left failure -> "global databases differ" `isInfixOf` show failure; Right _ -> False)
        forM_ ["absent-tool", root </> "absent-tool"] $ \missing -> do
          badCompiler <- tryIOError (installedContext missing packageTool "unused" [] Null)
          badPackage <- tryIOError (installedContext selected missing "unused" [] Null)
          assertBool "invalid compiler does not fall back" (isLeft badCompiler)
          assertBool "invalid package tool does not fall back" (isLeft badPackage)
  , TestCase $ withScratch $ \root -> do
      let executableName = if Host.os == "mingw32" then "llvm-objcopy.exe" else "llvm-objcopy"
          adjacent = root </> "clang" </> executableName
          onPath = root </> "path" </> executableName
      createDirectory (root </> "clang")
      createDirectory (root </> "path")
      writeExecutable onPath "PATH objcopy"
      withEnvironment [("PATH",root </> "path")] $
        bracket (lookupEnv "THC_LLVM_OBJCOPY")
          (maybe (unsetEnv "THC_LLVM_OBJCOPY") (setEnv "THC_LLVM_OBJCOPY")) $ \_ -> do
            unsetEnv "THC_LLVM_OBJCOPY"
            assertEqual "wrapped clang can use PATH objcopy" onPath =<< tool "THC_LLVM_OBJCOPY" adjacent
            writeExecutable adjacent "adjacent objcopy"
            assertEqual "adjacent objcopy wins over PATH" adjacent =<< tool "THC_LLVM_OBJCOPY" adjacent
            withEnvironment [("THC_LLVM_OBJCOPY",onPath)] $
              assertEqual "explicit override wins over adjacent" onPath =<< tool "THC_LLVM_OBJCOPY" adjacent
            withEnvironment [("THC_LLVM_OBJCOPY","llvm-objcopy")] $
              assertEqual "named explicit override resolves through PATH" onPath =<< tool "THC_LLVM_OBJCOPY" adjacent
            forM_ [root </> "missing", "missing-objcopy"] $ \missing ->
              withEnvironment [("THC_LLVM_OBJCOPY",missing)] $ do
                result <- tryIOError (tool "THC_LLVM_OBJCOPY" adjacent)
                assertBool "bad explicit override must fail, never fall back" (isLeft result)
            removeFile onPath
            removeFile adjacent
            result <- tryIOError (tool "THC_LLVM_OBJCOPY" adjacent)
            assertBool "missing implicit objcopy fails when acquisition needs it" (isLeft result)
  , TestCase $ forM_ ["aarch64-apple-darwin", "arm64-apple-macosx13.3.0"] $ \target -> do
      assertEqual "Darwin defined export name" "thc_symbol_probe" (nativeIrSymbol target "_thc_symbol_probe")
      assertEqual "Darwin libc linker prefix" "access" (nativeIrSymbol target "_access")
      assertEqual "Darwin preserves genuine GMP underscores" "__gmpn_add" (nativeIrSymbol target "___gmpn_add")
      assertEqual "Darwin managed symbol remains recognizable" "hs_free_stable_ptr" (nativeIrSymbol target "_hs_free_stable_ptr")
  , TestLabel "native archive roots preserve LLVM function versus data declarations" $ TestCase $ do
      let candidates = ["same_label", "__gmpn_add_1", "quoted.label", "asm_label$special", "defined", "alias", "missing"]
          source declaration = unlines
            [declaration, "declare i64 @__gmpn_add_1(ptr, ptr, i64, i64)",
             "declare void @\"quoted\\2Elabel\"()",
             "declare void @\"\\01_asm_label$special\"()",
             "define void @defined() { ret void }",
             "@alias = alias void (), ptr @defined",
             "; declare void @missing()"]
      assertEqual "an archive T symbol is callable only when LLVM declares a function"
        ["same_label", "__gmpn_add_1", "quoted.label", "asm_label$special"]
        (nativeFunctionExternals "arm64-apple-macosx15.0.0" candidates
          (source "declare i64 @same_label(ptr, i64)"))
      assertEqual "the same T symbol cannot satisfy an LLVM data/address reference"
        ["__gmpn_add_1", "quoted.label", "asm_label$special"]
        (nativeFunctionExternals "arm64-apple-macosx15.0.0" candidates
          (source "@same_label = external constant i8"))
      assertEqual "quoted return types cannot turn data references into function roots"
        ["actual"]
        (nativeFunctionExternals "arm64-apple-macosx15.0.0" ["same_label", "actual"]
          "declare %\"type@same_label(\" @actual()\n@same_label = external constant i8")
      assertEqual "asm labels on ELF retain their literal leading underscore"
        ["_asm_label$special"]
        (nativeFunctionExternals "x86_64-unknown-linux-gnu" ["_asm_label$special"]
          "declare void @\"\\01_asm_label$special\"()")
  , TestCase $ do
      assertEqual "ELF symbol remains unchanged" "__gmpn_add" (nativeIrSymbol "aarch64-linux-gnu" "__gmpn_add")
      assertEqual "ELF ordinary symbol remains unchanged" "access" (nativeIrSymbol "x86_64-linux-gnu" "access")
  , TestCase $ do
      compiler <- findExecutable "ghc" >>= maybe (fail "GHC missing") pure
      libdir <- readProcess compiler ["--print-libdir"] "" >>= \output -> case lines output of
        [directory] -> pure directory
        _ -> fail "GHC did not report one libdir"
      ghcPkg <- findExecutable "ghc-pkg" >>= maybe (fail "ghc-pkg missing") makeAbsolute
      let unitId package = readProcess ghcPkg ["field",package,"id","--simple-output"] "" >>= \output ->
            case words output of
              [identifier] -> pure identifier
              _ -> fail ("GHC did not report one installed unit for " ++ package)
      root <- getCurrentDirectory
      compilerUnit <- unitId "ghc"
      if compilerUnit /= "ghc-9.14.1-inplace"
        then putStrLn ("SKIP compiler native-state archive regression: requires ghc-9.14.1-inplace; selected " ++ compilerUnit)
        else do
          compilerArchives <- nativeSymbolArchives ghcPkg libdir root compilerUnit []
            [("keepCAFsForGHCi",True),("setHeapSize",True),("enableTimingStats",True),
             ("ghc_unique_counter64",False),("ghc_unique_inc",False)]
          assertEqual "compiler process-state objects must not load beside context-owned RTS services"
            [] compilerArchives
      internalUnit <- unitId "ghc-internal"
      ordinaryArchives <- nativeSymbolArchives ghcPkg libdir root internalUnit [] [("__hscore_bufsiz",True)]
      assertBool "ordinary registered C objects remain native providers" (not (null ordinaryArchives))
  , TestCase $ do
      let rep kind prim = object ["kind" .= (kind::String), "primReps" .= (prim::[String]), "evaluated" .= False]
          state = rep "void" []
          result = object ["kind" .= ("unknown"::String), "primReps" .= (["IntRep"]::[String]),
            "evaluated" .= False, "aggregate" .= ("unboxed-tuple"::String),
            "components" .= [set "evaluated" (Bool True) state, set "evaluated" (Bool True) (rep "long" ["IntRep"])]]
          call = object ["schema" .= (1::Int), "target" .= object ["kind" .= ("static"::String),
            "unit" .= ("fixture-unit"::String), "symbol" .= ("strlen"::String), "isFunction" .= True],
            "convention" .= ("ccall"::String), "safety" .= ("unsafe"::String), "arity" .= (2::Int),
            "suppliedArity" .= (2::Int), "argumentReps" .= [rep "address" ["AddrRep"], state], "resultRep" .= result]
          original descriptor = object ["unit" .= ("fixture-unit"::String), "module" .= ("Fixture"::String),
            "bindings" .= [object ["foreignCall" .= descriptor]]]
      assertEqual "genuine FCallId needs no vanished source declaration"
        (Right [("strlen","ccall","unsafe",["AddrRep"],"IntRep")])
        (installedNativeSignatures "fixture-unit" (original call))
      assertEqual "inline calls retain their original owner" (Right [])
        (installedNativeSignatures "different-unit" (original call))
      assertEqual "erased unlifted pointers do not invent byte-array mutability" (Right [])
        (installedNativeSignatures "fixture-unit" (original (set "argumentReps"
          (toJSON [rep "object" ["BoxedRep (Just Unlifted)"],state]) call)))
      let array = set "schema" (toJSON (2::Int)) $ set "argumentTypes" (toJSON [String "ByteArray#",Null]) $
            set "argumentReps" (toJSON [rep "object" ["BoxedRep (Just Unlifted)"],state]) call
      assertEqual "actual nominal array carrier survives Core erasure"
        (Right [("strlen","ccall","unsafe",["ByteArray#"],"IntRep")])
        (installedNativeSignatures "fixture-unit" (original array))
      let interruptible = changeEmitted "safety" "interruptible" $
            entry "__hscore_open" "ccall" ["AddrRep","Int32Rep","Word32Rep","void"] ["void","Int32Rep"]
      assertEqual "retained interruptible import does not reject unrelated ordinary adapters"
        (Right [("identity","ccall","unsafe",["WordRep"],"WordRep")])
        (installedNativeSignatures "fixture-unit" (moduleWith [ordinary,interruptible]))
      assertEqual "inlined CAPI calls retain their actual stub translation unit" (Right 2)
        (nativeCapiSource "wrapper" ["", "int wrapper_extra(void) { return 1; }", "int wrapper(void) { return 2; }"])
      assertBool "missing retained CAPI source stays explicit" (isLeft (nativeCapiSource "wrapper" [""]))
  , TestCase $ do
      let marker = "thc_javascript_v1_2878293d3e78"
          scalar = object ["kind" .= ("long"::String), "primReps" .= (["IntRep"]::[String]), "evaluated" .= True]
          state = object ["kind" .= ("void"::String), "primReps" .= ([]::[String]), "evaluated" .= True]
          call = object ["schema" .= (1::Int), "intrinsic" .= ("javascript-v1"::String),
            "javascriptSource" .= ("(x)=>x"::String), "target" .= object
              ["kind" .= ("static"::String), "unit" .= ("fixture-unit"::String),
               "symbol" .= (marker::String), "isFunction" .= True],
            "convention" .= ("ccall"::String), "safety" .= ("unsafe"::String),
            "arity" .= (2::Int), "suppliedArity" .= (2::Int), "argumentReps" .= [scalar,state],
            "resultRep" .= object ["aggregate" .= ("unboxed-tuple"::String), "components" .= [state,scalar]]]
          javascript = entry marker "ccall" ["IntRep","void"] ["void","IntRep"]
          withCall descriptor = set "bindings" (toJSON [object ["foreignCall" .= descriptor]])
            . changeProof "expectedCalls" (toJSON [descriptor])
          mixed descriptor = withCall descriptor (moduleWith [ordinary,javascript])
          native = [("identity","ccall","unsafe",["WordRep"],"WordRep")]
          markerSignature = (marker,"ccall","unsafe",["IntRep"],"IntRep")
          installed descriptor = object ["unit" .= ("fixture-unit"::String),
            "bindings" .= [object ["foreignCall" .= descriptor]]]
      assertEqual "mixed modules retain their ordinary native import" (Right native)
        (nativeSignatures "fixture-unit" [mixed call])
      assertEqual "retained installed modules use the same intrinsic classification" (Right native)
        (installedNativeSignatures "fixture-unit" (mixed call))
      assertEqual "installed FCallId fallback does not create JavaScript native adapters" (Right [])
        (installedNativeSignatures "fixture-unit" (installed call))
      forM_ [set "intrinsic" Null call, set "javascriptSource" "different" call,
             change "target" "unit" "other-unit" call] $ \unproved ->
        assertEqual "only the exact owned descriptor excludes the reserved C marker"
          (Right (native ++ [markerSignature])) (nativeSignatures "fixture-unit" [mixed unproved])
      assertEqual "an encoded C symbol alone remains a native call" (Right [markerSignature])
        (installedNativeSignatures "fixture-unit" (installed (set "intrinsic" Null call)))
      assertBool "intrinsic classification cannot hide malformed retained import proof" (isLeft
        (nativeSignatures "fixture-unit" [withCall call (moduleWith [set "isFunction" (Bool False) javascript])]))
  , TestLabel "C finalizer declaration and linked definition ABI" $ TestCase $ do
      let named modName name args = object ["kind" .= ("tycon"::String), "name" .= object
            ["unit" .= ("ghc-internal"::String), "module" .= (modName::String),
             "occurrence" .= (name::String), "namespace" .= ("type"::String)], "arguments" .= (args::[Value])]
          unit = named "GHC.Internal.Tuple" "Unit" []
          pointer = named "GHC.Internal.Ptr" "Ptr" [unit]
          integer = named "GHC.Internal.Int" "Int32" []
          io result = named "GHC.Internal.Types" "IO" [result]
          function argument result = object ["kind" .= ("function"::String),
            "multiplicity" .= named "GHC.Internal.Types" "Many" [], "argument" .= argument, "result" .= result]
          ty argument result = named "GHC.Internal.Ptr" "FunPtr" [function argument result]
          address normalized = object ["binder" .= object ["unit" .= ("fixture-unit"::String),
            "module" .= ("Fixture"::String), "occurrence" .= ("cleanup"::String), "namespace" .= ("value"::String)],
            "symbol" .= ("cleanup"::String), "header" .= Null, "isFunction" .= True, "convention" .= ("capi"::String),
            "declaredType" .= ty pointer (io unit), "normalizedType" .= normalized,
            "normalizationRole" .= ("representational"::String),
            "callback" .= object ["arguments" .= (["AddrRep"]::[String]), "result" .= ("void"::String)]]
          module' addresses = changeProof "addresses" (toJSON addresses) (changeProof "schema" (toJSON (2::Int)) (moduleWith []))
          original = address (ty pointer (io unit))
      assertEqual "actual typed address creates a distinct retained adapter" (Right [("cleanup","ccall","unsafe",["AddrRep"],"void")])
        (nativeSignatures "fixture-unit" [module' [original]])
      let environmentType = ty pointer (function pointer (io unit))
          environment = set "callback" (object ["arguments" .= (["AddrRep","AddrRep"]::[String]), "result" .= ("void"::String)])
            (address environmentType)
      assertEqual "environment adapter retains its declared two-pointer ABI"
        (Right [("cleanup","ccall","unsafe",["AddrRep","AddrRep"],"void")])
        (nativeSignatures "fixture-unit" [module' [environment]])
      assertBool "one-pointer type cannot certify a two-pointer callback tag" (isLeft
        (nativeSignatures "fixture-unit" [module' [set "normalizedType" (ty pointer (io unit)) environment]]))
      let one = "define void @cleanup(ptr noundef %object) {\n ret void\n}\n"
          two = "define dso_local void @cleanup(ptr noundef %env, ptr nocapture %object) {\n ret void\n}\n"
      assertBool "exact one-pointer definition" (nativeFinalizerDefinition "cleanup" ["AddrRep"] one)
      assertBool "exact environment definition" (nativeFinalizerDefinition "cleanup" ["AddrRep","AddrRep"] two)
      assertBool "linked definition arity cannot replace metadata" (not (nativeFinalizerDefinition "cleanup" ["AddrRep"] two))
      forM_ [one, "define void @cleanup(ptr %env, i64 %object) {\n ret void\n}\n",
          "define i32 @cleanup(ptr %env, ptr %object) {\n ret i32 0\n}\n",
          "define void @cleanup(ptr %env, ...) {\n ret void\n}\n", two ++ two] $ \bad ->
        assertBool "wrong linked C ABI cannot acquire environment authority"
          (not (nativeFinalizerDefinition "cleanup" ["AddrRep","AddrRep"] bad))
      assertEqual "address metadata does not manufacture call inventory" (Just (toJSON ([]::[Value])))
        (lookupField "staticForeignImports" (module' [original]) >>= lookupField "expectedCalls")
      forM_ [ty integer (io unit), ty pointer (io integer), ty pointer unit,
          ty pointer (function pointer (io unit)), ty (named "Other" "Ptr" [unit]) (io unit),
          named "Other" "FunPtr" [function pointer (io unit)]] $ \bad ->
        assertBool "callback tag cannot replace normalized signature" (isLeft (nativeFinalizers "fixture-unit" [module' [address bad]]))
      assertBool "duplicate binder cannot prove two labels" (isLeft (nativeFinalizers "fixture-unit"
        [module' [original, set "symbol" "other" original]]))
      assertEqual "inert labels are not granted executable adapters" (Right [])
        (nativeFinalizers "fixture-unit" [module' [set "callback" Null (address integer)]])
  , TestCase $ do
      let caller = "define i64 @entry(ptr %0) {\n  %1 = call i64 @result(ptr %0)\n  ret i64 %1\n}\n"
          callee = "define i32 @result(ptr nocapture noundef readonly %0) {\n"
          body = "  %2 = getelementptr inbounds nuw i8, ptr %0, i64 12\n  %3 = load i32, ptr %2, align 4, !tbaa !117\n  ret i32 %3\n}\n"
          bridge suffix = nativeArgumentBridge "x86_64-unknown-linux-gnu" "result" "entry" (caller ++ callee ++ suffix)
      case bridge body of
        Just (declaration,generated,witness) -> do
          assertEqual "actual C result stays i32" "declare i32 @result(ptr)" declaration
          assertBool "explicit zero extension of observed leaf load" ("zext i32 %r to i64" `isInfixOf` generated)
          assertBool "retain actual definition not just its signature" ("ret i32 %3" `elem` witness)
        Nothing -> assertFailure "missing constrained native return bridge"
      forM_ ["  ret i32 7\n}\n", "  %1 = call i32 @other(ptr %0)\n  ret i32 %1\n}\n",
          "  %1 = load volatile i32, ptr %0\n  ret i32 %1\n}\n"] $ \other ->
        assertEqual "signature alone cannot establish excess return bits" Nothing (bridge other)
      assertEqual "no return adaptation on other targets" Nothing
        (nativeArgumentBridge "aarch64-unknown-linux-gnu" "result" "entry" (caller ++ callee ++ body))
  , TestCase $ do
      let caller = "define i64 @entry(ptr %0) {\n  %1 = call i64 @result(ptr %0)\n  ret i64 %1\n}\n"
          callee = "define i32 @result(ptr nocapture noundef readonly %0) {\n"
          load pointer = "  %3 = load i64, ptr " ++ pointer ++ ", align 8, !tbaa !31\n"
          narrowed = "  %4 = trunc i64 %3 to i32\n  ret i32 %4\n}\n"
          bridge body = nativeArgumentBridge "x86_64-unknown-linux-gnu" "result" "entry" (caller ++ callee ++ body)
      forM_ [load "%0" ++ narrowed,
          "  %2 = getelementptr inbounds nuw i8, ptr %0, i64 8\n" ++ load "%2" ++ narrowed,
          "  %2 = getelementptr inbounds nuw i8, ptr %0, i64 16\n" ++ load "%2" ++ narrowed] $ \body ->
        case bridge body of
          Just (_,generated,witness) -> do
            assertBool "mark leaf explicitly zero extends its truncated value" ("zext i32 %r to i64" `isInfixOf` generated)
            assertBool "mark truncation remains in the witness" ("%4 = trunc i64 %3 to i32" `elem` witness)
          Nothing -> assertFailure "missing constrained mark return bridge"
      forM_ [load "%0" ++ "  %4 = trunc i64 %other to i32\n  ret i32 %4\n}\n",
          load "%0" ++ "  %4 = trunc i64 %3 to i16\n  ret i32 %4\n}\n",
          "  %2 = getelementptr inbounds i8, ptr %0, i64 -8\n" ++ load "%2" ++ narrowed,
          "  %2 = getelementptr inbounds i8, ptr %0, i64 8\n" ++ load "%other" ++ narrowed,
          "  store i64 0, ptr %0\n" ++ load "%0" ++ narrowed,
          "  %3 = load volatile i64, ptr %0, align 8\n" ++ narrowed] $ \body ->
        assertEqual "only the exact side-effect-free load/trunc/return shape adapts" Nothing (bridge body)
  , TestCase $ do
      let piece root name hash target = object ["root" .= (root :: String),
            "object" .= (root ++ "/" ++ name),"objectSha256" .= (hash :: String),
            "target" .= (target :: String)]
          first = piece "/selected" "a.o" "first" "target"
          second = piece "/selected" "b.o" "second" "target"
          sibling = piece "/sibling" "a.o" "different" "target"
      assertEqual "archive content selects only owned products" (Right [first,second])
        (selectNativePieces True [("a.o","first"),("b.o","second")] [sibling,second,first])
      assertEqual "mixed archive keeps captured C members, never native Haskell objects" (Right [first])
        (selectNativePieces False [("a.o","first"),("Owner.o","haskell")] [sibling,first])
      assertEqual "an uncaptured Haskell archive is not a native provider" (Right [])
        (selectNativePieces False [("Owner.o","haskell")] [first])
      assertEqual "unrecorded required product is an explicit acquisition miss"
        (Right (NativePieceSelection ["b.o"] [first]))
        (selectNativePiecesAvailable True [("a.o","first"),("b.o","second")] [first])
      assertBool "declared product mismatch is invalid, not an acquisition miss" (isLeft
        (selectNativePiecesAvailable True [("a.o","other")] [first]))
      assertBool "missing product cannot hide another ambiguous declaration" (isLeft
        (selectNativePiecesAvailable True [("missing.o","absent"),("a.o","first")]
          [first,piece "/sibling" "a.o" "first" "other"]))
      assertBool "matching basename cannot bless different native object" (isLeft
        (selectNativePieces True [("a.o","other")] [first]))
      assertBool "unrecorded member is not silently omitted" (isLeft
        (selectNativePieces True [("a.o","first"),("b.o","second")] [first]))
      assertBool "different recipes for one native object remain ambiguous" (isLeft
        (selectNativePieces True [("a.o","first")] [first,piece "/sibling" "a.o" "first" "other"]))
      assertBool "duplicate archive members cannot expand authority" (isLeft
        (selectNativePieces True [("a.o","first"),("a.o","first")] [first]))
      forM_ ["../a.o","/a.o",".","","-N","@response","a b.o","a\nb.o"] $ \name ->
        assertBool "archive member paths cannot escape selection" (isLeft (selectNativePieces True [(name,"first")] [first]))
  , TestCase $ do
      let source = unlines ["define i64 @caller(ptr %0, i64 %1, i64 %2) {",
            "  %3 = call i64 @callee(ptr %0, i64 %1, i64 %2)", "  ret i64 %3", "}",
            "define i64 @callee(ptr nocapture readonly %0, i8 zeroext %1, i32 %2) {"]
      case nativeArgumentBridge "x86_64-unknown-linux-gnu" "callee" "caller" source of
        Nothing -> assertFailure "missing native integer argument bridge"
        Just (declaration,body,witnesses) -> do
          assertEqual "exact C definition declaration" "declare i64 @callee(ptr, i8 zeroext, i32)" declaration
          assertBool "argument low bits become explicit" ("trunc i64 %a1 to i8" `isInfixOf` body && "trunc i64 %a2 to i32" `isInfixOf` body)
          assertBool "return ABI does not change" ("ret i64 %r" `isInfixOf` body)
          assertEqual "exact original signature witnesses" (filter ("define " `isPrefixOf`) (lines source)) witnesses
      mapM_ (\target -> assertEqual "target-specific ABI lowering" Nothing
        (nativeArgumentBridge target "callee" "caller" source)) ["aarch64-unknown-linux-gnu","x86_64-pc-windows-msvc"]
  , TestCase $ do
      let caller = "define i64 @caller(ptr %0, i64 %1) {\n  %2 = call i64 @callee(ptr %0, i64 %1)\n  ret i64 %2\n}\n"
          bridge callee = nativeArgumentBridge "x86_64-unknown-linux-gnu" "callee" "caller" (caller ++ callee)
      mapM_ (\callee -> assertEqual "unsupported ABI is never guessed" Nothing (bridge callee))
        ["define i32 @callee(ptr %0, i8 %1) {", "define i64 @callee(i64 %0, i8 %1) {",
         "define fastcc i64 @callee(ptr %0, i8 %1) {", "define i64 @callee(ptr %0, i8 %1, ...) {",
         "define i64 @callee(ptr byval(i64) %0, i8 %1) {", "declare i64 @callee(ptr, i8)",
         "define i64 @callee(ptr %0, float %1) {", "define i64 @callee(ptr %0, i64 %1) {"]
  , TestCase $ do
      let source = "define void @caller(i32 %0, i64 %1) {\n  call void @callee(i32 %0, i64 %1)\n  ret void\n}\ndefine void @callee(i16 signext %0, i16 zeroext %1) {"
      case nativeArgumentBridge "x86_64-unknown-linux-gnu" "callee" "caller" source of
        Nothing -> assertFailure "missing 16-bit native bridge"
        Just (declaration,body,_) -> do
          assertEqual "native extension attributes survive" "declare void @callee(i16 signext, i16 zeroext)" declaration
          assertBool "32-bit argument truncates" ("trunc i32 %a0 to i16" `isInfixOf` body)
          assertBool "64-bit argument truncates" ("trunc i64 %a1 to i16" `isInfixOf` body)
          assertBool "void remains void" ("  ret void\n" `isInfixOf` body)
  , TestCase $ do
      let source = "define i64 @caller(ptr %0, i64 %1) {\n  %2 = call i64 @callee(ptr %0, i64 %1)\n  ret i64 %2\n}\ndefine i64 @callee(ptr dereferenceable(8) %0, i8 zeroext %1) {"
      assertBool "nested ordinary attributes preserve parameter boundaries"
        (case nativeArgumentBridge "x86_64-unknown-linux-gnu" "callee" "caller" source of Just _ -> True; _ -> False)
      let candidate body = "define i64 @caller(i64 %0) {\n" ++ body ++
            "}\ndefine i64 @callee(i8 zeroext %0) {"
      mapM_ (\body -> assertEqual "do not replace unrelated or effectful adapter work" Nothing
        (nativeArgumentBridge "x86_64-unknown-linux-gnu" "callee" "caller" (candidate body)))
        ["  %1 = call i64 @different(i64 %0)\n  ret i64 %1\n",
         "  store volatile i8 1, ptr @counter\n  %1 = call i64 @callee(i64 %0)\n  ret i64 %1\n",
         "  %1 = call i64 @callee(i64 7)\n  ret i64 %1\n",
         "  %1 = call i64 @callee(i64 %0)\n  ret i64 0\n",
         "  %1 = call fastcc i64 @callee(i64 %0)\n  ret i64 %1\n"]
      let swapped = "define i64 @caller(i64 %0, i64 %1) {\n  %2 = call i64 @callee(i64 %1, i64 %0)\n  ret i64 %2\n}\ndefine i64 @callee(i8 %0, i8 %1) {"
      assertEqual "original argument ordering is required" Nothing
        (nativeArgumentBridge "x86_64-unknown-linux-gnu" "callee" "caller" swapped)
  , TestCase $ assertEqual "CAPI values, byte arrays, pointers and void keep their emitted ABI"
      (Right [("read_bytes","capi","unsafe",["ByteArray#","IntRep","Word64Rep"],"Word64Rep"),
              ("write_state","ccall","unsafe",["MutableByteArray#","AddrRep"],"void")])
      (nativeSignatures "fixture-unit" [moduleWith
        [entry "write_state" "ccall" ["MutableByteArray#","AddrRep","void"] ["void"],
         entry "read_bytes" "capi" ["ByteArray#","IntRep","Word64Rep","void"] ["void","Word64Rep"]]])
  , TestCase $ assertEqual "same-unit inlining contributes no invented declarations"
      (nativeSignatures "fixture-unit" [moduleWith [ordinary]])
      (nativeSignatures "fixture-unit" [moduleWith [ordinary],object ["unit" .= ("fixture-unit"::String)]])
  , TestCase $ do
      let blocked = changeEmitted "safety" "interruptible" (entry "blocked" "ccall" ["AddrRep","void"] ["void","WordRep"])
          original = moduleWith [ordinary,blocked]
      case archiveNativeModule "fixture-unit" original of
        Left message -> assertFailure message
        Right archived -> do
          assertEqual "original typed declaration inventory is not rewritten" (lookupField "staticForeignImports" original)
            (lookupField "staticForeignImports" archived)
          assertBool "unsupported interruptible pointer retained explicitly" (lookupField "packageNativeArchive" archived /= Nothing)
          assertEqual "supported declaration in the same module still receives its adapter"
            (nativeSignatures "fixture-unit" [moduleWith [ordinary]]) (nativeSignatures "fixture-unit" [archived])
      mapM_ (\bad -> assertBool "malformed companion cannot become unsupported" (isLeft (archiveNativeModule "fixture-unit" bad)))
        [changeProof "expectedCalls" (toJSON [object []]) original,
         changeProof "profile" "invented" original,
         moduleWith [blocked,changeEmitted "safety" "invented" ordinary],
         moduleWith [blocked,set "normalizedType" (object []) ordinary],
         moduleWith [blocked,entry "bad" "ccall" ["WordRep"] ["void","WordRep"]]]
  , TestCase $ do
      let unclassified reason = set "staticForeignImports" (object
            ["schema" .= (1::Int),"scope" .= ("retained-static-import-products"::String),
             "execution" .= ("not-linked"::String),"profile" .= ("ghc-9.14.1-thc-only-static-c-imports-v1"::String),
             "unit" .= ("fixture-unit"::String),"module" .= ("Fixture"::String),
             "status" .= ("unclassified"::String),"reason" .= (reason::String)]) (moduleWith [])
      assertBool "known non-static declaration is honestly archived"
        (not (isLeft (archiveNativeModule "fixture-unit" (unclassified "non-static-c-import-declaration"))))
      mapM_ (\reason -> assertBool "unknown pipeline/probe failures remain fatal"
        (isLeft (archiveNativeModule "fixture-unit" (unclassified reason))))
        ["unclassified-plugin-or-hook-pipeline","stock-import-emitter-did-not-complete-cleanly","invented"]
  , TestCase $ mapM_ (\value -> assertBool "unsupported native boundary rejected"
      (isLeft (nativeSignatures "fixture-unit" [moduleWith [value]])))
      [ entry "wrong" "ccall" ["WordRep"] ["void","WordRep"]
      , entry "wrong" "ccall" ["BoxedRep (Just Unlifted)","void"] ["void","WordRep"]
      , entry "wrong" "ccall" ["void","WordRep","void"] ["void","WordRep"]
      , entry "wrong" "ccall" ["void"] ["void","MutableByteArray#"]
      , entry "wrong" "ccall" ["void"] ["WordRep"]
      , entry "wrong" "stdcall" ["void"] ["void","WordRep"]
      , changeEmitted "safety" "interruptible" ordinary
      , changeEmitted "safety" "safe" (entry "wrong" "ccall" [] ["void","WordRep"])
      , changeEmitted "unit" "other-unit" ordinary
      , entry "bad-name" "ccall" ["void"] ["void"]
      ]
  , TestCase $ assertEqual "safe scalar import retains safe metadata"
      (Right [("identity","ccall","safe",["WordRep"],"WordRep")])
      (nativeSignatures "fixture-unit" [moduleWith [changeEmitted "safety" "safe" ordinary]])
  , TestCase $ do
      forM_ ["AddrRep","ByteArray#","MutableByteArray#"] $ \rep ->
        assertEqual "temporary safe-as-unsafe retains pointer metadata"
          (Right [("pointer","ccall","safe",[rep],"AddrRep")])
          (nativeSignatures "fixture-unit" [moduleWith
            [changeEmitted "safety" "safe" (entry "pointer" "ccall" [rep,"void"] ["void","AddrRep"])]])
      assertEqual "same C ABI can retain separate safe and unsafe adapters"
        (Right [("identity","ccall","safe",["WordRep"],"WordRep"),
                ("identity","ccall","unsafe",["WordRep"],"WordRep")])
        (nativeSignatures "fixture-unit" [moduleWith [ordinary,changeEmitted "safety" "safe" ordinary]])
  , TestCase $ assertBool "conflicting emitted ABIs rejected" $ isLeft $ nativeSignatures "fixture-unit"
      [moduleWith [ordinary,entry "identity" "ccall" ["IntRep","void"] ["void","IntRep"]]]
  , TestCase $ do
      forM_ [(["BoxedRep (Just Unlifted)","void"],["void","Word64Rep"]),
             (["BoxedRep (Just Lifted)","void"],["void"]),
             (["void"],["void","BoxedRep (Just Unlifted)"]),
             (["void"],["void","BoxedRep (Just Lifted)"])] $ \(arguments,result) -> do
        let boxed = entry "gc_object" "ccall" arguments result
            original = moduleWith [ordinary,boxed]
        case archiveNativeModule "fixture-unit" original of
          Left message -> assertFailure message
          Right archived -> do
            assertEqual "GC import proof survives archival unchanged"
              (lookupField "staticForeignImports" original) (lookupField "staticForeignImports" archived)
            assertEqual "GC carrier is explicitly excluded from native adapters"
              (Just (toJSON [maybe Null id (lookupField "emitted" boxed)]))
              (lookupField "packageNativeArchive" archived >>= lookupField "unsupportedImports")
            assertEqual "ordinary scalar adapter remains available beside GC imports"
              (Right [("identity","ccall","unsafe",["WordRep"],"WordRep")])
              (nativeSignatures "fixture-unit" [archived])
        assertBool "unarchived GC calls cannot generate native adapters"
          (isLeft (nativeSignatures "fixture-unit" [original]))
      assertBool "unknown levity is not concrete archival provenance"
        (isLeft (archiveNativeModule "fixture-unit" (moduleWith
          [entry "gc_object" "ccall" ["BoxedRep Nothing","void"] ["void"]])))
  , TestCase $ do
      let narrow = entry "width" "ccall" ["IntRep","void"] ["void","Int32Rep"]
          wide = entry "width" "ccall" ["IntRep","void"] ["void","Int64Rep"]
          named name entries = set "module" (toJSON (name::String)) $
            changeProof "module" (toJSON name) $ moduleWith (map (change "binder" "module" (toJSON name)) entries)
          originals = [named "Narrow" [ordinary,narrow],named "Wide" [wide]]
      assertBool "incompatible original C result widths remain an execution rejection"
        (isLeft (nativeSignatures "fixture-unit" originals))
      case archiveNativeModules "fixture-unit" originals of
        Left message -> assertFailure message
        Right archived -> do
          assertEqual "every original typed declaration survives unchanged"
            (map (lookupField "staticForeignImports") originals) (map (lookupField "staticForeignImports") archived)
          assertEqual "unrelated import in the conflicting module still gets an adapter"
            (nativeSignatures "fixture-unit" [moduleWith [ordinary]]) (nativeSignatures "fixture-unit" archived)
          forM_ archived $ \value -> do
            let marker = lookupField "packageNativeArchive" value
            assertEqual "original cross-module conflicting witnesses retained"
              (Just (toJSON [maybe Null id (lookupField "emitted" narrow),maybe Null id (lookupField "emitted" wide)]))
              (marker >>= lookupField "conflictingImports")
            assertEqual "conflicts are not disguised as unclassified declarations"
              (Just Null) (marker >>= lookupField "unclassifiedReason")
      assertBool "a malformed companion declaration is still fatal"
        (isLeft (archiveNativeModules "fixture-unit" [named "Narrow" [ordinary,narrow],named "Wide" [set "normalizedType" (object []) wide]]))
      let interruptible = changeEmitted "safety" "interruptible" narrow
          blocked = named "Interruptible" [interruptible]
      case archiveNativeModules "fixture-unit" (originals ++ [blocked]) of
        Left message -> assertFailure message
        Right archived -> do
          let retained = last archived
              marker = lookupField "packageNativeArchive" retained
          assertEqual "interruptible-only module retains its original declarations"
            (lookupField "staticForeignImports" blocked) (lookupField "staticForeignImports" retained)
          assertEqual "unsupported local safety does not acquire other modules' conflict witnesses"
            Nothing (marker >>= lookupField "conflictingImports")
          assertEqual "interruptible declaration remains explicitly excluded"
            (Just (toJSON [maybe Null id (lookupField "emitted" interruptible)]))
            (marker >>= lookupField "unsupportedImports")
          assertEqual "unrelated supported import retains its adapter across all three modules"
            (nativeSignatures "fixture-unit" [moduleWith [ordinary]]) (nativeSignatures "fixture-unit" archived)
  , TestCase $ assertEqual "one C pointer ABI retains each distinct Core carrier adapter"
      (Right [("read_bytes","ccall","unsafe",["AddrRep","WordRep"],"WordRep"),
              ("read_bytes","ccall","unsafe",["ByteArray#","WordRep"],"WordRep")])
      (nativeSignatures "fixture-unit" [moduleWith
        [entry "read_bytes" "ccall" ["ByteArray#","WordRep","void"] ["void","WordRep"],
         entry "read_bytes" "ccall" ["AddrRep","WordRep","void"] ["void","WordRep"]]])
  , TestCase $ assertBool "erased byte-array mutability cannot choose a writable policy" $ isLeft $
      nativeSignatures "fixture-unit" [moduleWith
        [entry "read_bytes" "ccall" ["ByteArray#","void"] ["void","WordRep"],
         entry "read_bytes" "ccall" ["MutableByteArray#","void"] ["void","WordRep"]]]
  , TestCase $ do
      let signature = ("identity_pointer","ccall","unsafe",["AddrRep"],"AddrRep")
      assertEqual "opaque pointer return preserves the emitted address ABI" (Right [signature])
        (nativeSignatures "fixture-unit" [moduleWith [entry "identity_pointer" "ccall" ["AddrRep","void"] ["void","AddrRep"]]])
      assertEqual "pointer adapter uses pointers, not integer addresses"
        (Right "extern void * identity_pointer(void *);\nvoid * thc_native_pointer_0(void * a0) { return identity_pointer(a0); }\n")
        (nativeWrapperSource [(signature,"thc_native_pointer_0",Nothing)])
      assertEqual "function addresses do not manufacture an invocation signature"
        (Right "extern void identity_pointer();\nvoid * thc_address_0(void) { return (void *) &identity_pointer; }\n")
        (nativeAddressSource [("identity_pointer",True,"thc_address_0")])
  , TestCase $ mapM_ (\value -> assertBool "retained proof mismatch rejected"
      (isLeft (nativeSignatures "fixture-unit" [value])))
      [ set "unit" "other-unit" (moduleWith [ordinary])
      , changeProof "status" "unclassified" (moduleWith [ordinary])
      , changeProof "expectedCalls" (toJSON [object ["unexpected" .= True]]) (moduleWith [ordinary])
      , set "foreign" (set "files" (toJSON [object []]) emptyArchive) (moduleWith [ordinary])
      , set "staticForeignImportStubs" (object []) (moduleWith [ordinary])
      ]
  , TestCase $ case nativeWrapperSource
      [(("read_bytes","capi","unsafe",["ByteArray#","Word64Rep"],"Word64Rep"),"thc_native_test_0",Nothing),
       (("write_state","ccall","unsafe",["MutableByteArray#","AddrRep"],"void"),"thc_native_test_1",Nothing)] of
      Left message -> assertFailure message
      Right source -> do
        assertBool "C compiler supplies the ABI for word results"
          ("uint64_t thc_native_test_0(void * a0, uint64_t a1) { return read_bytes(a0, a1); }" `isInfixOf` source)
        assertBool "void calls do not manufacture a result"
          ("void thc_native_test_1(void * a0, void * a1) { write_state(a0, a1); }" `isInfixOf` source)
  , TestCase $ do
      let signed = ("fill","ccall","unsafe",["MutableByteArray#","Int16Rep"],"void")
          unsigned = ("fill","ccall","unsafe",["MutableByteArray#","Word16Rep"],"void")
          declarations = [entry "fill" "ccall" ["MutableByteArray#",rep,"void"] ["void"] | rep <- ["Int16Rep","Word16Rep"]]
          withHeader header = map (set "header" (toJSON (header::String))) declarations
      assertEqual "signedness adapters retain their exact typed carriers" (Right [signed,unsigned])
        (nativeSignatures "fixture-unit" [moduleWith (withHeader "original.h")])
      assertBool "missing configured callee prototype rejected" (isLeft (nativeSignatures "fixture-unit" [moduleWith declarations]))
      mapM_ (\header -> assertBool "header injection rejected" (isLeft (nativeSignatures "fixture-unit" [moduleWith (withHeader header)])))
        ["", "bad\nheader", "bad\"header", "bad\\header"]
      assertEqual "actual header owns callee signedness, adapters retain Haskell types"
        (Right "#include \"original.h\"\n#undef fill\nvoid thc_native_signed_0(void * a0, int16_t a1) { fill(a0, a1); }\n")
        (nativeWrapperSource [(signed,"thc_native_signed_0",Just "original.h")])
      assertBool "different widths still conflict" $ isLeft $ nativeSignatures "fixture-unit"
        [moduleWith [set "header" "original.h" (entry "fill" "ccall" [rep,"void"] ["void"]) | rep <- ["Int8Rep","Word16Rep"]]]
  , TestLabel "configured C dependency outputs belong to the LLVM replay" $ TestCase $ withScratch $ \root -> do
      repository <- lookupEnv "THC_TEST_ROOT" >>= maybe getCurrentDirectory pure
      compiler <- tool "GHC" "ghc"
      forM_ [0 :: Int .. 2] $ \index -> do
        let directory = root </> show index
            output = directory </> "original.dyn_o"
            dependency = directory </> "original.d"
            options = ["-MD","-MF",dependency,"-MT","original_native_target"]
            configured = case index of
              0 -> map ("-optc" ++) options
              1 -> concatMap (\value -> ["-optc",value]) options
              _ -> ["-optc-MMD","-optc-MF" ++ dependency,"-optc-MToriginal_native_target"]
            arguments = ["-c",repository </> "src/driver/cbits/target-layout.c","-fPIC","-o",output] ++ configured
        createDirectory directory
        (status,_,diagnostic) <- readProcessWithExitCode compiler arguments ""
        assertEqual diagnostic ExitSuccess status
        original <- BS.readFile dependency
        piece <- captureConfiguredNativeObject (directory </> "pieces") repository compiler arguments
        assertEqual "LLVM capture preserves the actual successful argv" (Just (toJSON arguments))
          (lookupField "inputs" piece >>= lookupField "arguments")
        assertEqual "LLVM replay must not overwrite the native dependency output" original =<< BS.readFile dependency
  , TestCase $ assertEqual "actual configured C/package arguments survive Haskell flag filtering"
      (Right ["-hide-all-packages","-Iinclude","-optc-DREAL=1","-package-db","/db","-package-id","base-unit"])
      (nativeCompilerArguments ["--make","-hide-all-packages","-Iinclude","-O2","-odir","/build",
        "-optc-DREAL=1","-package-db","/db","-package-id","base-unit","-main-is","Main","Main.hs"])
  , TestCase $ do
      current <- getCurrentDirectory
      let own = current </> "package/dist/build"
          nested = own </> "tool/tool-tmp"
          roots = [own]
          allRoots = roots ++ [nested]
      assertBool "own C object admitted" (nativeObjectOwned roots allRoots (own </> "cbits/a.o"))
      assertBool "nested component C objects excluded"
        (not (nativeObjectOwned roots allRoots (nested </> "cbits/a.o")))
      assertBool "unrelated output excluded" (not (nativeObjectOwned roots allRoots (current </> "other/a.o")))

  , TestCase $ do
      let options = ["--make","-no-link","-package-db","/exact/db","-hide-all-packages",
            "-package-id","owned-unit","-plugin-package-id","plugin-unit","-package","base",
            "-L","/native/lib","-lm","-l","custom","-optl","-pthread","-optl-Wl,-z,now",
            "-O2","-odir","/objects","Main.hs"]
      assertEqual "actual linker flags preserve their order"
        ["-L/native/lib","-lm","-lcustom","-pthread","-Wl,-z,now"] (nativeLinkOptions options)
      assertEqual "dependency selection excludes compiler plugins"
        [(True,"owned-unit"),(False,"base")] (nativePackageSelectors options)
      assertEqual "the selected package database survives"
        ["--global","--user","--package-db=/exact/db"] (nativePackageOptions options)
      assertEqual "clear/reset database stack and equals syntax"
        ["--global","--package-db=/exact/db"]
        (nativePackageOptions ["-clear-package-db","-global-package-db","-no-user-package-db","-package-db=/exact/db"])
      assertEqual "linker flags exclude unrelated GHC LLVM options"
        ["-pthread","-lm"] (nativeLinkOptions ["-optlo","-O2","-linkdir","ignored","-optl=-pthread","-lm"])
      assertEqual "framework paths are not GHC's preprocessor flag"
        ["-F/frameworks","-framework","Native","-lm"]
        (nativeLinkOptions ["-F","-framework-path","/frameworks","-framework","Native","-lm"])
      assertEqual "detached native linker options stay detached"
        ["-L","relative/lib","-F","relative/frameworks"]
        (nativeLinkOptions ["-optl","-L","-optl","relative/lib","-optl","-F","-optl","relative/frameworks"])
      assertEqual "arbitrary declared libraries do not need a symbol provider"
        ["-L/native/lib","-Wl,-rpath,/native/lib","-lcustom","-lm","-lcustom","-pthread"]
        (packageNativeLibraries ["/native/lib"] ["custom","m","custom"] ["-pthread"])
  ]
  where
    ordinary = entry "identity" "ccall" ["WordRep","void"] ["void","WordRep"]

entry :: String -> String -> [String] -> [String] -> Value
entry symbol convention arguments result = object ["isFunction" .= True,"symbol" .= symbol,
  "unit" .= Null,"header" .= Null,"convention" .= convention,"safety" .= ("unsafe"::String),
  "binder" .= object ["unit" .= ("fixture-unit"::String),"module" .= ("Fixture"::String),
    "occurrence" .= symbol,"namespace" .= ("value"::String)],
  "declaredType" .= ty,"normalizedType" .= ty,"normalizationRole" .= ("representational"::String),"emitted" .= object
  ["symbol" .= symbol,"unit" .= ("fixture-unit"::String),"convention" .= convention,
   "safety" .= ("unsafe"::String),"arguments" .= arguments,"result" .= result]]
  where ty = object ["kind" .= ("tycon"::String),"arguments" .= ([]::[Value]),"name" .= object
          ["unit" .= ("ghc-internal"::String),"module" .= ("GHC.Internal.Word"::String),
           "occurrence" .= ("Word"::String),"namespace" .= ("type"::String)]]

moduleWith :: [Value] -> Value
moduleWith imports = object ["unit" .= ("fixture-unit"::String),"module" .= ("Fixture"::String),"staticForeignImports" .= object
  ["schema" .= (1::Int),"status" .= ("verified"::String),"unit" .= ("fixture-unit"::String),
   "module" .= ("Fixture"::String),"scope" .= ("retained-static-import-products"::String),"execution" .= ("not-linked"::String),
   "profile" .= ("ghc-9.14.1-thc-only-static-c-imports-v1"::String),"wordBits" .= (64::Int),
   "expectedForeign" .= emptyArchive,"expectedCalls" .= ([]::[Value]),"imports" .= imports]]

emptyArchive :: Value
emptyArchive = object ["schema" .= (1::Int),"execution" .= ("not-linked"::String),
  "stubs" .= Null,"files" .= ([]::[Value])]

set :: String -> Value -> Value -> Value
set key value (Object fields) = Object (KM.insert (Key.fromString key) value fields)
set _ _ _ = error "test metadata must be an object"

changeEmitted :: String -> Value -> Value -> Value
changeEmitted key value = (if key == "safety" then set key value else id) . change "emitted" key value
changeProof :: String -> Value -> Value -> Value
changeProof key value = change "staticForeignImports" key value
change :: String -> String -> Value -> Value -> Value
change outer key value original@(Object fields) = case KM.lookup (Key.fromString outer) fields of
  Just inner -> set outer (set key value inner) original
  Nothing -> error "test metadata field is required"
change _ _ _ _ = error "test metadata must be an object"

lookupField :: String -> Value -> Maybe Value
lookupField key (Object fields) = KM.lookup (Key.fromString key) fields
lookupField _ _ = Nothing
