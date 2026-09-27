{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ForeignFunctionInterface #-}
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : FFI; supported GHC with a C compiler
--
-- Independent Haskell controls for source-backed structural masks and carry.
module Main (main) where

import Control.Exception (IOException, try)
import Control.Monad (filterM, forM_, unless)
import Data.Bits (setBit)
import qualified Data.ByteString as BS
import Data.Word (Word8)
import Foreign.C.Types (CInt (..))
import System.Exit (exitFailure)
import Test.HUnit
import THC.JsonIndex.Scanner

main :: IO ()
main = do
  result <- runTestTT tests
  unless (errors result + failures result == 0) exitFailure

foreign import ccall safe "thc_json_test_native"
  nativeParity :: IO CInt

tests :: Test
tests = TestList
  [ TestLabel "native scalar and ISA parity" $ TestCase $
      nativeParity >>= assertEqual "native parity result" 0
  , TestLabel "independent all-byte and carried-state masks" $ TestCase $ do
      backends <- filterM backendAvailable [minBound .. maxBound]
      let source = BS.pack ([0 .. 255] ++ [255,254 .. 0])
      forM_ backends $ \backend -> forM_ [minBound .. maxBound] $ \state -> do
        actual <- scanBlock backend state source
        assertEqual (show (backend, state)) (reference state source) actual
  , TestLabel "quote escape across 512-byte boundary" $ TestCase $ do
      let first = "\"" <> BS.replicate 510 97 <> "\\"
          second = "\"{}[],:\"[0]"
      a <- scanBlock Automatic InJson first
      assertEqual "escape carry" InEscape (finalState a)
      b <- scanBlock Automatic (finalState a) second
      assertEqual "resumed quoted punctuation" (reference InEscape second) b
  , TestLabel "empty scalar and padding" $ TestCase $ do
      forM_ [minBound .. maxBound] $ \state -> do
        result <- scanBlock Scalar state BS.empty
        assertEqual "empty preserves state" (BlockMasks state BS.empty BS.empty BS.empty) result
      result <- scanBlock Scalar InJson "{\"x\":[]}"
      assertEqual "canonical mask bytes and padding" (reference InJson "{\"x\":[]}") result
  , TestLabel "bounded input and ISA selection" $ TestCase $ do
      result <- try (scanBlock Automatic InJson (BS.replicate 513 32)) :: IO (Either IOException BlockMasks)
      assertBool "oversized input rejected" (either (const True) (const False) result)
      selected <- selectedBackend Automatic
      assertBool "automatic chooses concrete supported backend" (selected /= Automatic)
      available <- backendAvailable selected
      assertBool "selected ISA available" available
  ]

reference :: LexerState -> BS.ByteString -> BlockMasks
reference initial bytes =
  let (state, interests, opens, closes) = foldl' step (initial, [], [], []) (zip [0..] (BS.unpack bytes))
      padded = ((BS.length bytes + 63) `div` 64) * 8
      packed positions = BS.pack [foldl' (\w p -> if p `div` 8 == byte then setBit w (p `mod` 8) else w)
                                   (0 :: Word8) positions | byte <- [0 .. padded - 1]]
  in BlockMasks state (packed interests) (packed opens) (packed closes)
  where
    step (InEscape, interest, opens, closes) _ = (InString, interest, opens, closes)
    step (InString, interest, opens, closes) (_, c) =
      (if c == 34 then InJson else if c == 92 then InEscape else InString, interest, opens, closes)
    step (InJson, interest, opens, closes) (i, c)
      | c == 34 = (InString, interest, opens, closes)
      | c == 123 || c == 91 = (InJson, i:interest, i:opens, closes)
      | c == 125 || c == 93 = (InJson, i:interest, opens, i:closes)
      | c == 44 || c == 58 = (InJson, i:interest, opens, closes)
      | otherwise = (InJson, interest, opens, closes)
