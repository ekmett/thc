-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE LambdaCase #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell; explicitly little-endian, 64-bit file offsets
--
-- Paged binary serialization of the immutable Core arena. Only header/index
-- bytes need authentication at open; each demanded data block has its own hash.
-- The writer never constructs a flat JSON document or a per-record u64 index.
module THC.CoreStore.Binary
  ( Encoded(..), encodeStore, encodedBytes, writeEncoded, unsignedLEB, signedLEB
  ) where

import Control.Monad (unless)
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Bits ((.&.), (.|.), shiftR)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as BB
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Data.Int (Int64)
import Data.List (mapAccumL)
import Data.Word (Word32, Word64)
import System.IO (IOMode(WriteMode), withBinaryFile)
import THC.CoreStore.Model

data Encoded = Encoded
  { encodedHeaderIndex :: !BS.ByteString
  , encodedIndexSHA256 :: !BS.ByteString
  , encodedBlocks :: ![BS.ByteString]
  , encodedLength :: !Word64
  } deriving (Eq, Show)

bytes :: BB.Builder -> BS.ByteString
bytes = BL.toStrict . BB.toLazyByteString

unsignedLEB :: Word64 -> BB.Builder
unsignedLEB value
  | value < 128 = BB.word8 (fromIntegral value)
  | otherwise = BB.word8 (fromIntegral (value .&. 127) .|. 128) <> unsignedLEB (value `shiftR` 7)

signedLEB :: Int64 -> BB.Builder
signedLEB value =
  let low = fromIntegral (value .&. 127)
      rest = value `shiftR` 7
      finished = (rest == 0 && low .&. 64 == 0) || (rest == -1 && low .&. 64 /= 0)
  in BB.word8 (if finished then low else low .|. 128) <>
       if finished then mempty else signedLEB rest

r :: Ref -> BB.Builder
r = unsignedLEB . fromIntegral . unRef
s :: Scope -> BB.Builder
s = unsignedLEB . fromIntegral . unScope
b :: Binder -> BB.Builder
b = unsignedLEB . fromIntegral . unBinder
n :: Int -> BB.Builder
n = unsignedLEB . fromIntegral
flag :: Bool -> BB.Builder
flag = BB.word8 . fromIntegral . fromEnum

data Payload = Payload !Word64 !BB.Builder
appendPayload :: BS.ByteString -> Payload -> (Word64,Payload)
appendPayload chunk (Payload offset output) =
  (offset,Payload (offset + fromIntegral (BS.length chunk)) (output <> BB.byteString chunk))

record :: Payload -> Node -> (Payload, BS.ByteString)
record initial node = case node of
  Null -> plain 0 mempty
  Boolean value -> plain (if value then 2 else 1) mempty
  Integer value
    | value >= toInteger (minBound :: Int64) && value <= toInteger (maxBound :: Int64) ->
        plain 3 (signedLEB (fromInteger value))
    | otherwise ->
        let magnitude = BS.pack (reverse (digits (abs value)))
            (offset,next) = appendPayload magnitude initial
        in emit next 7 (flag (value < 0) <> BB.word64LE offset <>
             BB.word64LE (fromIntegral (BS.length magnitude)))
  String value -> let (offset,next) = appendPayload value initial
    in emit next 4 (BB.word64LE offset <> BB.word64LE (fromIntegral (BS.length value)))
  Vector refs -> vector 5 unRef r refs
  Object keys values -> plain 6 (r keys <> r values)
  Layout fields -> plain 8 (r fields)
  Representation layout proof -> plain 9 (r layout <> case proof of
    Absent -> BB.word8 0 <> n 0
    Unevaluated position -> BB.word8 1 <> n position
    Evaluated position -> BB.word8 2 <> n position)
  Variable scope meta target -> plain 16 (s scope <> r meta <> case target of
    Global name -> BB.word8 0 <> r name
    Local binder -> BB.word8 1 <> b binder)
  Primitive scope meta name -> plain 17 (s scope <> r meta <> r name)
  Constructor scope meta name arity -> plain 18 (s scope <> r meta <> r name <> n arity)
  Literal scope meta kind value -> plain 19 (s scope <> r meta <> r kind <> r value)
  Application scope meta function arguments lifted hnf speculation -> plain 20
    (s scope <> r meta <> r function <> r arguments <> r lifted <> flag hnf <> flag speculation)
  Lambda scope meta bodyScope binders body -> plain 21
    (s scope <> r meta <> s bodyScope <> r binders <> r body)
  Let scope meta recursive bodyScope bindings body -> plain 22
    (s scope <> r meta <> flag recursive <> s bodyScope <> r bindings <> r body)
  Case scope meta branchScope binder scrutinee alternatives -> plain 23
    (s scope <> r meta <> s branchScope <> b binder <> r scrutinee <> r alternatives)
  Void scope meta -> plain 24 (s scope <> r meta)
  Unsupported scope reason -> plain 25 (s scope <> r reason)
  Definition binder expression fields position -> plain 32 (b binder <> r expression <> r fields <> n position)
  Declaration binder fields -> plain 33 (b binder <> r fields)
  Alternative bodyScope kind discriminator binders body meta -> plain 34
    (s bodyScope <> BB.word8 (case kind of DefaultAlt -> 0; DataAlt -> 1; LiteralAlt -> 2) <>
      r discriminator <> r binders <> r body <> r meta)
  BinderVector binders -> vector 35 unBinder b binders
  where
    plain = emit initial
    emit next tag fields = (next,bytes (BB.word8 tag <> fields))
    vector tag unwrap encode values
      | length values <= 16 = plain tag (n (length values) <> BB.word8 0 <> foldMap encode values)
      | otherwise =
          let payload = bytes (foldMap (BB.word32LE . fromIntegral . unwrap) values)
              (offset,next) = appendPayload payload initial
          in emit next tag (n (length values) <> BB.word8 1 <> BB.word64LE offset)
    digits 0 = []
    digits value = fromInteger (value .&. 255) : digits (value `shiftR` 8)

chunks :: Int -> [a] -> [[a]]
chunks _ [] = []
chunks size values = let (prefix,suffix) = splitAt size values in prefix : chunks size suffix

page :: [BS.ByteString] -> Either String BS.ByteString
page values = do
  let offsets = scanl (+) 0 (map BS.length values)
      total = 2 + 2 * (length values + 1) + last offsets
  unless (not (null values) && length values <= 256 && total <= 65536 && last offsets <= 65535)
    (Left "Core record page exceeds bounded format")
  pure $ bytes (BB.word16LE (fromIntegral (length values)) <>
    foldMap (BB.word16LE . fromIntegral) offsets <> foldMap BB.byteString values)

payloadPages :: BS.ByteString -> [BS.ByteString]
payloadPages value
  | BS.null value = []
  | otherwise = let (prefix,suffix) = BS.splitAt 65536 value in prefix : payloadPages suffix

encodeStore :: Store -> Either String Encoded
encodeStore store = do
  let nodes = toList (storeNodes store)
      count = length nodes
      scopeRows = toList (storeScopes store)
      binderRows = toList (storeBinders store)
      symbols = storeSymbols store
  mapM_ (\(label,value) -> unless (toInteger value < toInteger (maxBound :: Word32))
    (Left ("Core store " ++ label ++ " count exceeds format")))
    [("record",count),("scope",length scopeRows),("binder",length binderRows),("symbol",length symbols)]
  unless (unRef (storeRoot store) >= 0 && unRef (storeRoot store) < count)
    (Left "Core store root is outside records")
  mapM_ (\(index,node) -> unless (all (\ref -> unRef ref >= 0 && unRef ref < index) (children node))
    (Left "Core store structural reference is not backwards")) (zip [0..] nodes)
  let (Payload _ payload, encoded) = mapAccumL record (Payload 0 mempty) nodes
  pages <- mapM page (chunks 256 encoded)
  let large = payloadPages (bytes payload)
      blocks = pages ++ large
      indexPrefix = bytes (BB.word32LE (fromIntegral (unRef (storeRoot store))) <>
        BB.word32LE (fromIntegral (length symbols)) <>
        foldMap (BB.word32LE . maybe maxBound (fromIntegral . unScope)) scopeRows <>
        foldMap binderRow binderRows <> foldMap symbolRow symbols)
      indexSize = fromIntegral (BS.length indexPrefix + 48 * length blocks)
      (_, rows) = mapAccumL blockRow (64 + indexSize) blocks
      index = indexPrefix <> BS.concat rows
      total = 64 + indexSize + sum (map (fromIntegral . BS.length) blocks)
      header = bytes (BB.string8 "THCCORE\0" <> BB.word16LE 1 <> BB.word16LE 0 <>
        BB.word32LE 64 <> BB.word64LE total <> BB.word64LE 64 <> BB.word64LE indexSize <>
        BB.word32LE (fromIntegral count) <> BB.word32LE (fromIntegral (length scopeRows)) <>
        BB.word32LE (fromIntegral (length binderRows)) <> BB.word32LE (fromIntegral (length pages)) <>
        BB.word32LE (fromIntegral (length large)) <> BB.word32LE 0)
      authenticated = header <> index
  pure (Encoded authenticated (SHA256.hash authenticated) blocks total)
  where
    binderRow info = BB.word32LE (fromIntegral (unScope (binderScope info))) <>
      BB.word32LE (fromIntegral (unRef (binderName info))) <>
      BB.word32LE (fromIntegral (unRef (binderDeclaration info)))
    symbolRow (name,definition) = BB.word32LE (fromIntegral (unRef name)) <>
      BB.word32LE (fromIntegral (unRef definition))
    blockRow offset block =
      let size = fromIntegral (BS.length block)
      in (offset + size, bytes (BB.word64LE offset <> BB.word32LE (fromIntegral size) <>
        BB.word32LE 0 <> BB.byteString (SHA256.hash block)))

-- | Bounded tests/inspection convenience. Production export writes the parts
-- separately, avoiding another contiguous copy of the complete module.
encodedBytes :: Encoded -> BS.ByteString
encodedBytes value = BS.concat (encodedHeaderIndex value : encodedBlocks value)

writeEncoded :: FilePath -> Encoded -> IO ()
writeEncoded path value = withBinaryFile path WriteMode $ \handle ->
  mapM_ (BS.hPut handle) (encodedHeaderIndex value : encodedBlocks value)
