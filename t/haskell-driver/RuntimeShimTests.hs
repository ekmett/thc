-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : RuntimeShimTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and driver test dependencies
--
-- Tests for runtime shim.
module RuntimeShimTests (tests) where

import Control.Monad (forM_)
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
import Data.Either (isLeft)
import Test.HUnit
import THC.Driver.RuntimeShim (validateRuntimeShimModule, validateRuntimeShimInventory, foreignExceptionBridgeUnit,
  coreNativeOverride, coreNativeImport, coreNativeOverrideCalls)

tests :: Test
tests = TestLabel "exact runtime shim native fallback profile" $ TestList
  [ TestCase $ do
      forM_ coreNativeOverrideCalls $ \descriptorValue -> do
        assertEqual "complete selected descriptor omits only its Core wrapper" (Right True) (coreNativeOverride descriptorValue)
        let targetValue = case descriptorValue of Object fields -> maybe (error "test target") id (KM.lookup "target" fields); _ -> error "test call"
        assertEqual "an ordinary unit cannot acquire a context-owned capability" (Right False)
          (coreNativeOverride (alter "target" (alter "unit" "ordinary-unit" targetValue) descriptorValue))
        assertEqual "names are not prefix capabilities" (Right False)
          (coreNativeOverride (alter "target" (alter "symbol" "getProgArgv_extra" targetValue) descriptorValue))
        forM_ [alter "schema" (Number 2) descriptorValue, alter "suppliedArity" (Number 0) descriptorValue,
               alter "argumentTypes" (Array mempty) descriptorValue, alter "convention" "capi" descriptorValue,
               alter "target" (alter "isFunction" (Bool False) targetValue) descriptorValue,
               alter "target" (alter "kind" "dynamic" targetValue) descriptorValue] $ \wrong ->
          assertBool "a selected identity never falls back to native RTS on ABI drift" (isLeft (coreNativeOverride wrong))
  , TestCase $ do
      -- GHC.Internal.TopHandler's genuine retained declaration includes Rts.h.
      -- The header is source provenance, not a different ccall ABI.
      let emitted = object ["symbol" .= ("shutdownHaskellAndExit"::String), "unit" .= ("ghc-internal"::String),
            "convention" .= ("ccall"::String), "safety" .= ("safe"::String),
            "arguments" .= (["Int32Rep","Int32Rep","void"]::[String]), "result" .= (["void"]::[String])]
          imported = object ["isFunction" .= True, "header" .= ("Rts.h"::String), "emitted" .= emitted]
      assertEqual "the original configured ccall header preserves the exact Core capability" (Right True)
        (coreNativeImport imported)
      forM_ [String "", String "bad\0.h", String "bad\n.h", String "bad\r.h",
             String "bad\".h", String "bad\\.h", Number 1] $ \header ->
        assertBool "retained source headers still require well-formed provenance"
          (isLeft (coreNativeImport (alter "header" header imported)))
      forM_ [alter "isFunction" (Bool False) imported,
             alter "emitted" (alter "convention" "capi" emitted) imported,
             alter "emitted" (alter "safety" "unsafe" emitted) imported,
             alter "emitted" (alter "arguments" (strings ["IntRep","Int32Rep","void"]) emitted) imported,
             alter "emitted" (alter "result" (strings ["void","Int32Rep"]) emitted) imported] $ \wrong ->
        assertBool "a source header never grants a different invocation ABI" (isLeft (coreNativeImport wrong))
      assertEqual "same spelling with another owner still needs an ordinary provider" (Right False)
        (coreNativeImport (alter "emitted" (alter "unit" "ordinary-unit" emitted) imported))
  , TestCase $ do
      let emitted = object ["symbol" .= ("getProgArgv"::String), "unit" .= ("ghc-internal"::String),
            "convention" .= ("ccall"::String), "safety" .= ("unsafe"::String),
            "arguments" .= (["AddrRep","AddrRep","void"]::[String]), "result" .= (["void"]::[String])]
          imported = object ["isFunction" .= True, "header" .= Null, "emitted" .= emitted]
      assertEqual "exact source import uses the same descriptor-derived ABI" (Right True) (coreNativeImport imported)
      assertEqual "a configured ccall header is not a per-symbol capability" (Right True)
        (coreNativeImport (alter "header" "native.h" imported))
      assertEqual "ordinary same-name provider is still required" (Right False)
        (coreNativeImport (alter "emitted" (alter "unit" "ordinary-unit" emitted) imported))
      forM_ [alter "isFunction" (Bool False) imported,
             alter "emitted" (alter "safety" "safe" emitted) imported,
             alter "emitted" (alter "arguments" (strings ["AddrRep","IntRep","void"]) emitted) imported] $ \wrong ->
        assertBool "native declarations cannot borrow a merely similar Core capability" (isLeft (coreNativeImport wrong))
  , TestCase $ assertEqual "exact runtime query declaration is admitted"
      (Right (["thc_runtime_v1_query"], ["thc_runtime_v1_query"]))
      (validateRuntimeShimModule owner (moduleValue [declaration] [descriptor]))
  , TestCase $ do
      let textCall = alter "safety" (String "safe") $ alter "symbol" (String "thc_exception_v1_text") $
            alter "arguments" (strings ["AddrRep", "Int32Rep", "Int64Rep", "void"]) call
          textDescriptor = alter "safety" (String "safe") $ alter "target" (alter "symbol" (String "thc_exception_v1_text") target) $
            alter "argumentReps" (toJSON [rep ["AddrRep"], rep ["Int32Rep"], rep ["Int64Rep"], rep []]) descriptor
          textDeclaration = alter "emitted" textCall declaration
      assertEqual "exception metadata has one exact target-defined ABI"
        (Right (["thc_exception_v1_text"], ["thc_exception_v1_text"]))
        (validateRuntimeShimModule owner (moduleValue [textDeclaration] [textDescriptor]))
      assertBool "exception Core descriptor rejects unsafe metadata execution"
        (isLeft (validateRuntimeShimModule owner
          (moduleValue [textDeclaration] [alter "safety" (String "unsafe") textDescriptor])))
      forM_ [alter "arguments" (strings ["Int64Rep", "Int32Rep", "Int64Rep", "void"]) textCall,
             alter "safety" (String "unsafe") textCall,
             alter "unit" (String "other-unit") textCall,
             alter "symbol" (String "thc_exception_v1_text_extra") textCall] $ \wrong ->
        assertBool "exception shim rejects changed owner, pointer ABI, safety and suffix"
          (isLeft (validateRuntimeShimModule owner (moduleValue [alter "emitted" wrong declaration] [])))
  , TestCase $ do
      let bridge unit = object ["schema" .= (1 :: Int), "unit" .= unit,
            "module" .= ("THC.Internal.Exception" :: String),
            "box" .= (unit ++ ":THC.Internal.Exception.boxForeign"),
            "project" .= (unit ++ ":THC.Internal.Exception.projectForeign"),
            "payloadType" .= (unit ++ ":THC.Internal.Exception.ForeignException"),
            "exceptionType" .= ("ghc-internal:GHC.Internal.Exception.Type.SomeException" :: String)]
          internal unit = object ["unit" .= unit, "module" .= ("THC.Internal.Exception" :: String),
            "foreignExceptionBridge" .= bridge unit]
          public :: String -> Value
          public unit = object ["unit" .= unit, "module" .= ("THC.Exception" :: String)]
      assertEqual "missing bridge requests the private runtime sidecar" (Right Nothing)
        (foreignExceptionBridgeUnit [object ["unit" .= owner, "module" .= ("Main" :: String)]])
      assertEqual "reuse the application's exact dictionary unit" (Right (Just owner))
        (foreignExceptionBridgeUnit [internal owner, public owner])
      forM_ [[internal owner, internal "other-unit"], [public owner],
             [internal owner, public "other-unit"],
             [alter "foreignExceptionBridge" (alter "unit" (String "other-unit") (bridge owner)) (internal owner)],
             [alter "foreignExceptionBridge" (alter "box" (String "invented.box") (bridge owner)) (internal owner)],
             [alter "foreignExceptionBridge" (alter "schema" (Number 2) (bridge owner)) (internal owner)]] $ \values ->
        assertBool "missing, ambiguous and inconsistent dictionary identities are rejected"
          (isLeft (foreignExceptionBridgeUnit values))
  , TestCase $ assertEqual "a pure module may carry no declarations"
      (Right ([], [])) (validateRuntimeShimModule owner (moduleValue [] []))
  , TestCase $ assertEqual "exporter omits Verified [] metadata for a pure module"
      (Right ([], [])) (validateRuntimeShimModule owner (object ["unit" .= owner]))
  , TestCase $ assertEqual "inlined calls may move between modules of the same unit"
      (Right ([], ["thc_runtime_v1_query"]))
      (validateRuntimeShimModule owner (moduleValue [] [descriptor]))
  , TestCase $ do
      assertEqual "same-unit inlining matches verified declarations across the complete component"
        (Right ()) (validateRuntimeShimInventory owner
          [moduleValue [declaration] [], moduleValue [] [descriptor]])
      assertBool "an unassociated generated call is not hidden by module-local permissiveness"
        (isLeft (validateRuntimeShimInventory owner [moduleValue [] [descriptor]]))
      assertBool "empty shim inventory cannot hide unused native code"
        (isLeft (validateRuntimeShimInventory owner [moduleValue [] []]))
  , TestCase $ mapM_ (\value -> assertBool "exact declaration rejection"
        (isLeft (validateRuntimeShimModule owner (moduleValue [value] []))))
      [ alter "emitted" (alter "symbol" (String "thc_runtime_v1_query_extra") call) declaration
      , alter "emitted" (alter "symbol" (String "ordinary_c_function") call) declaration
      , alter "emitted" (alter "safety" (String "safe") call) declaration
      , alter "emitted" (alter "unit" (String "another-unit") call) declaration
      , alter "emitted" (alter "arguments" (strings ["Int32Rep", "Int64Rep", "Int32Rep", "void"]) call) declaration
      , alter "emitted" (alter "result" (strings ["void", "Int32Rep"]) call) declaration
      , alter "header" (String "foreign.h") declaration
      , alter "isFunction" (Bool False) declaration
      ]
  , TestCase $ mapM_ (\value -> assertBool "exact Core descriptor rejection"
        (isLeft (validateRuntimeShimModule owner (moduleValue [declaration] [value]))))
      [ alter "target" (alter "symbol" (String "ordinary_c_function") target) descriptor
      , alter "target" (alter "unit" (String "another-unit") target) descriptor
      , alter "target" (alter "kind" (String "dynamic") target) descriptor
      , alter "target" (alter "isFunction" (Bool False) target) descriptor
      , alter "convention" (String "capi") descriptor
      , alter "safety" (String "safe") descriptor
      , alter "arity" (Number 3) descriptor
      , alter "suppliedArity" (Number 3) descriptor
      , alter "argumentReps" (Array mempty) descriptor
      , alter "resultRep" (object ["aggregate" .= ("unboxed-tuple" :: String), "components" .= [rep [], rep ["Int32Rep"]]]) descriptor
      ]
  , TestCase $ mapM_ (\value -> assertBool "unverified/foreign-obligation module rejection"
        (isLeft (validateRuntimeShimModule owner value)))
      [ alter "unit" (String "another-unit") (moduleValue [declaration] [])
      , alter "foreign" (object []) (moduleValue [declaration] [])
      , alter "staticForeignImports" (alter "status" (String "unclassified") (proof [declaration])) (moduleValue [] [])
      ]
  ]
  where
    owner = "test-runtime-unit" :: String
    strings values = toJSON (values :: [String])
    alter key value (Object fields) = Object (KM.insert key value fields)
    alter _ _ _ = error "test object"
    rep values = object ["primReps" .= (values :: [String])]
    call = object ["symbol" .= ("thc_runtime_v1_query" :: String), "unit" .= owner,
      "convention" .= ("ccall" :: String), "safety" .= ("unsafe" :: String),
      "arguments" .= (["Int32Rep", "Int64Rep", "Int64Rep", "void"] :: [String]),
      "result" .= (["void", "Int64Rep"] :: [String])]
    declaration = object ["isFunction" .= True, "header" .= Null, "emitted" .= call]
    target = object ["kind" .= ("static" :: String), "symbol" .= ("thc_runtime_v1_query" :: String),
      "unit" .= owner, "isFunction" .= True]
    descriptor = object ["schema" .= (1 :: Int), "target" .= target,
      "convention" .= ("ccall" :: String), "safety" .= ("unsafe" :: String),
      "arity" .= (4 :: Int), "suppliedArity" .= (4 :: Int),
      "argumentReps" .= map rep [["Int32Rep"], ["Int64Rep"], ["Int64Rep"], []],
      "resultRep" .= object ["aggregate" .= ("unboxed-tuple" :: String),
        "components" .= [rep [], rep ["Int64Rep"]]]]
    proof imports = object ["schema" .= (1 :: Int), "status" .= ("verified" :: String),
      "unit" .= owner, "imports" .= (imports :: [Value])]
    moduleValue imports calls = object ["unit" .= owner, "staticForeignImports" .= proof imports,
      "bindings" .= (calls :: [Value])]
