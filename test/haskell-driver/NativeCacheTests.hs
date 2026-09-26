-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module NativeCacheTests (tests) where

import Control.Exception (bracket)
import Control.Monad (forM_, void)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), encode, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Numeric (showHex)
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
  getTemporaryDirectory, removeDirectoryRecursive, removeFile)
import System.Environment (lookupEnv, setEnv, unsetEnv)
import System.FilePath ((</>), takeDirectory)
import System.IO (hClose, openTempFile)
import System.IO.Error (tryIOError)
import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import THC.Driver.NativeCache (nativeToolIdentity, nativePieceIdentity)

tests :: Test
tests = TestLabel "native cache identity" $ TestList
  [ TestCase $ withScratch $ \root -> withTools (root </> "missing") $ do
      observed <- nativeToolIdentity
      forM_ toolVariables $ \name -> assertEqual "missing tools do not require LLVM for pure Haskell"
        Null (member "executable" (member name (member "tools" observed)))
  , TestCase $ withScratch $ \root -> do
      let executable = root </> "tool"
          replacement = root </> "other-tool"
      writeFile executable "first executable bytes"
      withTools executable $ do
        first <- nativeToolIdentity
        assertEqual "unchanged tools and environment reuse the identity" first =<< nativeToolIdentity
        writeFile executable "updated executable bytes"
        updated <- nativeToolIdentity
        assertBool "same executable path with new bytes invalidates the cache" (first /= updated)
        writeFile replacement "updated executable bytes"
        withEnvironment [("THC_CLANG", replacement)] $ do
          moved <- nativeToolIdentity
          assertBool "selected tool path is part of identity even with equal bytes" (updated /= moved)
        assertEqual "temporary selections are restored" updated =<< nativeToolIdentity
  , TestCase $ withScratch $ \root -> withTools (root </> "missing") $ do
      forM_ ["CPATH", "C_INCLUDE_PATH", "SDKROOT", "SOURCE_DATE_EPOCH"] $ \variable -> do
        first <- withEnvironment [(variable, "first")] nativeToolIdentity
        second <- withEnvironment [(variable, "second")] nativeToolIdentity
        assertBool (variable ++ " participates in the native build identity") (first /= second)
  , TestCase $ withScratch $ \root -> do
      let pieces = root </> "pieces"
      first <- makePiece pieces root "a"
      second <- makePiece pieces root "b"
      baseline <- nativePieceIdentity pieces [first,second]
      actual <- nativePieceIdentity pieces [second,first,first,root </> "ignored.dyn_o"]
      assertEqual "exact object membership is ordered, deduplicated and excludes dynamic twins" baseline actual
      createDirectoryIfMissing True (pieces </> "unrelated")
      writeFile (pieces </> "unrelated/piece.json") "not-json"
      assertEqual "unowned persistent receipts do not participate" baseline =<< nativePieceIdentity pieces [first,second]
      one <- nativePieceIdentity pieces [first]
      assertBool "removing an owned translation unit changes identity" (baseline /= one)
  , TestCase $ withScratch $ \root -> do
      let pieces = root </> "pieces"
      native <- makePiece pieces root "a"
      before <- nativePieceIdentity pieces [native]
      writeFile (root </> "a.bc") "new LLVM, unchanged Cabal object"
      changed <- nativePieceIdentity pieces [native]
      assertBool "actual bitcode bytes cannot hide behind unchanged object identity" (before /= changed)
      record <- pieceRecord root "a" ["-DCHANGED=1"]
      BL.writeFile (receiptPath pieces native) (encode record)
      recipeChanged <- nativePieceIdentity pieces [native]
      assertBool "captured options remain evidence even with identical bitcode" (changed /= recipeChanged)
  , TestCase $ withScratch $ \root -> do
      let pieces = root </> "pieces"
      native <- makePiece pieces root "a"
      before <- nativePieceIdentity pieces [native]
      writeFile (root </> "a.h") "updated captured header"
      expectFailure "header change after capture rejects stale receipt" (nativePieceIdentity pieces [native])
      refreshed <- pieceRecord root "a" []
      BL.writeFile (receiptPath pieces native) (encode refreshed)
      after <- nativePieceIdentity pieces [native]
      assertBool "fresh captured header hash invalidates old bundle" (before /= after)
  , TestCase $ withScratch $ \root -> do
      let pieces = root </> "pieces"
      native <- makePiece pieces root "a"
      writeFile native "object changed after capture"
      expectFailure "stale native object receipt is rejected" (nativePieceIdentity pieces [native])
  , TestCase $ withScratch $ \root -> do
      let pieces = root </> "pieces"
      native <- makePiece pieces root "a"
      record <- pieceRecord root "a" []
      case record of
        Object fields -> BL.writeFile (receiptPath pieces native) (encode (Object
          (KM.insert "object" (String (T.pack (root </> "another.o"))) fields)))
        _ -> assertFailure "test receipt must be an object"
      expectFailure "receipt must belong to requested object" (nativePieceIdentity pieces [native])
  , TestCase $ withScratch $ \root -> do
      let pieces = root </> "pieces"
      native <- makePiece pieces root "a"
      removeFile (root </> "a.bc")
      expectFailure "missing captured LLVM is not a cache hit" (nativePieceIdentity pieces [native])
      removeFile (receiptPath pieces native)
      expectFailure "missing receipt is not a cache hit" (nativePieceIdentity pieces [native])
  ]

toolVariables :: [String]
toolVariables = ["THC_CLANG", "THC_LLVM_LINK", "THC_LLVM_OPT", "THC_LLVM_NM"]

withTools :: FilePath -> IO a -> IO a
withTools path = withEnvironment [(variable,path) | variable <- toolVariables]

withEnvironment :: [(String,String)] -> IO a -> IO a
withEnvironment values action = bracket
  (mapM (\(name,_) -> (,) name <$> lookupEnv name) values)
  (mapM_ (\(name,old) -> maybe (unsetEnv name) (setEnv name) old))
  (\_ -> mapM_ (uncurry setEnv) values >> action)

makePiece :: FilePath -> FilePath -> String -> IO FilePath
makePiece pieces root name = do
  let native = root </> name ++ ".o"
      receipt = receiptPath pieces native
  writeFile native "original Cabal native object"
  writeFile (root </> name ++ ".bc") "captured LLVM bytes"
  writeFile (root </> name ++ ".h") "captured header"
  record <- pieceRecord root name []
  createDirectoryIfMissing True (takeDirectory receipt)
  BL.writeFile receipt (encode record)
  pure native

pieceRecord :: FilePath -> String -> [String] -> IO Value
pieceRecord root name arguments = do
  let native = root </> name ++ ".o"
      header = root </> name ++ ".h"
  objectHash <- digest <$> BS.readFile native
  headerHash <- digest <$> BS.readFile header
  pure (object ["root" .= root,"object" .= native,"objectSha256" .= objectHash,
    "bitcode" .= (root </> name ++ ".bc"),"target" .= ("x86_64-unknown-linux-gnu" :: String),
    "inputs" .= object ["compiler" .= ("/selected/ghc" :: String),"clang" .= ("/selected/clang" :: String),
      "arguments" .= arguments,"nativeTarget" .= ("x86_64-pc-linux-gnu" :: String),
      "target" .= ("x86_64-unknown-linux-gnu" :: String),
      "files" .= [object ["path" .= header,"sha256" .= headerHash]]]])

receiptPath :: FilePath -> FilePath -> FilePath
receiptPath pieces native = pieces </> digest (T.encodeUtf8 (T.pack native)) </> "piece.json"

digest :: BS.ByteString -> String
digest = concatMap byte . BS.unpack . SHA.hash
  where byte value = let digits = showHex value "" in if length digits == 1 then '0':digits else digits

member :: String -> Value -> Value
member key (Object fields) = maybe Null id (KM.lookup (Key.fromString key) fields)
member _ _ = Null

expectFailure :: String -> IO a -> IO ()
expectFailure label action = do
  result <- tryIOError (void action)
  assertBool label (case result of Left _ -> True; Right _ -> False)

withScratch :: (FilePath -> IO a) -> IO a
withScratch = bracket create removeDirectoryRecursive
  where
    create = do
      temporary <- getTemporaryDirectory
      (path, handle) <- openTempFile temporary "thc-native-cache"
      hClose handle
      removeFile path
      createDirectory path
      canonicalizePath path
