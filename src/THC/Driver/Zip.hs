-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Small deterministic ZIP/STORED codec for immutable Core bundles. No native
-- archiver, compression library, timestamps, or directory entries are needed.
module THC.Driver.Zip (encodeZip, decodeZip) where

import Data.Bits (complement, shiftR, testBit, xor)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy as BL
import Data.List (mapAccumL)
import Data.Word (Word16, Word32)

encodeZip :: [(String, BS.ByteString)] -> Either String BL.ByteString
encodeZip files = do
  let prepared = [(name, BS.pack (map (fromIntegral . fromEnum) name), bytes,
                   fromIntegral (BS.length bytes) :: Word32, crc32 bytes)
                 | (name, bytes) <- files]
  if any (not . safeName . first5) prepared then Left "invalid ZIP member name" else pure ()
  if any (\(_, name, bytes, _, _) -> BS.length name > 65535 || toInteger (BS.length bytes) > 0xffffffff) prepared
    then Left "Core bundle exceeds ZIP32 entry limits" else pure ()
  let (size, records) = mapAccumL add (0 :: Integer) prepared
      central = mconcat [centralRecord offset name size' crc | (offset, _, name, _, size', crc) <- records]
      centralSize = sum [46 + toInteger (BS.length name) | (_, _, name, _, _, _) <- records]
  if length files > 65535 || size > 0xffffffff || centralSize > 0xffffffff
    then Left "Core bundle exceeds ZIP32 directory limits"
    else pure $ Builder.toLazyByteString $
      mconcat [localRecord name bytes size' crc | (_, _, name, bytes, size', crc) <- records] <>
      central <> endRecord (fromIntegral (length files)) (fromIntegral centralSize) (fromIntegral size)
  where
    first5 (name, _, _, _, _) = name
    add offset (name, encoded, bytes, size', crc) =
      (offset + 30 + toInteger (BS.length encoded) + toInteger size',
       (fromIntegral offset :: Word32, name, encoded, bytes, size', crc))

decodeZip :: BS.ByteString -> Either String [(String, BS.ByteString)]
decodeZip bytes = go 0 []
  where
    total = BS.length bytes
    go offset entries
      | offset + 4 > total = Left "truncated Core ZIP"
      | word32 offset == 0x02014b50 = Right (reverse entries)
      | word32 offset /= 0x04034b50 = Left "invalid Core ZIP local header"
      | offset + 30 > total = Left "truncated Core ZIP local header"
      | word16 (offset + 6) /= 0x0800 || word16 (offset + 8) /= 0 =
          Left "unsupported Core ZIP compression or flags"
      | otherwise = do
          let size = fromIntegral (word32 (offset + 18))
              original = word32 (offset + 22)
              nameLength = fromIntegral (word16 (offset + 26))
              extraLength = fromIntegral (word16 (offset + 28))
              nameStart = offset + 30
              bodyStart = nameStart + nameLength + extraLength
              end = bodyStart + size
          if original /= fromIntegral size || end > total
            then Left "truncated Core ZIP member"
            else do
              let name = map (toEnum . fromIntegral) (BS.unpack (BS.take nameLength (BS.drop nameStart bytes)))
                  body = BS.take size (BS.drop bodyStart bytes)
              if not (safeName name) || any ((== name) . fst) entries ||
                 crc32 body /= word32 (offset + 14)
                then Left "invalid Core ZIP member"
                else go end ((name, body) : entries)
    word16 at = fromIntegral (BS.index bytes at) +
      fromIntegral (BS.index bytes (at + 1)) * 256 :: Word16
    word32 at = fromIntegral (word16 at) +
      fromIntegral (word16 (at + 2)) * 65536 :: Word32

safeName :: String -> Bool
safeName name = case name of
  [] -> False
  '/':_ -> False
  _ -> all (\c -> c /= '\\' && c /= '\0' && fromEnum c < 128) name &&
       all (\part -> not (null part) && part /= "." && part /= "..") (split name)
  where
    split value = case break (== '/') value of
      (part, []) -> [part]
      (part, _:rest) -> part : split rest

crc32 :: BS.ByteString -> Word32
crc32 = complement . BS.foldl' step 0xffffffff
  where
    step crc byte = foldl turn (crc `xor` fromIntegral byte) [1 :: Int .. 8]
    turn value _ = (value `shiftR` 1) `xor` (if testBit value 0 then 0xedb88320 else 0)

localRecord :: BS.ByteString -> BS.ByteString -> Word32 -> Word32 -> Builder.Builder
localRecord name bytes size crc =
  Builder.word32LE 0x04034b50 <> Builder.word16LE 20 <> Builder.word16LE 0x0800 <>
  Builder.word16LE 0 <> Builder.word16LE 0 <> Builder.word16LE 0 <>
  Builder.word32LE crc <> Builder.word32LE size <> Builder.word32LE size <>
  Builder.word16LE (fromIntegral $ BS.length name) <> Builder.word16LE 0 <>
  Builder.byteString name <> Builder.byteString bytes

centralRecord :: Word32 -> BS.ByteString -> Word32 -> Word32 -> Builder.Builder
centralRecord offset name size crc =
  Builder.word32LE 0x02014b50 <> Builder.word16LE 20 <> Builder.word16LE 20 <>
  Builder.word16LE 0x0800 <> Builder.word16LE 0 <> Builder.word16LE 0 <> Builder.word16LE 0 <>
  Builder.word32LE crc <> Builder.word32LE size <> Builder.word32LE size <>
  Builder.word16LE (fromIntegral $ BS.length name) <> Builder.word16LE 0 <>
  Builder.word16LE 0 <> Builder.word16LE 0 <> Builder.word16LE 0 <>
  Builder.word32LE 0 <> Builder.word32LE offset <> Builder.byteString name

endRecord :: Word16 -> Word32 -> Word32 -> Builder.Builder
endRecord count size offset =
  Builder.word32LE 0x06054b50 <> Builder.word16LE 0 <> Builder.word16LE 0 <>
  Builder.word16LE count <> Builder.word16LE count <> Builder.word32LE size <>
  Builder.word32LE offset <> Builder.word16LE 0
