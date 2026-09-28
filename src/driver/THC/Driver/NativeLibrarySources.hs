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
-- Validate exact native-library source and LLVM inputs for supported acquisition profiles.
module THC.Driver.NativeLibrarySources
  ( zlibChecksumSources, nativeMathSymbols, validateNativeMathIR, validateNativeEntropyIR,
    validateNativeWidthIR, nativeCxxInitSymbols, nativeLifecycleSymbols, validateNativeLifecycleIR
  , nativeLibcSymbols, validateNativeLibcIR, nativeZlibSymbols, validateNativeZlibIR ) where

import Control.Monad (forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import qualified Data.ByteString as BS
import Data.List (isPrefixOf)
import Numeric (showHex)
import System.FilePath ((</>))

nativeMathSymbols :: [String]
nativeMathSymbols = ["erf", "erfc", "erff", "erfcf"]

-- Exact Linux LP64 declarations observed in the original libyaml closure.
-- LLVM/Sulong retain the C implementation and libc provider; none of these
-- entries is replaced by a JVM model or inferred from its name alone.
nativeLibcSignatures :: [(String,String,[String])]
nativeLibcSignatures =
  [ ("fclose","i32",["ptr"]), ("fdopen","ptr",["i32","ptr"])
  , ("realloc","ptr",["ptr","i64"]), ("malloc","ptr",["i64"])
  , ("free","void",["ptr"]), ("strdup","ptr",["ptr"])
  , ("strcmp","i32",["ptr","ptr"]), ("strncmp","i32",["ptr","ptr","i64"])
  , ("strlen","i64",["ptr"]), ("ferror","i32",["ptr"])
  , ("fread","i64",["ptr","i64","i64","ptr"])
  , ("fwrite","i64",["ptr","i64","i64","ptr"])
  , ("__assert_fail","void",["ptr","ptr","i32","ptr"])
  ]

nativeLibcSymbols :: [String]
nativeLibcSymbols = [name | (name,_,_) <- nativeLibcSignatures]

-- Original zlib.h declarations on Linux LP64. These are native dependencies,
-- not replacements for the configured CAPI wrappers or a managed z_stream.
-- In particular, uLong is 64 bits, uInt is 32 bits and zlibVersion returns an
-- actual library-owned pointer. Init functions retain their version/size check.
nativeZlibSignatures :: [(String,String,[String])]
nativeZlibSignatures =
  [ ("adler32","i64",["i64","ptr","i32"])
  , ("crc32","i64",["i64","ptr","i32"])
  , ("zlibVersion","ptr",[])
  , ("deflate","i32",["ptr","i32"]), ("inflate","i32",["ptr","i32"])
  , ("deflateInit2_","i32",["ptr","i32","i32","i32","i32","i32","ptr","i32"])
  , ("inflateInit2_","i32",["ptr","i32","ptr","i32"])
  , ("deflateSetDictionary","i32",["ptr","ptr","i32"])
  , ("inflateSetDictionary","i32",["ptr","ptr","i32"])
  , ("inflateReset","i32",["ptr"])
  , ("deflateEnd","i32",["ptr"]), ("inflateEnd","i32",["ptr"])
  ]

nativeZlibSymbols :: [String]
nativeZlibSymbols = [name | (name,_,_) <- nativeZlibSignatures]

validateNativeZlibIR :: String -> [String] -> String -> Either String ()
validateNativeZlibIR target symbols source = do
  unless (target == "x86_64-unknown-linux-gnu")
    (Left "native zlib package provider currently requires Linux x86_64")
  forM_ symbols $ \symbol -> do
    (result,parameters) <- case [(value,arguments) | (name,value,arguments) <- nativeZlibSignatures, name == symbol] of
      [signature] -> Right signature
      _ -> Left "unsupported native zlib symbol"
    let declarations = [(before,drop (length symbol + 2) after) |
          line <- lines source, "declare " `isPrefixOf` line,
          let (before,after) = break (== '@') line, ("@" ++ symbol ++ "(") `isPrefixOf` after]
        clean = filter (/= "noundef") . words
        valid (before,after) = clean before == ["declare",result] &&
          map clean (split (takeWhile (/= ')') after)) == map (:[]) parameters
    unless (length declarations == 1 && all valid declarations)
      (Left ("native zlib declaration has unsupported ABI: " ++ symbol))
  where
    split "" = []
    split text = case break (== ',') text of
      (first,[]) -> [first]
      (first,_:rest) -> first : split rest

-- | Check actual linked IR before constructing its native libc dependency.
-- Only ordinary ABI-neutral parameter attributes are discarded. Calling
-- conventions, address spaces, varargs, by-value aggregates and width changes
-- remain rejected, as do missing or duplicate declarations.
validateNativeLibcIR :: String -> [String] -> String -> Either String ()
validateNativeLibcIR target symbols source = do
  unless (target == "x86_64-unknown-linux-gnu")
    (Left "native libc package provider currently requires Linux x86_64")
  forM_ symbols $ \symbol -> do
    (result,parameters) <- case [(value,arguments) | (name,value,arguments) <- nativeLibcSignatures, name == symbol] of
      [signature] -> Right signature
      _ -> Left "unsupported native libc symbol"
    let declarations = [(before,drop (length symbol + 2) after) |
          line <- lines source, "declare " `isPrefixOf` line,
          let (before,after) = break (== '@') line, ("@" ++ symbol ++ "(") `isPrefixOf` after]
        clean = filter (`notElem` ["noundef","noalias","nocapture","readonly","writeonly","allocptr"]) . words
        valid (before,after) = clean before == ["declare",result] &&
          map clean (split (takeWhile (/= ')') after)) == map (:[]) parameters
    unless (length declarations == 1 && all valid declarations)
      (Left ("native libc declaration has unsupported ABI: " ++ symbol))
  where
    split text = case break (== ',') text of
      (first,[]) -> [first]
      (first,_:rest) -> first : split rest

-- The configured Linux libstdc++ header emits these exact iostream lifetime
-- calls even for otherwise freestanding users such as original simdutf.
nativeCxxInitSymbols :: [String]
nativeCxxInitSymbols = ["_ZNSt8ios_base4InitC1Ev", "_ZNSt8ios_base4InitD1Ev"]

-- Sulong supplies context-owned atexit registration and its DSO identity.
-- These are not forwarded to a process-wide host atexit registry.
nativeLifecycleSymbols :: [String]
nativeLifecycleSymbols = ["__cxa_atexit", "__dso_handle"]

validateNativeLifecycleIR :: String -> [String] -> String -> Either String ()
validateNativeLifecycleIR target symbols source = do
  unless (target == "x86_64-unknown-linux-gnu")
    (Left "native C++ lifecycle provider currently requires Linux x86_64")
  forM_ symbols $ \symbol -> do
    unless (symbol `elem` nativeCxxInitSymbols ++ nativeLifecycleSymbols)
      (Left "unsupported native lifecycle symbol")
    let definitions = [line | line <- lines source, ("@" ++ symbol ++ " =") `isPrefixOf` line]
        declarations = [(before, arguments after) | line <- lines source,
          "declare " `isPrefixOf` line, let (before,rest) = break (== '@') line,
          let prefix = "@" ++ symbol ++ "(", prefix `isPrefixOf` rest,
          let after = drop (length prefix) rest]
        expected = if symbol == "__cxa_atexit" then ("i32",3) else ("void",1)
        valid (before,parameters) = words before == ["declare",fst expected] &&
          length parameters == snd expected && all pointer parameters
        pointer parameter = case words parameter of
          "ptr":attributes -> attributes `elem` [[],["noundef"],["noundef","nonnull","align","1","dereferenceable(1)"]]
          _ -> False
    unless (if symbol == "__dso_handle" then
        map words definitions == [["@__dso_handle","=","external","hidden","global","i8"]]
      else length declarations == 1 && all valid declarations)
      (Left ("native lifecycle declaration has unsupported ABI: " ++ symbol))
  where
    -- Parenthesized parameter attributes do not terminate the argument list.
    arguments = split . takeParameters (0::Int)
    takeParameters _ [] = []
    takeParameters depth (c:rest)
      | c == ')' && depth == 0 = []
      | otherwise = c : takeParameters (depth + if c == '(' then 1 else if c == ')' then -1 else 0) rest
    split text = case break (== ',') text of
      (first,[]) -> [first]
      (first,_:rest) -> first : split rest

-- These standard libm entries take one floating scalar and return the same
-- width. Check the linked LLVM declaration too, including package-owned C
-- callers: a familiar spelling alone cannot establish a native ABI.
validateNativeMathIR :: [String] -> String -> Either String ()
validateNativeMathIR symbols source = forM_ symbols $ \symbol -> do
  unless (symbol `elem` nativeMathSymbols) (Left "unsupported native math symbol")
  let rep = if symbol `elem` ["erf", "erfc"] then "double" else "float"
      declarations = [(before, drop (length symbol + 2) after) |
        line <- lines source, "declare " `isPrefixOf` line,
        let (before,after) = break (== '@') line,
        ("@" ++ symbol ++ "(") `isPrefixOf` after]
      valid (before, after) = filter (/= "noundef") (words before) == ["declare", rep] &&
        filter (/= "noundef") (words (takeWhile (/= ')') after)) == [rep]
  unless (length declarations == 1 && all valid declarations)
    (Left ("native libm declaration has unsupported ABI: " ++ symbol))

-- Linux x86_64 libc: int getentropy(void *, size_t). The caller's real C
-- stack/native/pinned address reaches libc through Sulong; this admits no
-- managed-heap copy or synthesized entropy implementation.
validateNativeEntropyIR :: String -> String -> Either String ()
validateNativeEntropyIR target source = do
  unless (target == "x86_64-unknown-linux-gnu")
    (Left "native getentropy provider currently requires Linux x86_64")
  let declarations = [(before, drop (length "@getentropy(") after) |
        line <- lines source, "declare " `isPrefixOf` line,
        let (before,after) = break (== '@') line,
        "@getentropy(" `isPrefixOf` after]
      valid (before,after) = filter (/= "noundef") (words before) == ["declare","i32"] &&
        map (filter (/= "noundef") . words) (arguments (takeWhile (/= ')') after)) == [["ptr"],["i64"]]
      arguments text = case break (== ',') text of
        (first,[]) -> [first]
        (first,_:rest) -> first : arguments rest
  unless (length declarations == 1 && all valid declarations)
    (Left "native getentropy declaration has unsupported ABI")

-- Linux wchar_t and int are both signed 32-bit values. Keep libc's current
-- locale behavior, including its -1 result; the Haskell caller owns fallback.
validateNativeWidthIR :: String -> String -> Either String ()
validateNativeWidthIR target source = do
  unless (target == "x86_64-unknown-linux-gnu")
    (Left "native wcwidth provider currently requires Linux x86_64")
  let declarations = [(before, drop (length "@wcwidth(") after) |
        line <- lines source, "declare " `isPrefixOf` line,
        let (before,after) = break (== '@') line, "@wcwidth(" `isPrefixOf` after]
      valid (before,after) = filter (/= "noundef") (words before) == ["declare","i32"] &&
        filter (/= "noundef") (words (takeWhile (/= ')') after)) == ["i32"]
  unless (length declarations == 1 && all valid declarations)
    (Left "native wcwidth declaration has unsupported ABI")

-- Compile the original implementation with the package's actual configured
-- zlib header. A mismatched installed version is a specific unsupported
-- provider, not permission to substitute an unrelated implementation.
-- The returned translation units are ordinary LLVM over managed pointers;
-- no heap buffer is copied or projected into native memory.
zlibChecksumSources :: FilePath -> IO [(String, String)]
zlibChecksumSources repository = do
  let directory = repository </> "third-party/pinned/zlib-1.2.11"
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
