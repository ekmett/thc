-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC with the declared language extensions
--
-- Check byte-exact interface JSON serialization against its reference renderer.
module Main (main) where

import Control.Exception (SomeException, evaluate, try)
import Control.Monad (forM_)
import qualified Data.ByteString as BS
import Data.Char (chr)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import System.Exit (exitFailure)
import Test.HUnit
import THC.JSON

main :: IO ()
main = do
  result <- runTestTT tests
  if errors result + failures result == 0 then pure () else exitFailure

tests :: Test
tests = TestList
  [ "ordered nested values" ~: same $ O
      [("z", A [Z, B True, B False, O [], A [], S ""]), ("a", N 0), ("z", S "duplicate key")]
  , "all control escapes" ~: same (S (map chr [0..127]))
  , "exact old escape spelling" ~:
      jsonBytes (S "\NUL\b\t\n\f\r\"\\/") ~?= "\"\\u0000\\u0008\\u0009\\u000a\\u000c\\u000d\\\"\\\\/\""
  , "all non-surrogate BMP characters" ~: same (S (map chr ([0..0xd7ff] ++ [0xe000..0xffff])))
  , "supplementary Unicode boundaries" ~: same (O
      [(map chr [0x10000,0x10001,0x1f642,0x10fffe,0x10ffff], S "λ雪🙂\x2028\x2029")])
  , "arbitrary integer spelling" ~: same (A (map N
      [0,1,-1,2^(63::Int),negate (2^(63::Int)),2^(256::Int),negate (10^(1000::Int))]))
  -- Floating payloads are already spelled by GHC's serializer before reaching
  -- JSON. They must remain strings, preserving -0.0, exponents and infinities.
  , "existing floating payload strings" ~: same (A (map (S . show)
      ([0,-0,1/0,-1/0,0/0,1.23456789012345,1e-300,1e300] :: [Double])))
  , "deep and wide values" ~: same (foldr (\_ child -> O [("nested",child)])
      (A (replicate 2048 (O [("value", S "source note \"λ\""), ("n",N 17)]))) [1..256 :: Int])
  , "late failure precedes strict success value" ~: TestCase $
      rejects (O [("prefix",S (replicate 100000 'x')), ("late",error "late serialization failure")])
  , "surrogates fail instead of silently replacing or emitting invalid UTF-8" ~: TestCase $
      forM_ [0xd800,0xdbff,0xdc00,0xdfff] $ \code -> rejects (S (['a',chr code,'z']))
  ]
  where
    same value = TestCase $ assertEqual "String and byte renderers differ"
      (Text.encodeUtf8 (Text.pack (json value))) (jsonBytes value)
    rejects value = do
      result <- try (evaluate (jsonBytes value)) :: IO (Either SomeException BS.ByteString)
      case result of
        Left _ -> pure ()
        Right _ -> assertFailure "Renderer exposed a strict success value before finishing validation"
