-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module THC.Driver.ForeignBitcode (linkClockGetTime) where

import Control.Exception (finally)
import Control.Monad (forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), eitherDecodeStrict', encode, object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (intToDigit)
import Data.List (isInfixOf, isSuffixOf, nub)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as TextEncoding
import System.Directory (createDirectory, createDirectoryIfMissing, doesDirectoryExist,
                         listDirectory, removePathForcibly)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory)
import System.IO (hClose, openTempFile)
import System.Process (readCreateProcessWithExitCode, proc)

-- A complete no-callback CAPI module is the first executable archive. The
-- compiler recipe is reusable, but no other module gains execution permission
-- merely because it has an apparently similar C symbol.
linkClockGetTime :: FilePath -> FilePath -> String -> String -> String -> BS.ByteString -> IO BS.ByteString
linkClockGetTime libdir staging platform unit name original
  | name /= "System.CPUTime.Posix.ClockGetTime" = pure original
  | otherwise = do
      value <- either fail pure (eitherDecodeStrict' original)
      fields <- case value of
        Object objectFields -> pure objectFields
        _ -> fail "CAPI Core must be an object"
      unless (KeyMap.lookup "unit" fields == Just (String (Text.pack unit)))
        (fail "CAPI Core owner differs from installed unit")
      archive <- case KeyMap.lookup "foreign" fields of
        Just (Object record) -> pure record
        _ -> fail "ClockGetTime lacks original foreign archive"
      unless (KeyMap.lookup "execution" archive == Just "not-linked" &&
              KeyMap.lookup "files" archive == Just (Array mempty))
        (fail "ClockGetTime has unsupported foreign files or execution state")
      stubs <- case KeyMap.lookup "stubs" archive of
        Just (Object record) -> pure record
        _ -> fail "ClockGetTime lacks original C stubs"
      source <- case KeyMap.lookup "source" stubs of
        Just (String text) | not (Text.null text) -> pure text
        _ -> fail "ClockGetTime has no C source"
      unless (KeyMap.lookup "header" stubs == Just "" &&
              KeyMap.lookup "initializers" stubs == Just (Array mempty) &&
              KeyMap.lookup "finalizers" stubs == Just (Array mempty))
        (fail "ClockGetTime requires unsupported callbacks or initialization")
      let calls = collectCalls value
          symbols = nub [symbol | Object descriptor <- calls,
                                  Just (Object target) <- [KeyMap.lookup "target" descriptor],
                                  Just (String symbol) <- [KeyMap.lookup "symbol" target]]
      unless (length symbols == 3 && all capiCall calls)
        (fail "ClockGetTime CAPI contract differs from its three generated wrappers")
      -- Find the selected GHC's HsFFI.h, not a different compiler on PATH.
      header <- findHeader libdir
      clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
      defaultTarget <- output clang ["-dumpmachine"]
      let cpu = takeWhile (/= '-') platform
          linux = "-linux" `isSuffixOf` platform
          darwin = "-darwin" `isSuffixOf` platform
          hostCpu = takeWhile (/= '-') defaultTarget
          cpuMatches = hostCpu == cpu || (hostCpu == "arm64" && cpu == "aarch64")
          targetFlags = if linux then ["--target=" ++ cpu ++ "-unknown-linux-gnu"] else []
      unless (cpuMatches && ((linux && "linux-gnu" `isInfixOf` defaultTarget) ||
              (darwin && "darwin" `isInfixOf` defaultTarget)))
        (fail "CAPI compiler target differs from selected GHC platform")
      target <- output clang (targetFlags ++ ["-dumpmachine"])
      unless (if linux then target == cpu ++ "-unknown-linux-gnu" else cpuMatches)
        (fail "CAPI compiler did not select the verified GHC target")
      createDirectoryIfMissing True staging
      (temporary, handle) <- openTempFile staging "thc-clock-capi-"
      hClose handle
      removePathForcibly temporary
      createDirectory temporary
      let cfile = temporary </> "clock.c"
          bitcode = temporary </> "clock.bc"
          cleanup = removePathForcibly temporary
      bytes <- (do
        writeFile cfile ("#include \"HsFFI.h\"\n#include <errno.h>\n#include <stdlib.h>\n#include <time.h>\n" ++
          Text.unpack source ++ "\n" ++ unlines
          ["_Static_assert(sizeof(struct timespec) == 16, \"unsupported timespec ABI\");",
           "void *thc_capi_alloc_timespec(void) {",
           "  void *native = malloc(sizeof(struct timespec));",
           "  if (!native) errno = ENOMEM;",
           "  return native;",
           "}",
           "void thc_capi_copy_timespec(void *native, void *managed) {",
           "  struct timespec *value = (struct timespec *)native;",
           "    volatile HsWord64 *result = (volatile HsWord64 *)managed;",
           "    result[0] = (HsWord64)value->tv_sec;",
           "    result[1] = (HsWord64)value->tv_nsec;",
           "}",
           "void thc_capi_free_timespec(void *native) { free(native); }",
           "HsInt32 thc_capi_errno(void) { return errno; }"])
        run clang (targetFlags ++ ["-O1", "-emit-llvm", "-c", "-I", takeDirectory header,
                   cfile, "-o", bitcode])
        BS.readFile bitcode) `finally` cleanup
      let linked = object ["schema" .= (1 :: Int), "format" .= ("llvm-bitcode" :: String),
                           "unit" .= unit, "module" .= name,
                           "sourceSha256" .= sha (TextEncoding.encodeUtf8 source),
                           "bitcodeSha256" .= sha bytes, "bitcodeHex" .= hex bytes,
                           "target" .= target,
                           "symbols" .= symbols]
      pure (BL.toStrict (encode (Object (KeyMap.insert "foreignLink" linked fields))))
  where
    sha = hex . SHA.hash

output :: FilePath -> [String] -> IO String
output tool arguments = do
  (status, text, diagnostic) <- readCreateProcessWithExitCode (proc tool arguments) ""
  unless (status == ExitSuccess) (fail (tool ++ " failed: " ++ diagnostic))
  case lines text of
    line : _ -> pure line
    [] -> fail (tool ++ " returned an empty result")

collectCalls :: Value -> [Value]
collectCalls value = case value of
  Object fields -> maybe [] (:[]) (KeyMap.lookup "foreignCall" fields) ++
                   concatMap collectCalls (KeyMap.elems fields)
  Array values -> concatMap collectCalls values
  _ -> []

capiCall :: Value -> Bool
capiCall (Object fields) = KeyMap.lookup "convention" fields == Just "capi" &&
    KeyMap.lookup "safety" fields == Just "unsafe" &&
    case KeyMap.lookup "target" fields of
      Just (Object target) -> KeyMap.lookup "kind" target == Just "static" &&
        KeyMap.lookup "isFunction" target == Just (Bool True)
      _ -> False
capiCall _ = False

hex :: BS.ByteString -> String
hex = concatMap (\byte -> [intToDigit (fromIntegral byte `div` 16),
                            intToDigit (fromIntegral byte `mod` 16)]) . BS.unpack

run :: FilePath -> [String] -> IO ()
run tool arguments = do
  (status, _, diagnostic) <- readCreateProcessWithExitCode (proc tool arguments) ""
  unless (status == ExitSuccess) (fail (tool ++ " failed: " ++ diagnostic))

findHeader :: FilePath -> IO FilePath
findHeader root = do
  found <- search root
  case found of
    [path] -> pure path
    _ -> fail "Selected GHC must provide exactly one HsFFI.h"
  where
    search directory = do
      entries <- listDirectory directory
      fmap concat $ forM entries $ \name -> do
        let path = directory </> name
        if name == "HsFFI.h" then pure [path]
        else do
          child <- doesDirectoryExist path
          if child then search path else pure []
