-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- aeson-2.3.2.0 CompareWithJSON: compare-json/decode/nf/aeson/strict.
module AesonBenchmarks (aesonDecodeValue) where
import Control.DeepSeq (rnf)
import qualified Data.Aeson as A
import qualified Data.Aeson.KeyMap as K
import Data.Bits ((.&.))
import qualified Data.ByteString as B
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
