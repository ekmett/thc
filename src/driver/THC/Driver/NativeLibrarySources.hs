-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.NativeLibrarySources
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell; target-specific native link options
--
-- Capture declared native link inputs, not a list of tested native symbols.
module THC.Driver.NativeLibrarySources
  ( nativeLinkOptions, nativePackageOptions, nativePackageSelectors
  , packageNativeLibraries ) where

import Data.List (isPrefixOf)

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
