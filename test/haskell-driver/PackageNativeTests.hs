-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module PackageNativeTests (tests) where

import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import Data.Either (isLeft)
import Data.List (isInfixOf)
import Test.HUnit
import THC.Driver.PackageNative
import THC.Driver.NativeLibrarySources (validateNativeMathIR)

tests :: Test
tests = TestLabel "package-owned native C acquisition" $ TestList
  [ TestCase $ assertEqual "CAPI values, byte arrays, pointers and void keep their emitted ABI"
      (Right [("read_bytes","capi","unsafe",["ByteArray#","IntRep","Word64Rep"],"Word64Rep"),
              ("write_state","ccall","unsafe",["MutableByteArray#","AddrRep"],"void")])
      (nativeSignatures "fixture-unit" [moduleWith
        [entry "write_state" "ccall" ["MutableByteArray#","AddrRep","void"] ["void"],
         entry "read_bytes" "capi" ["ByteArray#","IntRep","Word64Rep","void"] ["void","Word64Rep"]]])
  , TestCase $ assertEqual "same-unit inlining contributes no invented declarations"
      (nativeSignatures "fixture-unit" [moduleWith [ordinary]])
      (nativeSignatures "fixture-unit" [moduleWith [ordinary],object ["unit" .= ("fixture-unit"::String)]])
  , TestCase $ do
      let blocked = changeEmitted "safety" "safe" (entry "blocked" "ccall" ["AddrRep","void"] ["void","WordRep"])
          original = moduleWith [ordinary,blocked]
      case archiveNativeModule "fixture-unit" original of
        Left message -> assertFailure message
        Right archived -> do
          assertEqual "original typed declaration inventory is not rewritten" (lookupField "staticForeignImports" original)
            (lookupField "staticForeignImports" archived)
          assertBool "unsupported safe pointer retained explicitly" (lookupField "packageNativeArchive" archived /= Nothing)
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
      , changeEmitted "safety" "safe" (entry "wrong" "ccall" ["AddrRep","void"] ["void","WordRep"])
      , changeEmitted "safety" "safe" (entry "wrong" "ccall" ["ByteArray#","void"] ["void","WordRep"])
      , changeEmitted "safety" "safe" (entry "wrong" "ccall" ["WordRep","void"] ["void","AddrRep"])
      , changeEmitted "safety" "safe" (entry "wrong" "ccall" [] ["void","WordRep"])
      , changeEmitted "unit" "other-unit" ordinary
      , entry "bad-name" "ccall" ["void"] ["void"]
      ]
  , TestCase $ assertEqual "safe scalar import retains safe metadata"
      (Right [("identity","ccall","safe",["WordRep"],"WordRep")])
      (nativeSignatures "fixture-unit" [moduleWith [changeEmitted "safety" "safe" ordinary]])
  , TestCase $ do
      assertEqual "libm scalar declarations preserve exact native widths" (Right ())
        (validateNativeMathIR ["erf","erff"]
          "declare double @erf(double noundef) local_unnamed_addr\ndeclare float @erff(float)\n")
      mapM_ (assertBool "wrong native libm prototype rejected" . isLeft . validateNativeMathIR ["erf"])
        ["declare float @erf(float)\n", "declare double @erf(ptr)\n",
         "declare double @erf(double, double)\n", "declare fastcc double @erf(double)\n", ""]
  , TestCase $ assertBool "conflicting emitted ABIs rejected" $ isLeft $ nativeSignatures "fixture-unit"
      [moduleWith [ordinary,entry "identity" "ccall" ["IntRep","void"] ["void","IntRep"]]]
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
      assertEqual "ordinary C data is permitted" (Right ()) (validateNativeIR "@counter = internal global i32 0\n")
      mapM_ (assertBool "C-level initialization obligations remain excluded" . isLeft . validateNativeIR)
        ["@llvm.global_ctors = appending global [1 x { i32, ptr, ptr }] []\n",
         "@llvm.global_dtors = appending global [1 x { i32, ptr, ptr }] []\n"]
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
