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
import Data.Maybe (catMaybes, isJust)
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
          classified = map (capiAbi unit) calls
          abi = nub (catMaybes classified)
          symbols = map fst abi
      unless (all isJust classified && length abi == 3 &&
              length [() | (_, kind) <- abi, kind == "clock-id"] == 1 &&
              length [() | (_, kind) <- abi, kind == "clock-buffer"] == 2 &&
              length symbols == length (nub symbols))
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
        writeFile cfile ("#include \"HsFFI.h\"\n#include <errno.h>\n#include <time.h>\n" ++
          Text.unpack source ++ "\n" ++ unlines
          ["_Static_assert(sizeof(struct timespec) == 16, \"unsupported timespec ABI\");",
           "HsInt32 thc_capi_errno(void) { return errno; }"])
        run clang (targetFlags ++ ["-O1", "-emit-llvm", "-c", "-I", takeDirectory header,
                   cfile, "-o", bitcode])
        BS.readFile bitcode) `finally` cleanup
      let linked = object ["schema" .= (2 :: Int), "format" .= ("llvm-bitcode" :: String),
                           "unit" .= unit, "module" .= name,
                           "sourceSha256" .= sha (TextEncoding.encodeUtf8 source),
                           "bitcodeSha256" .= sha bytes, "bitcodeHex" .= hex bytes,
                           "target" .= target,
                           "symbols" .= symbols,
                           "abi" .= [object ["symbol" .= symbol, "kind" .= kind] | (symbol, kind) <- abi]]
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

capiAbi :: String -> Value -> Maybe (String, String)
capiAbi unit call = do
  Object fields <- Just call
  Object target <- KeyMap.lookup "target" fields
  String symbol <- KeyMap.lookup "symbol" target
  let expected :: String -> Value
      expected kind = object
        ["schema" .= (1 :: Int),
         "target" .= object ["kind" .= ("static" :: String), "symbol" .= symbol,
                              "unit" .= unit, "isFunction" .= True],
         "convention" .= ("capi" :: String), "safety" .= ("unsafe" :: String),
         "arity" .= (if kind == "clock-id" then (1 :: Int) else 3),
         "suppliedArity" .= (if kind == "clock-id" then (1 :: Int) else 3),
         "argumentReps" .= (if kind == "clock-id" then [scalar Nothing False]
                            else [scalar (Just "Word64Rep") False,
                                  scalar (Just "AddrRep") False, scalar Nothing False]),
         "resultRep" .= tuple (if kind == "clock-id" then "Word64Rep" else "Int32Rep") False]
  if call == expected "clock-id" then Just (Text.unpack symbol, "clock-id")
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
