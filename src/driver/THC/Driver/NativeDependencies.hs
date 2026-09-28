-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.NativeDependencies
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Cabal registrations and native ar
--
-- Select C-only dependency products from the resolved Cabal registration and
-- exact native archive membership, never from an unresolved symbol spelling.
module THC.Driver.NativeDependencies
  ( COnlyProduct, cOnlyProductProof, cOnlyProductPieces, readCOnlyProduct
  , selectCOnlyPieces
  ) where

import Control.Exception (evaluate)
import Control.Monad (filterM, forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', fromJSON, Result(..), object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import Data.Char (isAscii, isAlphaNum)
import Data.List (nub, sort)
import qualified Data.Text.Encoding as T
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo)
import qualified Distribution.Types.InstalledPackageInfo as Package
import Numeric (showHex)
import System.Directory (doesFileExist, doesDirectoryExist, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeFileName)
import System.Process (CreateProcess(..), StdStream(..), proc, readProcessWithExitCode,
  waitForProcess, withCreateProcess)
import THC.Driver.Installed (emptyRegistration)

-- The constructor stays private: callers cannot supply arbitrary extra bitcode
-- through the dependency seam without an actual resolved C-only registration.
data COnlyProduct = COnlyProduct
  { cOnlyProductProof :: Value, cOnlyProductPieces :: [Value] }

-- | Match every registered archive member exactly once. The basename is only
-- an additional check; native object content is the membership authority.
-- Ambiguous captures fail closed instead of picking one compiler recipe.
selectCOnlyPieces :: [(FilePath, String)] -> [Value] -> Either String [Value]
selectCOnlyPieces members pieces = do
  require (not (null members) && length members == length (nub (map fst members)))
    "C-only archive has empty or duplicate member inventory"
  forM members $ \(name,expected) -> do
    require (archiveMember name)
      "C-only archive member is not a basename"
    matches <- filterM (matchesMember name expected) pieces
    case nub matches of
      [piece] -> Right piece
      [] -> Left ("C-only archive member has no captured compiler product: " ++ name)
      _ -> Left ("C-only archive member has ambiguous compiler products: " ++ name)
  where
    matchesMember name expected piece = do
      path <- field piece "object"
      observed <- field piece "objectSha256"
      pure (takeFileName path == name && observed == expected)

-- | Read one exact resolved Cabal plan row and its matching registration.
-- Haskell-bearing packages and reexport facades are not C-only providers.
readCOnlyProduct :: Value -> FilePath -> FilePath -> IO (Maybe COnlyProduct)
readCOnlyProduct unit registration pieces = do
  identifier <- get unit "id"
  dependencies <- get unit "depends"
  bytes <- BS.readFile registration
  if not (emptyRegistration identifier dependencies bytes) then pure Nothing else do
    (_, info) <- either (fail . show) pure (parseInstalledPackageInfo bytes)
    let libraries = Package.hsLibraries info
    if null libraries then pure Nothing else do
      kind <- get unit "type" :: IO String
      style <- get unit "style" :: IO String
      source <- get unit "pkg-src-sha256" :: IO String
      check (kind == "configured" && style `elem` ["global","inplace"] && validHash source)
        "C-only dependency lacks a resolved source identity"
      archiveProducts <- forM libraries $ \library -> do
        check (takeFileName library == library && library `notElem` [".",".."])
          "C-only registration library is not a basename"
        let directories = nub (Package.libraryDirsStatic info ++ Package.libraryDirs info)
        found <- filterM doesFileExist [directory </> "lib" ++ library ++ ".a" | directory <- directories]
        archive <- case nub found of
          [path] -> pure path
          _ -> fail ("C-only registration lacks a unique native archive: " ++ identifier)
        before <- digest <$> BS.readFile archive
        ar <- maybe "ar" id <$> lookupEnv "THC_AR"
        (status, listing, diagnostic) <- readProcessWithExitCode ar ["t",archive] ""
        check (status == ExitSuccess) ("Cannot read C-only archive: " ++ diagnostic)
        let names = lines listing
        check (not (null names) && length names == length (nub names) &&
          all archiveMember names) "Unsupported C-only archive inventory"
        members <- forM names $ \name -> do
          contents <- withCreateProcess (proc ar ["p",archive,name]) {std_out=CreatePipe} $ \_ output _ handle -> do
            stream <- maybe (fail "Missing archive output pipe") pure output
            payload <- BS.hGetContents stream
            _ <- evaluate (BS.length payload)
            result <- waitForProcess handle
            check (result == ExitSuccess) "Cannot read C-only archive member"
            pure payload
          pure (name,digest contents)
        after <- digest <$> BS.readFile archive
        check (before == after) "C-only archive changed during selection"
        pure (archive,before,members)
      paths <- filter ((== "piece.json") . takeFileName) <$> files pieces
      candidates <- mapM readJson paths
      let members = concat [entries | (_,_,entries) <- archiveProducts]
      selected <- either fail pure (selectCOnlyPieces members candidates)
      -- One dependency cannot silently collect same-named sibling components.
      roots <- mapM (`get` "root") selected :: IO [String]
      check (length (nub roots) == 1) "C-only archive combines different captured source roots"
      products <- forM selected $ \piece -> do
        path <- get piece "bitcode"
        hash <- digest <$> BS.readFile path
        pure (object ["receipt" .= piece,"bitcodeSha256" .= hash])
      let identity = object [Key.fromString key .= maybe Null id (member unit key) |
            key <- ["id","depends","type","style","pkg-name","pkg-version","flags",
                    "component-name","pkg-src-sha256","pkg-cabal-sha256"]]
          proof = object ["profile" .= ("resolved-c-only-archive-products-v1" :: String),
            "unit" .= identifier,"sourceIdentity" .= identity,
            "registration" .= T.decodeUtf8 bytes,"registrationSha256" .= digest bytes,
            "archives" .= [object ["path" .= path,"sha256" .= hash,
                "members" .= [object ["name" .= name,"sha256" .= value] | (name,value) <- entries]] |
              (path,hash,entries) <- archiveProducts],"translationUnits" .= products]
      pure (Just (COnlyProduct proof selected))

member :: Value -> String -> Maybe Value
member (Object fields) key = KM.lookup (Key.fromString key) fields
member _ _ = Nothing
field :: FromJSON a => Value -> String -> Either String a
field value key = case member value key of
  Just item -> case fromJSON item of Success result -> Right result; Error message -> Left message
  Nothing -> Left ("Missing native dependency field: " ++ key)
get :: FromJSON a => Value -> String -> IO a
get value key = either fail pure (field value key)
require :: Bool -> String -> Either String ()
require condition message = if condition then Right () else Left message
check :: Bool -> String -> IO ()
check condition = unless condition . fail
readJson :: FilePath -> IO Value
readJson path = either fail pure . eitherDecodeStrict' =<< BS.readFile path
files :: FilePath -> IO [FilePath]
files directory = do
  exists <- doesDirectoryExist directory
  if not exists then pure [] else do
    names <- sort <$> listDirectory directory
    concat <$> forM names (\name -> do
      let path = directory </> name
      isDirectory <- doesDirectoryExist path
      if isDirectory then files path else pure [path])
validHash :: String -> Bool
validHash value = length value == 64 && all (`elem` ("0123456789abcdef" :: String)) value
archiveMember :: String -> Bool
archiveMember [] = False
archiveMember name@(first:_) = first `notElem` ['-','@'] &&
  name `notElem` [".",".."] && all (\c -> isAscii c && (isAlphaNum c || c `elem` ("._-" :: String))) name
digest :: BS.ByteString -> String
digest = concatMap (\byte -> let value = showHex byte "" in if length value == 1 then '0':value else value) . BS.unpack . SHA.hash
