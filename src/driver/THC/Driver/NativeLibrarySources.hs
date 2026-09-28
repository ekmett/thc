-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.NativeLibrarySources
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell; bytestring, cryptohash-sha256 and target-specific native profiles
--
-- Capture declared native link inputs, not a list of tested native symbols.
module THC.Driver.NativeLibrarySources
  ( zlibChecksumSources, nativeLinkOptions, nativePackageOptions, nativePackageSelectors
  , packageNativeLibraries ) where

import Control.Monad (forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import qualified Data.ByteString as BS
import Data.List (isPrefixOf)
import Numeric (showHex)
import System.FilePath ((</>))

-- GHC's native linker options, in their original order. Haskell objects and
-- Haskell/RTS libraries are deliberately not linked into the Sulong component.
nativeLinkOptions :: [String] -> [String]
nativeLinkOptions [] = []
nativeLinkOptions (flag:value:rest)
  | flag `elem` ["-l","-L"] = (flag ++ value) : nativeLinkOptions rest
  | flag == "-optl" = value : nativeLinkOptions rest
  | flag == "-framework" = flag : value : nativeLinkOptions rest
  | flag == "-framework-path" = ("-F" ++ value) : nativeLinkOptions rest
  | flag `elem` ["-linkdir","-optlo","-optlc","-optlm","-optlas"] = nativeLinkOptions rest
nativeLinkOptions (flag:rest)
  | flag == "-F" = nativeLinkOptions rest
  | "-framework-path=" `isPrefixOf` flag = ("-F" ++ drop 16 flag) : nativeLinkOptions rest
  | flag == "-link-rts" || "-linkdir=" `isPrefixOf` flag ||
    any (`isPrefixOf` flag) ["-optlo","-optlc","-optlm","-optlas"] = nativeLinkOptions rest
  | "-optl=" `isPrefixOf` flag = drop 6 flag : nativeLinkOptions rest
  | "-optl" `isPrefixOf` flag = drop 5 flag : nativeLinkOptions rest
  | any (`isPrefixOf` flag) ["-l","-L"] = flag : nativeLinkOptions rest
  | otherwise = nativeLinkOptions rest

-- Use the same package database stack and explicit dependency selection as
-- the successful Cabal/GHC invocation; plugin packages are not dependencies.
nativePackageOptions :: [String] -> [String]
nativePackageOptions = go ["--global","--user"]
  where
    go stack [] = stack
    go stack ("-package-db":path:rest) = go (stack ++ ["--package-db=" ++ path]) rest
    go stack (flag:rest)
      | flag == "-no-user-package-db" = go (filter (/= "--user") stack) rest
      | flag == "-clear-package-db" = go [] rest
      | flag == "-global-package-db" = go (stack ++ ["--global"]) rest
      | flag == "-user-package-db" = go (stack ++ ["--user"]) rest
      | "-package-db=" `isPrefixOf` flag = go (stack ++ ["-" ++ flag]) rest
      | otherwise = go stack rest

nativePackageSelectors :: [String] -> [(Bool,String)]
nativePackageSelectors [] = []
nativePackageSelectors (flag:value:rest)
  | flag `elem` ["-package-id","-package"] = (flag == "-package-id",value) : nativePackageSelectors rest
nativePackageSelectors (flag:rest)
  | "-package-id=" `isPrefixOf` flag = (True,drop 12 flag) : nativePackageSelectors rest
  | "-package=" `isPrefixOf` flag = (False,drop 9 flag) : nativePackageSelectors rest
nativePackageSelectors (_:rest) = nativePackageSelectors rest

-- No library is inferred from an unresolved symbol. Keep registration order,
-- including repeated libraries required by an ordinary native link.
packageNativeLibraries :: [FilePath] -> [String] -> [String] -> [String]
packageNativeLibraries directories libraries options =
  concatMap (\path -> ["-L" ++ path,"-Wl,-rpath," ++ path]) directories ++
  map ("-l" ++) libraries ++ options

-- Compile the original implementation with the package's actual configured
-- zlib header. A mismatched installed version is a specific unsupported
-- provider, not permission to substitute an unrelated implementation.
-- The returned translation units are ordinary LLVM over managed pointers;
-- no heap buffer is copied or projected into native memory.
zlibChecksumSources :: FilePath -> IO [(String, String)]
zlibChecksumSources repository = do
  let directory = repository </> "nih/pinned/zlib-1.2.11"
      hashes =
        [ ("adler32.c", "d7f1b6e44fee20ab41cef1d650776a039a2348935eb96bcbd294a4096139be3a")
        , ("crc32.c", "a04af273e83ecc351bf3794974ab2098d8d960df4044b7b44734c41443ee26d0")
        , ("crc32.h", "407af59d0abfea84a6507c603eb29809411797f98249614fe76a661def783ce1")
        , ("zutil.h", "9a63f6690fac1620aa3cecee5752af618806da438a256b4a047fbcd289cac159")
        , ("zlib.h", "4ddc82b4af931ab55f44d977bde81bfbc4151b5dcdccc03142831a301b5ec3c8")
        , ("zconf.h", "9c0087f31cd45fe4bfa0ca79b51df2c69d67c44f2fbb2223d7cf9ab8d971c360")
        ]
  forM_ hashes $ \(name, expected) -> do
    observed <- digest <$> BS.readFile (directory </> name)
    unless (observed == expected) (fail ("pinned zlib checksum source differs: " ++ name))
  pure [(symbol, unlines
    [ "#include <zlib.h>"
    , "#if ZLIB_VERNUM != 0x12b0"
    , "#error THC checksum source provider requires the configured zlib 1.2.11 header"
    , "#endif"
    , "#include \"" ++ includePath (directory </> symbol ++ ".c") ++ "\""
    ]) | symbol <- ["adler32", "crc32"]]
  where
    includePath = concatMap (\c -> case c of '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; _ -> [c])
    digest = concatMap (\byte -> let value = showHex byte "" in if length value == 1 then '0':value else value) . BS.unpack . SHA.hash
