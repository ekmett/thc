-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Cache inputs for package C acquisition, independent of native object
-- equivalence. A selected tool or freshly captured translation unit must not
-- disappear behind an older Core bundle with the same Cabal object identity.
module THC.Driver.NativeCache (nativeToolIdentity, nativePieceIdentity) where

import Control.Exception (evaluate)
import Control.Monad (forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', fromJSON, object, Result(..), (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (nub, sort)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Numeric (showHex)
import System.Directory (canonicalizePath, doesFileExist, findExecutable)
import System.Environment (lookupEnv)
import System.FilePath ((</>), isAbsolute, takeExtension)
import System.IO (IOMode(ReadMode), withBinaryFile)

-- Missing LLVM tools remain explicit inputs, not an error for pure-Haskell
-- projects. Acquisition itself diagnoses a missing tool when C is needed.
-- Hash the selected executable rather than trusting a mutable version label.
nativeToolIdentity :: IO Value
nativeToolIdentity = do
  tools <- forM [("THC_CLANG", "clang"), ("THC_LLVM_LINK", "llvm-link"),
                ("THC_LLVM_OPT", "opt"), ("THC_LLVM_NM", "llvm-nm")] $ \(variable, fallback) -> do
    selected <- maybe fallback id <$> lookupEnv variable
    found <- if isAbsolute selected
      then do exists <- doesFileExist selected; pure (if exists then Just selected else Nothing)
      else findExecutable selected
    observed <- case found of
      Nothing -> pure Null
      Just path -> do
        canonical <- canonicalizePath path
        digest <- fileHash canonical
        pure (object ["path" .= canonical, "sha256" .= digest])
    pure (Key.fromString variable .= object ["selected" .= selected, "executable" .= observed])
  environment <- forM ["PATH", "CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH",
    "OBJC_INCLUDE_PATH", "SDKROOT", "MACOSX_DEPLOYMENT_TARGET", "SOURCE_DATE_EPOCH",
    "COMPILER_PATH", "GCC_EXEC_PREFIX", "LIBRARY_PATH"] $ \name -> do
      value <- lookupEnv name
      pure (Key.fromString name .= value)
  pure (object ["schema" .= (1 :: Int), "tools" .= object tools, "environment" .= object environment])

-- The caller supplies the exact currently owned object list; never discover
-- component membership by scanning the persistent piece cache. Dynamic twins
-- share the vanilla C recipe and do not contribute a second translation unit.
nativePieceIdentity :: FilePath -> [FilePath] -> IO Value
nativePieceIdentity pieces objects = do
  owned <- sort . nub <$> mapM canonicalizePath (filter ((== ".o") . takeExtension) objects)
  inputs <- forM owned $ \path -> do
    let receipt = pieces </> hash (BL.fromStrict (T.encodeUtf8 (T.pack path))) </> "piece.json"
    record <- either fail pure . eitherDecodeStrict' =<< BS.readFile receipt
    original <- field record "object"
    unless (original == path) (fail "package native piece cache owner differs")
    expected <- field record "objectSha256"
    observed <- fileHash path
    unless (observed == expected) (fail "package native piece has a stale native object receipt")
    bitcode <- field record "bitcode"
    bitcodeHash <- fileHash bitcode
    recipe <- field record "inputs"
    files <- field recipe "files" :: IO [Value]
    mapM_ verifyInput files
    pure (object ["receipt" .= record, "bitcodeSha256" .= bitcodeHash])
  pure (object ["schema" .= (1 :: Int), "translationUnits" .= inputs])
  where
    verifyInput value = do
      path <- field value "path"
      expected <- field value "sha256"
      observed <- fileHash path
      unless (observed == expected) (fail ("package native input changed after capture: " ++ path))

field :: FromJSON a => Value -> String -> IO a
field (Object fields) key = case KM.lookup (Key.fromString key) fields of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> fail message
  Nothing -> fail ("package native cache input missing: " ++ key)
field _ _ = fail "package native cache input must be an object"

fileHash :: FilePath -> IO String
fileHash path = withBinaryFile path ReadMode $ \handle -> do
  bytes <- BL.hGetContents handle
  digest <- evaluate (SHA.hashlazy bytes)
  pure (hex digest)

hash :: BL.ByteString -> String
hash = hex . SHA.hashlazy

hex :: BS.ByteString -> String
hex = concatMap byte . BS.unpack
  where byte value = let digits = showHex value "" in if length digits == 1 then '0':digits else digits
