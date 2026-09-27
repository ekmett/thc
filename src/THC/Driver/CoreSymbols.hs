-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.CoreSymbols
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; Aeson and host filesystem services
--
-- Publish immutable, directly seekable unit JSON from final linked Core bytes.
-- ZIPs remain acquisition/provenance caches; guest requests use the plain pair.
module THC.Driver.CoreSymbols (bindingPositions, publishCoreUnit) where

import Control.DeepSeq (force)
import Control.Exception (IOException, bracketOnError, catch, evaluate)
import Control.Monad (foldM, forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), FromJSON, Result(..), eitherDecodeStrict', encode, fromJSON, object, toJSON, (.=))
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Data.Maybe (fromMaybe)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Word (Word64)
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing, doesFileExist, getFileSize, makeAbsolute, removeFile, renameFile)
import System.FilePath ((</>), isAbsolute, takeDirectory)
import System.IO (Handle, IOMode(ReadMode), hClose, hTell, openBinaryTempFile, withBinaryFile)
import THC.CoreSymbols (encodeMd5Symbols, symbolDigest, symbolFormat)
import THC.Driver.Lock (withLock)
import THC.Driver.Zip (ZipMember, decodeZipMembers, readZipMember)

-- | Locate bindings in final producer bytes, including amended package-native
-- JSON whose field order may differ from the plugin's. This is publication work,
-- not a runtime pre-scan. Aeson validates the document and decodes exact IDs;
-- the small byte walk records positions without reserializing any value.
bindingPositions :: BS.ByteString -> Either String ([(BS.ByteString, Word64)], (Word64, Word64))
bindingPositions bytes = do
  _ <- eitherDecodeStrict' bytes :: Either String Value
  bindingPositionsValidated bytes

-- The publication caller has already parsed and checked this exact document.
-- Avoid allocating a second complete Aeson tree merely to locate its bytes.
bindingPositionsValidated :: BS.ByteString -> Either String ([(BS.ByteString, Word64)], (Word64, Word64))
bindingPositionsValidated bytes = do
  members <- fields (white 0)
  (start, end) <- case [span' | ("bindings", span') <- members] of
    [span'] -> Right span'
    _ -> Left "Core module requires one bindings array"
  expect start 91
  rows <- elements (white (start + 1))
  pure (rows, (fromIntegral start, fromIntegral end))
  where
    size = BS.length bytes
    byte at | at >= 0 && at < size = Right (BS.index bytes at)
            | otherwise = Left "Truncated Core JSON while locating bindings"
    white at | at < size && BS.index bytes at `elem` [9,10,13,32] = white (at + 1)
             | otherwise = at
    expect at wanted = do
      actual <- byte at
      unless (actual == wanted) (Left "Unexpected Core JSON delimiter")
    stringEnd at = expect at 34 >> quoted (at + 1)
    quoted at = do
      item <- byte at
      case item of
        34 -> Right (at + 1)
        92 -> byte (at + 1) >> quoted (at + 2)
        _ -> quoted (at + 1)
    textAt start end = eitherDecodeStrict' (BS.take (end - start) (BS.drop start bytes))
      :: Either String Text.Text
    valueEnd at = do
      item <- byte at
      case item of
        34 -> stringEnd at
        123 -> nested [125] (at + 1)
        91 -> nested [93] (at + 1)
        _ -> Right (scalar at)
    scalar at | at < size && BS.index bytes at `notElem` [9,10,13,32,44,93,125] = scalar (at + 1)
              | otherwise = at
    nested [] at = Right at
    nested stack@(close:rest) at = do
      item <- byte at
      case item of
        34 -> stringEnd at >>= nested stack
        123 -> nested (125:stack) (at + 1)
        91 -> nested (93:stack) (at + 1)
        _ | item == close -> nested rest (at + 1)
          | item `elem` [125,93] -> Left "Mismatched Core JSON delimiter"
          | otherwise -> nested stack (at + 1)
    fields start = expect start 123 >> membersAt (white (start + 1))
    membersAt at = do
      item <- byte at
      if item == 125 then pure [] else do
        keyEnd <- stringEnd at
        key <- textAt at keyEnd
        let colon = white keyEnd
        expect colon 58
        let start = white (colon + 1)
        end <- valueEnd start
        delimiter <- byte (white end)
        rest <- case delimiter of
          125 -> pure []
          44 -> membersAt (white (white end + 1))
          _ -> Left "Invalid Core object separator"
        pure ((key, (start, end)):rest)
    elements at = do
      item <- byte at
      if item == 93 then pure [] else do
        members <- fields at
        (start, end) <- case [span' | ("id", span') <- members] of
          [span'] -> pure span'
          _ -> Left "Core binding requires one ID"
        key <- Text.encodeUtf8 <$> textAt start end
        after <- white <$> valueEnd at
        delimiter <- byte after
        rest <- case delimiter of
          93 -> pure []
          44 -> elements (white (after + 1))
          _ -> Left "Invalid Core bindings separator"
        pure ((key, fromIntegral at):rest)

-- | The source ZIP is already the selected acquisition artifact. On a cold
-- publication, retain each final module byte-for-byte, append LF separators,
-- and record absolute positions while writing the unit. Warm default requests
-- check cache identity and file extents, not whole-file digests. Explicit
-- verification also checks those digests before reusing the pair.
publishCoreUnit :: FilePath -> Bool -> Value -> IO Value
publishCoreUnit cache verify unit = case (member unit "bundle", member unit "modules") of
  (Just bundle, Just (Array modules)) | not (null modules) -> do
    source <- field bundle "path"
    unless (isAbsolute source) $ fail "Unit source bundle path must be absolute"
    sourceHash <- field bundle "sha256"
    unless (length sourceHash == 64 && all (`elem` ("0123456789abcdef" :: String)) sourceHash) $
      fail "Invalid source bundle digest for unit publication"
    directory <- makeAbsolute (cache </> "unit-core/v2" </> sourceHash)
    let jsonPath = directory </> "core.jsons"
        symbolsPath = directory </> "core.symbols"
        receiptPath = directory </> "publication.json"
    createDirectoryIfMissing True directory
    withLock (directory </> "publication.lock") $ do
      cached <- (do
        receipt <- readJson receiptPath
        storedSource <- field receipt "source"
        storedUnit <- field receipt "unit" :: IO Value
        rawSize <- field receipt "jsonSize"
        symbolsSize <- field receipt "symbolsSize"
        unless ((member storedUnit "symbols" >>= (`member` "format")) == Just (toJSON symbolFormat)) $
          fail "Stale unit symbol format"
        refs <- field storedUnit "modules" :: IO [Value]
        unless (all completeRecord refs) $ fail "Incomplete unit publication metadata"
        let originalRefs = map withoutPositions refs
        unless (storedSource == bundle && originalRefs == map (delete "index") (toList modules) &&
                member storedUnit "id" == member unit "id") $ fail "Stale unit publication"
        checkFile verify jsonPath rawSize =<< field storedUnit "json"
        checkFile verify symbolsPath symbolsSize =<< field storedUnit "symbols"
        pure (Just storedUnit)) `catch` absent
      case cached of
        Just ready -> pure (mergePublished unit ready)
        Nothing -> do
          archive <- BS.readFile source
          unless (digest archive == sourceHash) $ fail "Source bundle changed during unit publication"
          entries <- either fail pure =<< decodeZipMembers archive
          inner <- decodeMember entries "manifest.json"
          unless (member inner "modules" == Just (Array modules) && member inner "unit" == member unit "id") $
            fail "Source bundle manifest disagrees with unit publication"
          layout <- targetLayout entries inner
          (refs, rows, jsonHash, jsonSize) <- atomicOutput jsonPath $ \output -> do
            (records, keys, hashState) <- foldM (emit output entries) ([], [], SHA.init) (toList modules)
            size <- hTell output
            pure (reverse records, concat (reverse keys), hex (SHA.finalize hashState), size)
          symbols <- either fail pure (encodeMd5Symbols rows)
          atomicOutput symbolsPath (\output -> BS.hPut output symbols)
          let ready = set "modules" (toArray refs) $ set "json" (reference jsonPath jsonHash) $
                set "symbols" (set "format" (toJSON symbolFormat) (reference symbolsPath (digest symbols))) $ delete "bundle" unit
              withLayout = maybe ready (\value -> set "targetLayout" value ready) layout
              receipt = object ["source" .= bundle, "unit" .= withLayout,
                "jsonSize" .= jsonSize, "symbolsSize" .= BS.length symbols]
          atomicOutput receiptPath (\output -> BS.hPut output (BL.toStrict (encode receipt)))
          pure withLayout
  _ -> pure unit
  where
    absent :: IOException -> IO (Maybe Value)
    absent _ = pure Nothing
    emit output entries (refs, keys, hashState) ref = do
      path <- field ref "path"
      expected <- field ref "sha256"
      bytes <- maybe (fail "Missing Core module during unit publication") readZipMember (lookup path entries)
      unless (digest bytes == expected) $ fail "Core module changed during unit publication"
      value <- either fail pure (eitherDecodeStrict' bytes)
      unless (member value "unit" == member unit "id" && member value "module" == member ref "name" &&
              member value "boundary" == member ref "boundary") $ fail "Core module identity changed during publication"
      (offsets, (arrayStart, arrayEnd)) <- either fail pure (bindingPositionsValidated bytes)
      bindings <- field value "bindings" :: IO [Value]
      name <- field value "module" :: IO String
      needsRegistration <- either fail pure (registration value)
      start <- hTell output
      BS.hPut output bytes
      end <- hTell output
      BS.hPut output "\n"
      metadataStart <- hTell output
      let metadata = BL.toStrict (encode (project metadataKeys value))
      BS.hPut output metadata
      metadataEnd <- hTell output
      BS.hPut output "\n"
      (sourceSpan, sourceBytes) <- if any (\key -> member value key /= Nothing) sourceKeys then do
        sourceStart <- hTell output
        let source = BL.toStrict (encode (project sourceKeys value))
        BS.hPut output source
        sourceEnd <- hTell output
        BS.hPut output "\n"
        pure (Just (sourceStart, sourceEnd), source <> "\n")
        else pure (Nothing, BS.empty)
      let base = fromIntegral start :: Word64
          record = set "start" (integer start) $ set "end" (integer end) $
            set "bindingsStart" (integer (start + fromIntegral arrayStart)) $
            set "bindingsEnd" (integer (start + fromIntegral arrayEnd)) $
            set "metadataStart" (integer metadataStart) $ set "metadataEnd" (integer metadataEnd) $
            set "containsDelimitedControl" (Bool (any (containsControl . fromMaybe Null . (`member` "expr")) bindings)) $
            set "packageScalarDeclarations" (Bool (hasDeclarations value)) $
            set "registrationObligations" (Bool needsRegistration) $ delete "index" ref
          aliased = set "mainAlias" (Bool (any ((== Text.encodeUtf8 (Text.pack ("main::" ++ name ++ ".main"))) . fst) offsets)) record
          sourced = maybe aliased (\(a,b) -> set "sourceMetadataStart" (integer a) $
            set "sourceMetadataEnd" (integer b) aliased) sourceSpan
      -- Only compact records/keys and the updated digest survive this module.
      -- Lazy summaries or a chain of SHA.update thunks retain every preceding
      -- decoded Core tree and byte buffer until the final receipt is encoded.
      compact <- evaluate (force sourced)
      directoryRows <- forM offsets (\(key, offset) -> do
        unless (not (BS.null key)) $ fail "Core symbol directory requires nonempty binding IDs"
        hashed <- symbolDigest key
        pure (hashed, base + offset)) >>= evaluate . force
      nextHash <- evaluate (foldl' SHA.update hashState [bytes, "\n", metadata, "\n", sourceBytes])
      pure (compact:refs, directoryRows:keys, nextHash)
    integer = Number . fromInteger
    reference path hash = object ["path" .= path, "sha256" .= hash]
    toArray = toJSON
    withoutPositions value = foldr delete value
      ["start", "end", "bindingsStart", "bindingsEnd", "metadataStart", "metadataEnd",
       "sourceMetadataStart", "sourceMetadataEnd", "containsDelimitedControl", "registrationObligations",
       "mainAlias", "packageScalarDeclarations"]
    completeRecord value = all (\key -> case member value key of Just (Bool _) -> True; _ -> False)
      ["containsDelimitedControl", "registrationObligations", "mainAlias", "packageScalarDeclarations"] &&
      all (\key -> case member value key of Just (Number _) -> True; _ -> False)
      ["start", "end", "bindingsStart", "bindingsEnd", "metadataStart", "metadataEnd"]
    mergePublished original ready = foldr (\key result -> maybe result (\value -> set key value result) (member ready key))
      (delete "bundle" original) ["json", "symbols", "modules", "targetLayout"]

-- Runtime admission needs these existing fields, not the original pretty Core
-- or per-binding group inventory. Source tables have a separate optional span
-- so source-note-disabled contexts do not allocate embedded source contents.
metadataKeys, sourceKeys :: [Key]
metadataKeys = ["schema", "ghc", "unit", "module", "boundary", "providedModules", "constructors",
  "foreign", "foreignLink", "staticForeignImportStubs", "staticForeignImports", "staticForeignExports",
  "staticForeignExportRegistration", "packageScalarLink", "packageNativeLink", "packageNativeArchive",
  "foreignExceptionBridge", "foreignExceptionBridgeUnit"]
sourceKeys = ["sourceFiles", "sourceSpans"]

project :: [Key] -> Value -> Value
project keys (Object fields) = Object (KM.filterWithKey (\key _ -> key `elem` keys) fields)
project _ _ = Object KM.empty

hasDeclarations :: Value -> Bool
hasDeclarations value = case member value "staticForeignImports" >>= (`member` "imports") of
  Just (Array imports) -> not (null imports)
  _ -> False

containsControl :: Value -> Bool
containsControl (Array values) = case toList values of
  String "prim" : String name : _ | name `elem` ["prompt#", "control0#"] -> True
  items -> any containsControl items
containsControl (Object fields) = any containsControl (KM.elems fields)
containsControl _ = False

registration :: Value -> Either String Bool
registration value = case member value "foreign" of
  Just foreignValue -> do
    files <- nonempty (member foreignValue "files")
    stubs <- case member foreignValue "stubs" of
      Just Null -> pure False
      Just stubs -> (||) <$> nonempty (member stubs "initializers") <*> nonempty (member stubs "finalizers")
      Nothing -> Left "Missing foreign stub metadata during unit publication"
    pure (files || stubs)
  Nothing -> pure False
  where nonempty (Just (Array values)) = Right (not (null values))
        nonempty _ = Left "Invalid foreign registration metadata during unit publication"

targetLayout :: [(FilePath, ZipMember)] -> Value -> IO (Maybe Value)
targetLayout entries inner = case member inner "targetLayout" of
  Nothing -> pure Nothing
  Just layout -> do
    inputs <- field inner "buildInputs"
    path <- field inputs "path"
    expected <- field inputs "sha256"
    bytes <- maybe (fail "Missing target-layout build receipt") readZipMember (lookup path entries)
    unless (digest bytes == expected) $ fail "Target-layout build receipt changed"
    receipt <- either fail pure (eitherDecodeStrict' bytes)
    unless (member receipt "targetLayout" == Just layout) $ fail "Target-layout receipts disagree"
    compiler <- field receipt "compiler" :: IO Value
    pure (Just (object ["format" .= ("thc-target-layout" :: String), "schema" .= (1 :: Int),
      "compiler" .= compiler, "layout" .= layout]))

checkFile :: Bool -> FilePath -> Integer -> Value -> IO ()
checkFile verify expectedPath size ref = do
  path <- field ref "path"
  unless (path == expectedPath) $ fail "Unexpected unit publication path"
  present <- doesFileExist path
  unless present $ fail "Missing unit publication file"
  actualSize <- getFileSize path
  unless (actualSize == size) $ fail "Unit publication size changed"
  if verify then do
    expected <- field ref "sha256"
    actual <- withBinaryFile path ReadMode $ \input -> hashHandle input SHA.init
    unless (hex actual == expected) $ fail "Unit publication hash changed"
  else pure ()
  where
    hashHandle input state = do
      bytes <- BS.hGetSome input (1024 * 1024)
      if BS.null bytes then pure (SHA.finalize state) else do
        next <- evaluate (SHA.update state bytes)
        hashHandle input next

atomicOutput :: FilePath -> (Handle -> IO a) -> IO a
atomicOutput destination action = bracketOnError
  (openBinaryTempFile (takeDirectory destination) "unit-output.tmp")
  (\(path, handle) -> hClose handle `catch` ignore >> removeFile path `catch` ignore) $ \(path, handle) -> do
    result <- action handle
    hClose handle
    renameFile path destination
    pure result
  where ignore :: IOException -> IO ()
        ignore _ = pure ()

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either fail pure . eitherDecodeStrict'

decodeMember :: [(FilePath, ZipMember)] -> FilePath -> IO Value
decodeMember entries path = do
  bytes <- maybe (fail ("Missing bundle member " ++ path)) readZipMember (lookup path entries)
  either fail pure (eitherDecodeStrict' bytes)

member :: Value -> Key -> Maybe Value
member (Object fields) key = KM.lookup key fields
member _ _ = Nothing

field :: FromJSON a => Value -> Key -> IO a
field value key = case member value key of
  Just item -> case fromJSON item of Success result -> pure result; Error problem -> fail problem
  Nothing -> fail ("Missing unit publication field " ++ show key)

set :: Key -> Value -> Value -> Value
set key value (Object fields) = Object (KM.insert key value fields)
set _ _ value = value

delete :: Key -> Value -> Value
delete key (Object fields) = Object (KM.delete key fields)
delete _ value = value

digest :: BS.ByteString -> String
digest = hex . SHA.hash

hex :: BS.ByteString -> String
hex = concatMap (\byte -> let digits = showHex byte "" in if length digits == 1 then '0':digits else digits) . BS.unpack
