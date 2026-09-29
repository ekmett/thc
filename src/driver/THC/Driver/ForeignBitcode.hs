-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.ForeignBitcode
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC utilities and host filesystem/process services
--
-- Acquire and validate the retained CPU-time and time-package CAPI clock wrappers.
module THC.Driver.ForeignBitcode (linkClockGetTime, timeClockHeaders) where

import Control.Exception (finally)
import Control.Monad (filterM, forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Char (intToDigit)
import Data.List (isInfixOf, isSuffixOf, nub)
import Data.Maybe (catMaybes, isJust)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as TextEncoding
import GHC.Utils.Encoding (zEncodeString)
import System.Directory (createDirectory, createDirectoryIfMissing, doesDirectoryExist, doesFileExist,
                         listDirectory, removePathForcibly)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory, takeFileName)
import System.IO (hClose, openTempFile)
import System.Process (readCreateProcessWithExitCode, proc)
import THC.Compact.Module (readModuleValue, finalizeModuleMetadata)

-- A complete no-callback CAPI module is the first executable archive. The
-- compiler recipe is reusable, but no other module gains execution permission
-- merely because it has an apparently similar C symbol.
linkClockGetTime :: FilePath -> [FilePath] -> FilePath -> String -> String -> String -> FilePath -> IO FilePath
linkClockGetTime libdir includes staging platform unit name original
  | name `notElem` ["System.CPUTime.Posix.ClockGetTime", "Data.Time.Clock.Internal.CTimespec"] = pure original
  | timeClock && not ("-linux" `isSuffixOf` platform) = pure original
  | otherwise = do
      unless (not timeClock || unit == "time-1.15-inplace" ||
        case splitAt (length ("time-1.15-" :: String)) unit of
          ("time-1.15-", suffix) -> not (null suffix) && all (`elem` ("0123456789abcdef" :: String)) suffix
          _ -> False) (fail "CTimespec requires the original time-1.15 unit")
      -- This legacy fixture compiler validates the actual FCallIds before C
      -- compilation. Final metadata installation itself never reads DATA.
      value <- BS.readFile original >>= either fail pure . readModuleValue
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
      let calls = (if timeClock then filter (ownedCall unit) else id) (collectCalls value)
          classified = map (capiAbi timeClock unit) calls
          abi = nub (catMaybes classified)
          symbols = map fst abi
      unless (all isJust classified && length abi == 3 &&
              (if timeClock then sortAbi abi == sortAbi (timeSymbols unit)
               else length [() | (_, kind) <- abi, kind == "clock-id"] == 1 &&
                    length [() | (_, kind) <- abi, kind == "clock-buffer"] == 2) &&
              length symbols == length (nub symbols))
        (fail "ClockGetTime CAPI contract differs from its three generated wrappers")
      -- Find the selected GHC's HsFFI.h, not a different compiler on PATH.
      header <- findHeader libdir
      headers <- if timeClock then timeClockHeaders libdir includes else pure []
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
        writeFile cfile ("#include \"HsFFI.h\"\n#include <errno.h>\n#include <time.h>\n#include <stddef.h>\n" ++
          Text.unpack source ++ "\n" ++ unlines
          ["_Static_assert(sizeof(struct timespec) == 16, \"unsupported timespec ABI\");",
           "_Static_assert(offsetof(struct timespec, tv_sec) == 0 && offsetof(struct timespec, tv_nsec) == 8, \"unsupported timespec offsets\");",
           "_Static_assert(sizeof(((struct timespec *)0)->tv_sec) == 8 && sizeof(((struct timespec *)0)->tv_nsec) == 8, \"unsupported timespec fields\");",
           "HsInt32 thc_capi_errno(void) { return errno; }"])
        run clang (targetFlags ++ ["-O1", "-emit-llvm", "-c", "-I", takeDirectory header,
                   cfile, "-o", bitcode] ++ concatMap (\path -> ["-I", path]) includes)
        BS.readFile bitcode) `finally` cleanup
      after <- if timeClock then timeClockHeaders libdir includes else pure []
      unless (after == headers) (fail "Selected clock headers changed during CAPI compilation")
      let linked = object (["schema" .= (if timeClock then 3 else 2 :: Int), "format" .= ("llvm-bitcode" :: String),
                           "unit" .= unit, "module" .= name,
                           "sourceSha256" .= sha (TextEncoding.encodeUtf8 source),
                           "bitcodeSha256" .= sha bytes, "bitcodeHex" .= hex bytes,
                           "target" .= target,
                           "symbols" .= symbols,
                           "abi" .= [object ["symbol" .= symbol, "kind" .= kind] | (symbol, kind) <- abi]] ++
                           ["headerHashes" .= [object ["name" .= takeFileName path, "sha256" .= digest] |
                             (path, digest) <- headers] | timeClock])
      finalizeModuleMetadata original (Object (KeyMap.insert "foreignLink" linked fields))
      pure original
  where
    sha = hex . SHA.hash
    timeClock = name == "Data.Time.Clock.Internal.CTimespec"
    sortAbi = Map.fromList

-- The installed registration supplies the configured package headers; never
-- replace HS_CLOCK_REALTIME with a guessed constant or a fixture declaration.
timeClockHeaders :: FilePath -> [FilePath] -> IO [(FilePath, String)]
timeClockHeaders libdir includes = do
  ffi <- findHeader libdir
  paths <- forM ["HsTime.h", "HsTimeConfig.h"] $ \name -> do
    found <- filterM doesFileExist [directory </> name | directory <- includes]
    case nub found of
      [path] -> pure path
      _ -> fail ("Selected time unit must provide exactly one " ++ name)
  forM (ffi : paths) $ \path -> do
    digest <- hex . SHA.hash <$> BS.readFile path
    pure (path, digest)

timeSymbols :: String -> [(String, String)]
timeSymbols unit = [(prefix 0 ++ "HSzuCLOCKzuREALTIME", "time-clock-id"),
                    (prefix 1 ++ "clockzugetres", "time-clock-resolution"),
                    (prefix 2 ++ "clockzugettime", "time-clock-time")]
  where prefix index = "ghczuwrapperZC" ++ show (index :: Int) ++ "ZC" ++ zEncodeString unit ++
          "ZCDataziTimeziClockziInternalziCTimespecZC"

ownedCall :: String -> Value -> Bool
ownedCall unit (Object fields) = case KeyMap.lookup "target" fields of
  Just (Object target) -> KeyMap.lookup "unit" target == Just (String (Text.pack unit))
  _ -> False
ownedCall _ _ = False

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

capiAbi :: Bool -> String -> Value -> Maybe (String, String)
capiAbi timeClock unit call = do
  Object fields <- Just call
  Object target <- KeyMap.lookup "target" fields
  String symbol <- KeyMap.lookup "symbol" target
  let zeroKind = if timeClock then "time-clock-id" else "clock-id"
      wordRep = if timeClock then "Int32Rep" else "Word64Rep"
      expected :: String -> Value
      expected kind = object
        ["schema" .= (1 :: Int),
         "target" .= object ["kind" .= ("static" :: String), "symbol" .= symbol,
                              "unit" .= unit, "isFunction" .= True],
         "convention" .= ("capi" :: String), "safety" .= ("unsafe" :: String),
         "arity" .= (if kind == zeroKind then (1 :: Int) else 3),
         "suppliedArity" .= (if kind == zeroKind then (1 :: Int) else 3),
         "argumentReps" .= (if kind == zeroKind then [scalar Nothing False]
                            else [scalar (Just wordRep) False,
                                  scalar (Just "AddrRep") False, scalar Nothing False]),
         "resultRep" .= tuple (if kind == zeroKind then wordRep else "Int32Rep") False]
  if timeClock then do
    kind <- lookup (Text.unpack symbol) (timeSymbols unit)
    if call == expected kind then Just (Text.unpack symbol, kind) else Nothing
  else if call == expected "clock-id" then Just (Text.unpack symbol, "clock-id")
  else if call == expected "clock-buffer" then Just (Text.unpack symbol, "clock-buffer")
  else Nothing
  where
    scalar primitive evaluated = object
      ["kind" .= case primitive of Nothing -> ("void" :: String)
                                   Just "AddrRep" -> "address"
                                   Just _ -> "long",
       "primReps" .= maybe ([] :: [String]) pure primitive,
       "evaluated" .= evaluated]
    tuple primitive evaluated = object
      ["kind" .= ("unknown" :: String), "primReps" .= [primitive],
       "aggregate" .= ("unboxed-tuple" :: String),
       "components" .= [scalar Nothing True, scalar (Just primitive) True],
       "evaluated" .= evaluated]

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
