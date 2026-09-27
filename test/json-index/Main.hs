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
import qualified Crypto.Hash.SHA256 as SHA
import Data.Bits (setBit, testBit, shiftL, (.|.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Word (Word8, Word32, Word64)
import Foreign.C.Types (CInt (..))
import System.Exit (exitFailure)
import System.Environment (getArgs)
import System.Directory (createDirectoryIfMissing)
import System.FilePath ((</>))
import Test.HUnit
import THC.JsonIndex
import THC.JsonIndex.Scanner

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--write-goldens", directory] -> do
      createDirectoryIfMissing True directory
      forM_ goldenSources $ \(name, source) -> do
        BS.writeFile (directory </> name ++ ".json") source
        BS.writeFile (directory </> name ++ ".idx") (referenceSidecar source)
    [] -> do
      result <- runTestTT tests
      unless (errors result + failures result == 0) exitFailure
    _ -> ioError (userError "Expected no arguments, or --write-goldens DIRECTORY")

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
  , TestLabel "whole source sections match independent masks" $ TestCase $ do
      backends <- filterM backendAvailable [minBound .. maxBound]
      let patternBytes = "{\"x\":[0,\"\\\"{},:[]\"],\"y\":true} \255"
          large = BS.concat (replicate 600 patternBytes)
      forM_ [0,1,2,31,32,63,64,511,512,513,2047,2048,2049,16383,16384,16385] $ \count -> do
        let source = BS.take count large
            expected = referenceSections source
        forM_ backends $ \backend -> do
          actual <- buildSections backend source
          assertEqual (show (count, backend)) expected actual
  , TestLabel "checked layout and epoch boundaries" $ TestCase $ do
      -- Arithmetic-only epoch controls; no 2^32-byte fixture is allocated.
      assertEqual "empty" (Right (Layout 0 0 0 0 0)) (sectionLayout 0 0)
      assertBool "impossible count" (either (const True) (const False) (sectionLayout 1 2))
      assertBool "overflow rejected" (either (const True) (const False) (sectionLayout maxBound maxBound))
      assertEqual "last byte of first epoch" (Right 8) (epochBytes <$> sectionLayout 4294967296 0)
      assertEqual "first byte of next epoch" (Right 16) (epochBytes <$> sectionLayout 4294967297 0)
  , TestLabel "v2 envelope matches independent byte model" $ TestCase $ do
      forM_ goldenSources $ \(name, source) -> do
        actual <- encodeSidecar Automatic source
        assertEqual name (referenceSidecar source) actual
  , TestLabel "persisted cross-language vectors" $ TestCase $
      forM_ goldenSources $ \(name, source) -> do
        storedSource <- BS.readFile ("test/json-index/golden" </> name ++ ".json")
        storedIndex <- BS.readFile ("test/json-index/golden" </> name ++ ".idx")
        assertEqual (name ++ " source") source storedSource
        assertEqual (name ++ " index") (referenceSidecar source) storedIndex
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

-- Independent section model deliberately uses the bytewise scanner above,
-- lists and explicit integer encodings. Production never constructs these
-- per-quarter records or a retained input-sized bitmap.
referenceSections :: BS.ByteString -> Sections
referenceSections source =
  let quarters = scanQuarters InJson source
      events = concatMap snd quarters
      populations = map (length . snd) quarters
      blocks = chunks 4 populations
      prefixes = init (scanl (+) 0 (map sum blocks))
      directory = BS.concat
        [ word32 (fromIntegral before) <> word32 (packed runs)
        | (before, runs) <- zip prefixes blocks ]
      states = padded (packPairs (map (fromEnum . fst) quarters))
      bits = padded (packPairs events)
      lastState = foldl' nextState InJson (BS.unpack source)
  in Sections (fromIntegral (BS.length source)) (fromIntegral (length events)) lastState
       (if BS.null source then BS.empty else word64 0) directory states bits
  where
    scanQuarters :: LexerState -> BS.ByteString -> [(LexerState, [Int])]
    scanQuarters _ bytes | BS.null bytes = []
    scanQuarters state bytes =
      let (part, rest) = BS.splitAt 512 bytes
          scanned = reference state part
          pairs = [ if at (openMask scanned) i then 3 else if at (closeMask scanned) i then 0 else 2
                  | i <- [0 .. BS.length part - 1], at (interestMask scanned) i ]
      in (state, pairs) : scanQuarters (finalState scanned) rest
    at mask i = testBit (BS.index mask (i `div` 8)) (i `mod` 8)
    packed runs = foldl' (.|.) 0 [fromIntegral n `shiftL` (11 * i) | (i, n) <- zip [0..2] runs]
    padded bytes = bytes <> BS.replicate ((8 - BS.length bytes `mod` 8) `mod` 8) 0
    packPairs :: [Int] -> BS.ByteString
    packPairs pairs = BS.pack
      [ fromIntegral (foldl' (.|.) 0 [value `shiftL` (2 * i) | (i, value) <- zip [0..] group])
      | group <- chunks 4 pairs ]
    nextState InEscape _ = InString
    nextState InString c = if c == 34 then InJson else if c == 92 then InEscape else InString
    nextState InJson c = if c == 34 then InString else InJson

chunks :: Int -> [a] -> [[a]]
chunks _ [] = []
chunks count xs = let (part, rest) = splitAt count xs in part : chunks count rest

word32 :: Word32 -> BS.ByteString
word32 x = BS.pack [fromIntegral (x `div` (256 ^ i)) | i <- [0..3 :: Int]]

word64 :: Word64 -> BS.ByteString
word64 x = BS.pack [fromIntegral (x `div` (256 ^ i)) | i <- [0..7 :: Int]]

goldenSources :: [(String, BS.ByteString)]
goldenSources =
  [ ("empty-input", BS.empty)
  , ("scalar", "42")
  , ("empty-object", "{}")
  , ("odd-events", "{\"a\":1}")
  , ("mixed", "{\"a\":[1,true,null,\"{},:[]\"],\"b\":{\"x\":-1.25e+3}}")
  , ("escaped", "[\"\\\"{},:[]\\\\\",\"\\u005b\",\"\195\169\"]")
  , ("quarter-carry", "[\"" <> BS.replicate 509 97 <> "\\\"{}[],:\",0]")
  ]

referenceSidecar :: BS.ByteString -> BS.ByteString
referenceSidecar source =
  let sections = referenceSections source
      header = BSC.pack "THCJSIX1" <> word32 2 <> word32 0 <>
        word64 (fromIntegral (BS.length source)) <> word64 (interestCount sections) <> SHA.hash source
      body = BS.concat [header, epochs sections, poppy sections, lexerStates sections, topology sections]
  in body <> SHA.hash body
