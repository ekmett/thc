-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- aeson-2.3.2.0 CompareWithJSON: compare-json/decode/nf/aeson/strict.
module AesonBenchmarks (aesonDecodeValue, aesonContentFingerprint, decodedContentFingerprint) where
import Control.DeepSeq (rnf)
import qualified Data.Aeson as A
import qualified Data.Aeson.Encoding as E
import qualified Data.Aeson.KeyMap as K
import Data.Bits ((.&.), xor)
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as L
import Data.Word (Word64)
import qualified Data.Vector as V
import GHC.Exts (Int(I#), Int#)
import System.IO.Unsafe (unsafePerformIO)

{-# NOINLINE source #-}
source :: B.ByteString
source = unsafePerformIO (B.readFile "corpus/twitter100.json")

rawInputs :: [B.ByteString]
rawInputs = [B.replicate n 32 <> source | n <- [0..15]]

aesonDecodeValue :: Int# -> Int#
aesonDecodeValue input =
  case A.decodeStrict (rawInputs !! (I# input .&. 15)) of
    Nothing -> -1#
    Just value -> rnf value `seq` case rootSize value of
      I# result -> result
  where
    rootSize (A.Array xs) = V.length xs
    rootSize (A.Object xs) = K.size xs
    rootSize _ = 1

-- | Preflight only: canonical object ordering, full scalar/array content and
-- a 64-bit FNV-1a checksum. This additional encoding is never part of the timed
-- decodeStrict + normal-form benchmark above.
decodedContentFingerprint :: A.Value -> Word64
decodedContentFingerprint =
  L.foldl' (\hash byte -> (hash `xor` fromIntegral byte) * 1099511628211)
    14695981039346656037 . E.encodingToLazyByteString . canonical
  where
    canonical (A.Object fields) =
      E.pairs (foldMap (\(key, value) -> E.pair key (canonical value)) (K.toAscList fields))
    canonical (A.Array values) = E.list canonical (V.toList values)
    canonical value = A.toEncoding value

-- | Run once per input before timing, comparing against the native oracle.
aesonContentFingerprint :: Int# -> Int#
aesonContentFingerprint input =
  case A.decodeStrict (rawInputs !! (I# input .&. 15)) of
    Nothing -> error "aeson content preflight: invalid JSON"
    Just value -> case fromIntegral (decodedContentFingerprint value) of
      I# result -> result
