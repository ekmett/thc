-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Compiler model handoff controls. No executable JSON writer or index exists.
module Main (main) where

import Control.Exception (SomeException, evaluate, try)
import Control.Monad (forM_)
import Data.Aeson (Value(..), encode, eitherDecode, object, toJSON, (.=))
import qualified Data.ByteString.Lazy as BL
import Data.Char (chr)
import Data.Int (Int64)
import System.Exit (exitFailure)
import Test.HUnit
import THC.JSON

main :: IO ()
main = do
  result <- runTestTT tests
  if errors result + failures result == 0 then pure () else exitFailure

tests :: Test
tests = TestList
  [ "module structure is handed off without serialization" ~:
      moduleValue (O [("bindings",A [O [("id",S "unit:Module.λ"),("arity",N 2)]]),
        ("ready",B True),("missing",Z)]) ~?=
      object ["bindings" .= [object ["id" .= ("unit:Module.λ" :: String),"arity" .= (2 :: Int)]],
        "ready" .= True,"missing" .= Null]
  , "all control and valid Unicode codepoints retain exact text" ~: TestCase $
      forM_ [map chr [0..127],map chr ([0..0xd7ff] ++ [0xe000..0xffff]),
        map chr [0x10000,0x10001,0x1f642,0x10fffe,0x10ffff]] $ \value ->
        assertEqual "exact text" (toJSON value) (moduleValue (S value))
  , "integers retain arbitrary precision" ~: TestCase $
      forM_ [0,1,-1,2^(63::Int),negate (2^(63::Int)),2^(256::Int),negate (10^(1000::Int))] $ \value ->
        assertEqual "integer value" (toJSON value) (moduleValue (N value))
  , "floating payload spellings remain strings" ~: TestCase $
      forM_ ([0,-0,1/0,-1/0,0/0,1.23456789012345,1e-300,1e300] :: [Double]) $ \value ->
        assertEqual "floating text" (toJSON (show value)) (moduleValue (S (show value)))
  , "deep and wide module values survive explicit inspection" ~: TestCase $ do
      let value = moduleValue (foldr (\_ child -> O [("nested",child)])
            (A (replicate 2048 (O [("value",S "source note λ"),("n",N 17)]))) [1..256 :: Int])
      assertEqual "inspection roundtrip" (Right value) (eitherDecode (encode value))
  , "late model failure cannot become a successful inspected value" ~: TestCase $
      rejects (O [("prefix",S (replicate 100000 'x')),("late",error "late model failure")])
  , "surrogate text rejects instead of silently changing identities" ~: TestCase $
      forM_ [0xd800,0xdbff,0xdc00,0xdfff] $ \code -> do
        rejects (S ['a',chr code,'z'])
        rejects (O [([chr code],Z)])
  ]
  where
    rejects value = do
      result <- try (evaluate (BL.length (encode (moduleValue value)))) :: IO (Either SomeException Int64)
      case result of
        Left _ -> pure ()
        Right _ -> assertFailure "Invalid model reached completed inspection"
