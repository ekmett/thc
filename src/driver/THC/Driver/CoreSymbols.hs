-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Publish immutable per-module CBD from final linked binary Core, or preserve
-- a checked demand-interface source. Acquisition ZIPs remain provenance caches.
module THC.Driver.CoreSymbols (publishCoreUnit) where

import Control.Exception (IOException, bracketOnError, catch, evaluate)
import Control.Monad (forM, forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), FromJSON, Result(..), eitherDecodeStrict', encode, fromJSON, object, toJSON, (.=))
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KM
import Data.Bits ((.&.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing, doesFileExist, getFileSize, makeAbsolute, removeFile, renameFile)
import System.FilePath ((</>), isAbsolute, takeDirectory)
import System.IO (Handle, IOMode(ReadMode), hClose, openBinaryTempFile, withBinaryFile)
import THC.Compact.Module (readModuleMetadata)
import THC.Compact.Wire (headerSummaries)
import THC.Driver.Lock (withLock)
import THC.Driver.Zip (ZipMember, decodeZipMembers, readZipMember)

-- | Warm publication checks identities and sizes; explicit verification also
-- checks content hashes. There is no JSON/index migration or fallback route.
publishCoreUnit :: FilePath -> Bool -> Value -> IO Value
publishCoreUnit cache verify unit
  | any (\key -> member unit key /= Nothing) ["json","symbols"] = fail "JSON Core unit artifacts are not supported"
  | member unit "interfaceSource" /= Nothing = do
      unless (member unit "bundle" == Nothing) (fail "Interface unit also contains a CBD bundle")
      pure unit
  | otherwise = case (member unit "bundle", member unit "modules") of
  (Just bundle, Just (Array modules)) | not (null modules) -> do
    source <- field bundle "path"
    unless (isAbsolute source) $ fail "Unit source bundle path must be absolute"
    sourceHash <- field bundle "sha256"
    unless (length sourceHash == 64 && all (`elem` ("0123456789abcdef" :: String)) sourceHash) $
      fail "Invalid source bundle digest for unit publication"
    directory <- makeAbsolute (cache </> "unit-core/v3" </> sourceHash)
    let receiptPath = directory </> "publication.json"
    createDirectoryIfMissing True directory
    withLock (directory </> "publication.lock") $ do
      cached <- (do
        receipt <- readJson receiptPath
        storedSource <- field receipt "source"
        originals <- field receipt "modules" :: IO [Value]
        ready <- field receipt "unit" :: IO Value
        refs <- field ready "modules" :: IO [Value]
        sizes <- field receipt "sizes" :: IO [Integer]
        unless (storedSource == bundle && originals == toList modules &&
                member ready "id" == member unit "id" && length refs == length sizes &&
                length refs == length originals && all completeRecord refs) $
          fail "Stale CBD unit publication"
        forM_ (zip3 [0::Int ..] refs sizes) $ \(index,ref,size) -> do
          compact <- field ref "compact"
          checkFile verify (directory </> show index ++ ".cbd") size compact
        pure (Just ready)) `catch` absent
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
          products <- forM (zip [0::Int ..] (toList modules)) $ \(index,ref) -> do
            path <- field ref "path"
            expected <- field ref "sha256"
            bytes <- maybe (fail "Missing CBD module during unit publication") readZipMember (lookup path entries)
            unless (digest bytes == expected) $ fail "Core module changed during unit publication"
            (header,value) <- either fail pure (readModuleMetadata bytes)
            unless (member value "unit" == member unit "id" && member value "module" == member ref "name" &&
                    member value "boundary" == member ref "boundary") $
              fail "CBD module identity changed during publication"
            let destination = directory </> show index ++ ".cbd"
                flags = headerSummaries header
                summaries = [("containsDelimitedControl",1),("registrationObligations",2),
                  ("mainAlias",4),("packageScalarDeclarations",8)]
                record = foldr (\(key,bit) -> set key (Bool (flags .&. bit /= 0)))
                  (set "compact" (object ["path" .= destination,"sha256" .= expected,
                    "format" .= ("thc-cbd-v1" :: String)]) (delete "index" ref)) summaries
            atomicOutput destination (\output -> BS.hPut output bytes)
            pure (record,BS.length bytes)
          let ready = set "modules" (toJSON (map fst products)) (delete "bundle" unit)
              withLayout = maybe ready (\value -> set "targetLayout" value ready) layout
              receipt = object ["source" .= bundle,"modules" .= toList modules,
                "unit" .= withLayout,"sizes" .= map snd products]
          atomicOutput receiptPath (\output -> BS.hPut output (BL.toStrict (encode receipt)))
          pure withLayout
  (_,Just (Array modules)) | null modules -> pure (delete "bundle" unit)
  (Nothing,Just (Array modules)) | all completeRecord modules -> pure unit
  _ -> fail "Nonempty Core unit requires CBD module artifacts"
  where
    absent :: IOException -> IO (Maybe Value)
    absent _ = pure Nothing
    completeRecord value = all (\key -> case member value key of Just (Bool _) -> True; _ -> False)
      ["containsDelimitedControl","registrationObligations","mainAlias","packageScalarDeclarations"] &&
      (member value "compact" >>= (`member` "format")) == Just (String "thc-cbd-v1")
    mergePublished original ready = foldr (\key result -> maybe result (\value -> set key value result) (member ready key))
      (delete "bundle" original) ["modules","targetLayout"]

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
    pure (Just (object ["format" .= ("thc-target-layout" :: String),"schema" .= (1 :: Int),
      "compiler" .= compiler,"layout" .= layout]))

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
  (\(path,handle) -> hClose handle `catch` ignore >> removeFile path `catch` ignore) $ \(path,handle) -> do
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
