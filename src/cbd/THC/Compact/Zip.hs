-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Zip
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; zlib, binary temporary handles
--
-- Bounded deterministic ZIP assembly from already counted/CRC'd fragments.
-- ZIP64 fields are emitted only where the ordinary field cannot hold a value.
module THC.Compact.Zip
  ( ZipSource(..), writeZip, readZip, readZipWithMethods, readZipHeader, readZipHeaderFile, finalizeHeaderFile
  , zipLocalHeader, zipCentralHeader, zipEnd ) where

import qualified Codec.Compression.Zlib.Internal as Z
import qualified Codec.Compression.Zlib.Raw as Raw
import Control.Exception (IOException, bracket, catch)
import Control.Monad (foldM, unless, replicateM)
import Control.Monad.ST.Lazy (runST)
import Control.Monad.Trans.Class (lift)
import Control.Monad.Trans.Except (ExceptT(..), except, runExceptT, throwE)
import Data.Binary.Get hiding (remaining)
import Data.Binary.Put
import Data.Bits ((.&.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as B8
import qualified Data.ByteString.Lazy as BL
import Data.Digest.CRC32 (crc32, crc32Update)
import Data.Functor.Identity (Identity(..))
import Data.Word (Word16, Word32, Word64)
import System.Directory (removeFile)
import System.IO

-- | CRC and uncompressed length are accumulated during original emission, not
-- by rescanning a STORED fragment. Handles remain owned by the caller.
data ZipSource = ZipSource !String !Int !Word64 !Word32 !Handle

-- | Assemble in supplied order without seeking the destination. Deflate uses
-- one scoped compressed temporary per member, permitting actual size fields
-- and no data descriptors. Only the small central directory stays in memory.
writeZip :: FilePath -> Handle -> [ZipSource] -> IO ()
writeZip temporaryDirectory output sources = do
  (!directoryOffset,entries) <- foldM member (0,[]) sources
  let directory = runPut (mapM_ snd (reverse entries))
      directorySize = fromIntegral (BL.length directory)
  _ <- checkedAdd directoryOffset directorySize
  BL.hPut output directory
  BL.hPut output (runPut (zipEnd (fromIntegral (length sources)) directoryOffset directorySize))
  where
    member (!offset,entries) (ZipSource name level size crc input) = do
      unless (all (\c -> c >= 'a' && c <= 'z' || c == '-') name && not (null name))
        (fail "Invalid CBD ZIP member name")
      unless (level >= 0 && level <= 9) (fail "Invalid CBD ZIP compression level")
      hFlush input
      hSeek input AbsoluteSeek 0
      let emit method compressedSize source = do
            let local = runPut (zipLocalHeader name method crc size compressedSize)
                central = zipCentralHeader name method crc size compressedSize offset
                localSize = fromIntegral (BL.length local)
            end <- checkedAdd offset localSize >>= (`checkedAdd` compressedSize)
            BL.hPut output local
            copyExactly source output compressedSize
            pure (end,(name,central):entries)
      if level == 0 then emit 0 size input else
        bracket (openBinaryTempFile temporaryDirectory "cbd-deflate.tmp") cleanup $ \(_,compressed) -> do
          compressedSize <- deflate input compressed size level
          hFlush compressed
          hSeek compressed AbsoluteSeek 0
          emit 8 compressedSize compressed

deflate :: Handle -> Handle -> Word64 -> Int -> IO Word64
deflate input output expected level = go 0 0 (Z.compressIO Z.rawFormat
  Z.defaultCompressParams {Z.compressLevel=Z.compressionLevel level})
  where
    go !readBytes !written state = case state of
      Z.CompressInputRequired next -> do
        bytes <- BS.hGetSome input 65536
        count <- checkedAdd readBytes (fromIntegral (BS.length bytes))
        unless (count <= expected) (fail "CBD fragment grew while compressing")
        next bytes >>= go count written
      Z.CompressOutputAvailable bytes next -> do
        count <- checkedAdd written (fromIntegral (BS.length bytes))
        BS.hPut output bytes
        next >>= go readBytes count
      Z.CompressStreamEnd -> do
        unless (readBytes == expected) (fail "CBD fragment truncated while compressing")
        pure written

copyExactly :: Handle -> Handle -> Word64 -> IO ()
copyExactly input output = go
  where
    go 0 = pure ()
    go remaining = do
      bytes <- BS.hGetSome input (fromIntegral (min 65536 remaining))
      unless (not (BS.null bytes)) (fail "Truncated CBD construction fragment")
      BS.hPut output bytes
      go (remaining-fromIntegral (BS.length bytes))

-- | Ordinary local file header. ZIP64 sizes are paired in local extras.
zipLocalHeader :: String -> Word16 -> Word32 -> Word64 -> Word64 -> Put
zipLocalHeader name method crc size compressed = do
  putWord32le 0x04034b50
  putWord16le (if big then 45 else 20)
  putWord16le 0
  putWord16le method
  putWord16le 0
  putWord16le 33 -- 1980-01-01, the first DOS date
  putWord32le crc
  putWord32le (if big then maxBound else fromIntegral compressed)
  putWord32le (if big then maxBound else fromIntegral size)
  putWord16le (fromIntegral (length name))
  putWord16le (if big then 20 else 0)
  putByteString (B8.pack name)
  if big then putWord16le 1 >> putWord16le 16 >> putWord64le size >> putWord64le compressed else pure ()
  where big = large size || large compressed

-- | Central directory entry. ZIP64 fields follow the order of sentinel fields.
zipCentralHeader :: String -> Word16 -> Word32 -> Word64 -> Word64 -> Word64 -> Put
zipCentralHeader name method crc size compressed offset = do
  putWord32le 0x02014b50
  putWord16le 45 -- DOS host, ZIP 4.5 encoder
  putWord16le (if null extra then 20 else 45)
  putWord16le 0
  putWord16le method
  putWord16le 0
  putWord16le 33
  putWord32le crc
  putWord32le (narrow compressed)
  putWord32le (narrow size)
  putWord16le (fromIntegral (length name))
  putWord16le (if null extra then 0 else fromIntegral (4+8*length extra))
  putWord16le 0
  putWord16le 0
  putWord16le 0
  putWord32le 0
  putWord32le (narrow offset)
  putByteString (B8.pack name)
  unless (null extra) (putWord16le 1 >> putWord16le (fromIntegral (8*length extra)) >> mapM_ putWord64le extra)
  where extra = [value | value <- [size,compressed,offset], large value]

-- | Standard end records; there are never disks, comments or appended payloads.
zipEnd :: Word64 -> Word64 -> Word64 -> Put
zipEnd count offset size = do
  if big then do
    putWord32le 0x06064b50
    putWord64le 44
    putWord16le 45
    putWord16le 45
    putWord32le 0
    putWord32le 0
    putWord64le count
    putWord64le count
    putWord64le size
    putWord64le offset
    putWord32le 0x07064b50
    putWord32le 0
    putWord64le (offset+size)
    putWord32le 1
  else pure ()
  putWord32le 0x06054b50
  putWord16le 0
  putWord16le 0
  putWord16le count16
  putWord16le count16
  putWord32le (narrow size)
  putWord32le (narrow offset)
  putWord16le 0
  where
    big = count >= 65535 || large offset || large size
    count16 = if count >= 65535 then maxBound else fromIntegral count

large :: Word64 -> Bool
large value = value >= 0xffffffff
narrow :: Word64 -> Word32
narrow value = if large value then maxBound else fromIntegral value
checkedAdd :: Word64 -> Word64 -> IO Word64
checkedAdd a b | b <= maxBound-a = pure $! a+b
               | otherwise = fail "CBD ZIP offset overflow"
cleanup :: (FilePath,Handle) -> IO ()
cleanup (path,handle) = do
  hClose handle `catch` ignore
  removeFile path `catch` ignore
  where ignore :: IOException -> IO (); ignore _ = pure ()

-- | Explicit native inspection of one archive. This validates CRCs and inflates
-- every member; it is not the runtime's demand-loading implementation. Directory
-- and ZIP64 bounds are checked before slicing, and inflation obeys declared size.
readZip :: BS.ByteString -> Either String [(String,BS.ByteString)]
readZip bytes = map (\(name,_,payload) -> (name,payload)) <$> readZipWithMethods bytes

-- | Retain the actual ZIP method when an existing container is amended.
readZipWithMethods :: BS.ByteString -> Either String [(String,Word16,BS.ByteString)]
readZipWithMethods bytes = do
  (offset,entries) <- readZipDirectory bytes
  mapM (contents bytes offset . fst) entries

readZipHeader :: BS.ByteString -> Either String (BS.ByteString,[Word64])
readZipHeader bytes = do
  (offset,entries) <- readZipDirectory bytes
  (_,_,payload) <- contents bytes offset (fst (last entries))
  pure (payload,[size | (Entry _ _ _ _ size _ _,_) <- take 6 entries])

-- | Finalize an OWNED STAGING file, never a published immutable cache entry.
-- Seek to the last local record and replace only that tail. The six payload
-- payload bytes are neither read nor written. Their raw central records retain
-- their exact bytes and offsets. Prepare the complete new tail before writing.
finalizeHeaderFile :: FilePath -> (BS.ByteString -> [Word64] -> IO (Maybe BS.ByteString)) -> IO ()
finalizeHeaderFile path prepare = withBinaryFile path ReadWriteMode $ \handle -> do
  (entries,oldHeader) <- fileHeader handle
  let (Entry _ _ method _ _ _ offset,_) = last entries
      lengths = [length' | (Entry _ _ _ _ length' _ _,_) <- take 6 entries]
  changed <- prepare oldHeader lengths
  case changed of
    Nothing -> pure ()
    Just header -> do
      let compressed = if method == 0 then header else BL.toStrict (Raw.compress (BL.fromStrict header))
          newSize = fromIntegral (BS.length header)
          newCompressed = fromIntegral (BS.length compressed)
          crc = crc32 header
          local = BL.toStrict (runPut (zipLocalHeader "header" method crc newSize newCompressed)) <> compressed
          central = BS.concat (map snd (take 6 entries)) <>
            BL.toStrict (runPut (zipCentralHeader "header" method crc newSize newCompressed offset))
      nextDirectory <- checkedAdd offset (fromIntegral (BS.length local))
      let tailBytes = local <> central <>
            BL.toStrict (runPut (zipEnd 7 nextDirectory (fromIntegral (BS.length central))))
      end <- checkedAdd offset (fromIntegral (BS.length tailBytes))
      hSeek handle AbsoluteSeek (fromIntegral offset)
      BS.hPut handle tailBytes
      hSetFileSize handle (fromIntegral end)

readZipHeaderFile :: FilePath -> IO (BS.ByteString,[Word64])
readZipHeaderFile path = withBinaryFile path ReadMode $ \handle -> do
  (entries,header) <- fileHeader handle
  pure (header,[size | (Entry _ _ _ _ size _ _,_) <- take 6 entries])

fileHeader :: Handle -> IO ([(Entry,BS.ByteString)],BS.ByteString)
fileHeader handle = do
  extent <- hFileSize handle
  unless (extent <= fromIntegral (maxBound :: Word64)) (fail "CBD ZIP exceeds uint64")
  let size = fromIntegral extent
      fetch start count = do
        unless (start <= size && count <= size-start && count <= fromIntegral (maxBound :: Int))
          (throwE "ZIP extent exceeds archive")
        lift (hSeek handle AbsoluteSeek (fromIntegral start))
        bytes <- lift (BS.hGet handle (fromIntegral count))
        unless (fromIntegral (BS.length bytes) == count) (throwE "Truncated CBD staging file")
        pure bytes
  (directoryOffset,entries) <- either fail pure =<< runExceptT (readDirectory size fetch)
  let (headerEntry@(Entry _ flags method checksum headerSize compressedSize _),_) = last entries
  unless (flags == 0) (fail "Final CBD header has unsupported ZIP flags")
  oldHeader <- either fail pure =<< runExceptT (do
    -- Owned staging files have our canonical local framing. Check its extents
    -- from the directory, without visiting payload headers: even an unbuffered
    -- GHC Handle may read ahead from a small header into the following payload.
    finalOffset <- foldM (\expected (Entry name entryFlags entryMethod crc size' count offset,_) -> do
      unless (entryFlags == 0 && offset == expected) (throwE "CBD staging records are not contiguous")
      let framing = fromIntegral (BL.length (runPut (zipLocalHeader name entryMethod crc size' count)))
      unless (offset <= directoryOffset && framing <= directoryOffset-offset)
        (throwE "CBD staging local header overlaps directory")
      let start = offset+framing
      unless (count <= directoryOffset-start) (throwE "CBD staging member overlaps directory")
      pure (start+count)) 0 (take 6 entries)
    let Entry _ _ _ _ _ _ headerOffset = headerEntry
    unless (finalOffset == headerOffset) (throwE "CBD header does not follow payload records")
    start <- entryStart fetch directoryOffset headerEntry
    unless (compressedSize == directoryOffset-start) (throwE "CBD header is not the final local record")
    compressed <- fetch start compressedSize
    except (uncompress method checksum headerSize compressedSize compressed))
  pure (entries,oldHeader)

readZipDirectory :: BS.ByteString -> Either String (Word64,[(Entry,BS.ByteString)])
readZipDirectory bytes = runIdentity (runExceptT
  (readDirectory (fromIntegral (BS.length bytes)) (\start size -> except (sliceZip bytes start size))))

readDirectory :: Monad m => Word64 -> (Word64 -> Word64 -> ExceptT String m BS.ByteString) ->
  ExceptT String m (Word64,[(Entry,BS.ByteString)])
readDirectory sizeOfFile fetch = do
  unless (sizeOfFile >= 22) (throwE "Missing ZIP end record")
  let end = sizeOfFile-22
  tailBytes <- fetch end 22
  (count,offset,size,boundary) <- endRecord end =<< except (decode endOnly tailBytes)
  unless (count == 7) (throwE "CBD ZIP requires seven members")
  unless (offset <= boundary && size == boundary-offset) (throwE "Invalid ZIP directory extent")
  directory <- fetch offset size
  rows <- except $ decode (replicateM 7 $ do
    start <- bytesRead
    entry <- centralEntry
    finish <- bytesRead
    pure (entry,start,finish)) directory
  let entries = [(entry,BS.take (fromIntegral (finish-start)) (BS.drop (fromIntegral start) directory)) |
        (entry,start,finish) <- rows]
      names = [name | (Entry name _ _ _ _ _ _,_) <- entries]
  unless (names == ["data","strings","names","filenames","line-columns","symbols","header"])
    (throwE "Invalid CBD ZIP member order")
  let offsets = [position | (Entry _ _ _ _ _ _ position,_) <- entries]
  unless (and (zipWith (<) offsets (drop 1 offsets))) (throwE "Invalid CBD ZIP physical member order")
  pure (offset,entries)
  where
    endOnly = do
      signature 0x06054b50
      disk <- getWord16le; directoryDisk <- getWord16le
      diskCount <- getWord16le; count <- getWord16le
      size <- getWord32le; offset <- getWord32le
      commentSize <- getWord16le
      unless (commentSize == 0) (fail "CBD ZIP comments are unsupported")
      unless (disk == 0 && directoryDisk == 0 && diskCount == count) (fail "Multi-disk ZIP is unsupported")
      pure (count,offset,size)
    endRecord end (count,offset,size) = do
      if count /= maxBound && offset /= maxBound && size /= maxBound
        then pure (fromIntegral count,fromIntegral offset,fromIntegral size,fromIntegral end)
        else do
          unless (end >= 20) (throwE "Truncated ZIP64 locator")
          locator <- fetch (end-20) 20
          zip64Offset <- except $ decode (do
            signature 0x07064b50
            disk <- getWord32le; position <- getWord64le; disks <- getWord32le
            unless (disk == 0 && disks == 1) (fail "Invalid ZIP64 locator")
            pure position) locator
          record <- fetch zip64Offset 56
          (wideCount,wideOffset,wideSize) <- except $ decode (do
            signature 0x06064b50
            recordLength <- getWord64le
            unless (recordLength == 44) (fail "Unsupported ZIP64 end extension")
            skip 4
            disk <- getWord32le; directoryDisk <- getWord32le
            diskCount <- getWord64le; total <- getWord64le
            wideSize <- getWord64le; wideOffset <- getWord64le
            unless (disk == 0 && directoryDisk == 0 && diskCount == total) (fail "Invalid ZIP64 end record")
            pure (total,wideOffset,wideSize)) record
          unless (zip64Offset <= end && end-zip64Offset == 76) (throwE "Invalid ZIP64 end bounds")
          pure (wideCount,wideOffset,wideSize,zip64Offset)

sliceZip :: BS.ByteString -> Word64 -> Word64 -> Either String BS.ByteString
sliceZip bytes start size
  | start <= fromIntegral (BS.length bytes) && size <= fromIntegral (BS.length bytes)-start =
      Right (BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes))
  | otherwise = Left "ZIP extent exceeds archive"

compressedContents :: BS.ByteString -> Word64 -> Entry -> Either String (Word64,BS.ByteString)
compressedContents bytes directoryOffset (Entry name flags method checksum size compressed offset) = do
      let entry = Entry name flags method checksum size compressed offset
      start <- runIdentity (runExceptT (entryStart (\at count -> except (sliceZip bytes at count)) directoryOffset entry))
      payload <- sliceZip bytes start compressed
      pure (start,payload)

entryStart :: Monad m => (Word64 -> Word64 -> ExceptT String m BS.ByteString) -> Word64 -> Entry -> ExceptT String m Word64
entryStart fetch directoryOffset (Entry name flags method checksum size compressed offset) = do
      unless (offset <= directoryOffset && directoryOffset-offset >= 30) (throwE "ZIP local header overlaps directory")
      fixed <- fetch offset 30
      (localFlags,localMethod,localCrc,localCompressed,localSize,nameSize,extraSize) <- except $ decode (do
        signature 0x04034b50
        skip 2
        f <- getWord16le; m <- getWord16le
        skip 4
        c <- getWord32le; z <- getWord32le; s <- getWord32le
        n <- getWord16le; x <- getWord16le
        pure (f,m,c,z,s,n,x)) fixed
      let variableSize = fromIntegral nameSize+fromIntegral extraSize
      variable <- fetch (offset+30) variableSize
      unless (B8.unpack (BS.take (fromIntegral nameSize) variable) == name && flags == localFlags && method == localMethod)
        (throwE "ZIP local/directory identity mismatch")
      localExtra <- except (decode extras (BS.drop (fromIntegral nameSize) variable))
      actualSizes <- except (widen localExtra [localSize,localCompressed])
      unless (flags .&. 8 /= 0 || (checksum == localCrc && actualSizes == [size,compressed]))
        (throwE "ZIP local/directory size or CRC mismatch")
      let start = offset+30+variableSize
      unless (start <= directoryOffset && compressed <= directoryOffset-start) (throwE "ZIP member overlaps directory")
      pure start

contents :: BS.ByteString -> Word64 -> Entry -> Either String (String,Word16,BS.ByteString)
contents bytes directoryOffset entry@(Entry name _ method checksum size compressed _) = do
      (_,payload) <- compressedContents bytes directoryOffset entry
      result <- uncompress method checksum size compressed payload
      pure (name,method,result)

uncompress :: Word16 -> Word32 -> Word64 -> Word64 -> BS.ByteString -> Either String BS.ByteString
uncompress method checksum size compressed payload = do
      (result,actualCrc) <- case method of
        0 -> do
          unless (size == compressed) (Left "STORED ZIP sizes disagree")
          pure (payload,crc32 payload)
        8 -> inflate size payload
        _ -> Left "Unsupported ZIP compression method"
      unless (actualCrc == checksum) (Left "ZIP member CRC mismatch")
      pure result

data Entry = Entry !String !Word16 !Word16 !Word32 !Word64 !Word64 !Word64

centralEntry :: Get Entry
centralEntry = do
  signature 0x02014b50
  skip 4
  flags <- getWord16le; method <- getWord16le
  unless (flags .&. 0xf7f1 == 0 && (method == 0 || method == 8)) (fail "Unsupported ZIP flags or method")
  skip 4
  checksum <- getWord32le; compressed <- getWord32le; size <- getWord32le
  nameSize <- getWord16le; extraSize <- getWord16le; commentSize <- getWord16le
  disk <- getWord16le
  unless (disk == 0) (fail "Multi-disk ZIP member")
  skip 6
  offset <- getWord32le
  name <- B8.unpack <$> getByteString (fromIntegral nameSize)
  extra <- getByteString (fromIntegral extraSize) >>= either fail pure . decode extras
  skip (fromIntegral commentSize)
  values <- either fail pure (widen extra [size,compressed,offset])
  case values of
    [s,c,o] -> pure (Entry name flags method checksum s c o)
    _ -> fail "Invalid ZIP64 entry"

extras :: Get [(Word16,BS.ByteString)]
extras = do
  empty <- isEmpty
  if empty then pure [] else do
    tag <- getWord16le; size <- getWord16le
    value <- getByteString (fromIntegral size)
    ((tag,value):) <$> extras

widen :: [(Word16,BS.ByteString)] -> [Word32] -> Either String [Word64]
widen extra values = do
  let needed = length (filter (==maxBound) values)
  wide <- if needed == 0 then Right [] else case [body | (1,body) <- extra] of
    [body] -> decode (replicateM needed getWord64le) body
    _ -> Left "Missing or duplicate ZIP64 size fields"
  pure (replaceWide values wide)
  where
    replaceWide [] _ = []
    replaceWide (value:rest) wide
      | value /= maxBound = fromIntegral value:replaceWide rest wide
      | otherwise = case wide of next:more -> next:replaceWide rest more; [] -> []

inflate :: Word64 -> BS.ByteString -> Either String (BS.ByteString,Word32)
inflate expected compressed = runST (go [compressed] 0 0 []
  (Z.decompressST Z.rawFormat Z.defaultDecompressParams))
  where
    go input !size !checksum chunks state = case state of
      Z.DecompressInputRequired next -> case input of
        [] -> next BS.empty >>= go [] size checksum chunks
        bytes:rest -> next bytes >>= go rest size checksum chunks
      Z.DecompressOutputAvailable bytes next
        | fromIntegral (BS.length bytes) > expected-size -> pure (Left "ZIP inflated size exceeds declaration")
        | otherwise -> next >>= go input (size+fromIntegral (BS.length bytes)) (crc32Update checksum bytes) (bytes:chunks)
      Z.DecompressStreamEnd trailing -> pure $ do
        unless (size == expected && BS.null trailing && null input) (Left "ZIP inflated extent mismatch")
        pure (BS.concat (reverse chunks),checksum)
      Z.DecompressStreamError problem -> pure (Left (show problem))

signature :: Word32 -> Get ()
signature expected = getWord32le >>= \actual -> unless (actual == expected) (fail "Invalid ZIP signature")
decode :: Get a -> BS.ByteString -> Either String a
decode parser bytes = case runGetOrFail parser (BL.fromStrict bytes) of
  Left (_,_,problem) -> Left problem
  Right (rest,_,value) | BL.null rest -> Right value
                      | otherwise -> Left "Trailing ZIP record bytes"
