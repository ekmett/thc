-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module THC.Driver.NativeLibrarySources (zlibChecksumSources) where

import Control.Monad (forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import qualified Data.ByteString as BS
import Numeric (showHex)
import System.FilePath ((</>))

-- Compile the original implementation with the package's actual configured
-- zlib header. A mismatched installed version is a specific unsupported
-- provider, not permission to substitute an unrelated implementation.
-- The returned translation units are ordinary LLVM over managed pointers;
-- no heap buffer is copied or projected into native memory.
zlibChecksumSources :: FilePath -> IO [(String, String)]
zlibChecksumSources repository = do
  let directory = repository </> "compiler/pinned-zlib/1.2.11"
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
