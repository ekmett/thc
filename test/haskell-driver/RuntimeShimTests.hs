-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module RuntimeShimTests (tests) where

import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
import Data.Either (isLeft)
import Test.HUnit
import THC.Driver.RuntimeShim (validateRuntimeShimModule, validateRuntimeShimInventory)

tests :: Test
tests = TestLabel "exact runtime shim native fallback profile" $ TestList
  [ TestCase $ assertEqual "exact runtime query declaration is admitted"
      (Right (["thc_runtime_v1_query"], ["thc_runtime_v1_query"]))
      (validateRuntimeShimModule owner (moduleValue [declaration] [descriptor]))
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
