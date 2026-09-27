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
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import Data.Either (isLeft)
import Data.List (isInfixOf, isPrefixOf)
import Test.HUnit
import THC.Driver.PackageNative
import THC.Driver.NativeLibrarySources (validateNativeMathIR, validateNativeEntropyIR, validateNativeWidthIR,
  nativeCxxInitSymbols, nativeLifecycleSymbols, validateNativeLifecycleIR,
  validateNativeLibcIR)
import THC.Driver.NativeArgumentBridge (nativeArgumentBridge)
import THC.Driver.NativeDependencies (selectCOnlyPieces)

tests :: Test
tests = TestLabel "package-owned native C acquisition" $ TestList
  [ TestCase $ do
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
      let validate = validateNativeLibcIR "x86_64-unknown-linux-gnu" ["realloc"]
      assertEqual "original libc allocation declaration" (Right ())
        (validate "declare noalias noundef ptr @realloc(ptr allocptr nocapture noundef, i64 noundef) local_unnamed_addr #5")
      forM_ ["declare ptr @realloc(ptr, i32)","declare fastcc ptr @realloc(ptr, i64)",
          "declare ptr @realloc(ptr addrspace(1), i64)","declare ptr @realloc(ptr, ...)",
          "declare ptr @realloc(ptr byval(i64), i64)","", "declare ptr @realloc(ptr, i64)\ndeclare ptr @realloc(ptr, i64)"] $ \source ->
        assertBool "unsupported native ABI stays rejected" (isLeft (validate source))
      assertBool "LP64 assumptions do not leak to Windows" (isLeft
        (validateNativeLibcIR "x86_64-pc-windows-msvc" ["realloc"] "declare ptr @realloc(ptr, i64)"))
      assertBool "unlisted libc entry stays unsupported" (isLeft
        (validateNativeLibcIR "x86_64-unknown-linux-gnu" ["system"] "declare i32 @system(ptr)"))
  , TestCase $ do
      let piece root name hash target = object ["root" .= (root :: String),
            "object" .= (root ++ "/" ++ name),"objectSha256" .= (hash :: String),
            "target" .= (target :: String)]
          first = piece "/selected" "a.o" "first" "target"
          second = piece "/selected" "b.o" "second" "target"
          sibling = piece "/sibling" "a.o" "different" "target"
      assertEqual "archive content selects only owned products" (Right [first,second])
        (selectCOnlyPieces [("a.o","first"),("b.o","second")] [sibling,second,first])
      assertBool "matching basename cannot bless different native object" (isLeft
        (selectCOnlyPieces [("a.o","other")] [first]))
      assertBool "unrecorded member is not silently omitted" (isLeft
        (selectCOnlyPieces [("a.o","first"),("b.o","second")] [first]))
      assertBool "different recipes for one native object remain ambiguous" (isLeft
        (selectCOnlyPieces [("a.o","first")] [first,piece "/sibling" "a.o" "first" "other"]))
      assertBool "duplicate archive members cannot expand authority" (isLeft
        (selectCOnlyPieces [("a.o","first"),("a.o","first")] [first]))
      forM_ ["../a.o","/a.o",".","","-N","@response","a b.o","a\nb.o"] $ \name ->
        assertBool "archive member paths cannot escape selection" (isLeft (selectCOnlyPieces [(name,"first")] [first]))
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
  , TestCase $ do
      let validate = validateNativeWidthIR "x86_64-unknown-linux-gnu"
      mapM_ (assertEqual "exact signed wchar_t/int ABI" (Right ()) . validate)
        ["declare i32 @wcwidth(i32)","declare noundef i32 @wcwidth(i32 noundef) #0"]
      mapM_ (assertBool "wrong width/arity/convention remains rejected" . isLeft . validate)
        ["declare i64 @wcwidth(i32)","declare i32 @wcwidth(i64)","declare i32 @wcwidth(ptr)",
         "declare i32 @wcwidth(i32, ...)","declare fastcc i32 @wcwidth(i32)","",
         "declare i32 @wcwidth(i32)\ndeclare i32 @wcwidth(i32)"]
      assertBool "wchar ABI is target-specific" (isLeft (validateNativeWidthIR "x86_64-pc-windows-msvc" "declare i32 @wcwidth(i32)"))
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
  , TestCase $ do
      assertEqual "libm scalar declarations preserve exact native widths" (Right ())
        (validateNativeMathIR ["erf","erff"]
          "declare double @erf(double noundef) local_unnamed_addr\ndeclare float @erff(float)\n")
      mapM_ (assertBool "wrong native libm prototype rejected" . isLeft . validateNativeMathIR ["erf"])
        ["declare float @erf(float)\n", "declare double @erf(ptr)\n",
         "declare double @erf(double, double)\n", "declare fastcc double @erf(double)\n", ""]
  , TestCase $ assertBool "conflicting emitted ABIs rejected" $ isLeft $ nativeSignatures "fixture-unit"
      [moduleWith [ordinary,entry "identity" "ccall" ["IntRep","void"] ["void","IntRep"]]]
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
  , TestCase $ do
      let target = "x86_64-unknown-linux-gnu"
          valid = "declare i32 @getentropy(ptr noundef, i64 noundef) local_unnamed_addr\n"
      assertEqual "getentropy preserves native pointer/size_t/status ABI" (Right ())
        (validateNativeEntropyIR target valid)
      mapM_ (assertBool "wrong getentropy prototype rejected" . isLeft . validateNativeEntropyIR target)
        ["declare i64 @getentropy(ptr, i64)\n", "declare i32 @getentropy(ptr, i32)\n",
         "declare i32 @getentropy(i64, i64)\n", "declare i32 @getentropy(ptr, i64, ...)\n",
         "declare fastcc i32 @getentropy(ptr, i64)\n", valid ++ valid, ""]
      assertBool "unsupported getentropy target rejected"
        (isLeft (validateNativeEntropyIR "x86_64-apple-darwin" valid))
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
          ("HsWord64 thc_native_test_0(void * a0, HsWord64 a1) { return read_bytes(a0, a1); }" `isInfixOf` source)
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
        (Right "#include \"original.h\"\n#undef fill\nvoid thc_native_signed_0(void * a0, HsInt16 a1) { fill(a0, a1); }\n")
        (nativeWrapperSource [(signed,"thc_native_signed_0",Just "original.h")])
      assertBool "different widths still conflict" $ isLeft $ nativeSignatures "fixture-unit"
        [moduleWith [set "header" "original.h" (entry "fill" "ccall" [rep,"void"] ["void"]) | rep <- ["Int8Rep","Word16Rep"]]]
  , TestCase $ assertEqual "actual configured C/package arguments survive Haskell flag filtering"
      (Right ["-hide-all-packages","-Iinclude","-optc-DREAL=1","-package-db","/db","-package-id","base-unit"])
      (nativeCompilerArguments ["--make","-hide-all-packages","-Iinclude","-O2","-odir","/build",
        "-optc-DREAL=1","-package-db","/db","-package-id","base-unit","-main-is","Main","Main.hs"])
  , TestCase $ do
      let source = unlines ["@__dso_handle = external hidden global i8",
            "declare void @_ZNSt8ios_base4InitC1Ev(ptr noundef nonnull align 1 dereferenceable(1)) unnamed_addr #0",
            "declare void @_ZNSt8ios_base4InitD1Ev(ptr noundef nonnull align 1 dereferenceable(1)) unnamed_addr #1",
            "declare i32 @__cxa_atexit(ptr, ptr, ptr) local_unnamed_addr #2"]
          symbols = nativeCxxInitSymbols ++ nativeLifecycleSymbols
          validate = validateNativeLifecycleIR "x86_64-unknown-linux-gnu" symbols
      assertEqual "exact original simdutf iostream lifecycle ABI" (Right ()) (validate source)
      assertBool "different platform ABI is not guessed" (isLeft (validateNativeLifecycleIR "aarch64-unknown-linux-gnu" symbols source))
      mapM_ (\line -> assertBool "malformed supported lifetime declaration remains fatal" (isLeft
        (validateNativeLifecycleIR "x86_64-unknown-linux-gnu" ["__cxa_atexit"] line)))
        ["declare i64 @__cxa_atexit(ptr, ptr, ptr)", "declare i32 @__cxa_atexit(ptr, ptr)",
         "declare i32 @__cxa_atexit(ptr, ptr, ...)", "declare fastcc i32 @__cxa_atexit(ptr, ptr, ptr)",
         "declare i32 @__cxa_atexit(ptr addrspace(1), ptr, ptr)"]
      assertBool "DSO identity must retain its actual type" (isLeft
        (validateNativeLifecycleIR "x86_64-unknown-linux-gnu" ["__dso_handle"] "@__dso_handle = external hidden global i64"))
  , TestCase $ do
      let roots = ["/package/dist/build"]
          allRoots = roots ++ ["/package/dist/build/tool/tool-tmp"]
      assertBool "own C object admitted" (nativeObjectOwned roots allRoots "/package/dist/build/cbits/a.o")
      assertBool "nested component C objects excluded"
        (not (nativeObjectOwned roots allRoots "/package/dist/build/tool/tool-tmp/cbits/a.o"))
      assertBool "unrelated output excluded" (not (nativeObjectOwned roots allRoots "/other/a.o"))
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
