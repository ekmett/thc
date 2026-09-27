-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Compact.Compression
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; pure command-line policy
--
-- Per-member CBD compression selection. A specific member override remains
-- independent of the global default, including when the default appears later.
module THC.Compact.Compression
  ( CbdMember(..), memberName, Compression, defaultCompression
  , setCompression, compressionLevel
  , parseEncodingOptions
  ) where

import qualified Data.Map.Strict as Map
import Data.List (stripPrefix, isPrefixOf)

-- | Exact, case-sensitive ZIP member names accepted by the CLI.
data CbdMember = HeaderMember | DataMember | StringsMember | SymbolsMember
  | NamesMember | FilenamesMember | LineColumnsMember
  deriving (Eq, Ord, Show, Enum, Bounded)

memberName :: CbdMember -> String
memberName member = case member of
  HeaderMember -> "header"
  DataMember -> "data"
  StringsMember -> "strings"
  SymbolsMember -> "symbols"
  NamesMember -> "names"
  FilenamesMember -> "filenames"
  LineColumnsMember -> "line-columns"

-- The constructor is private: every selected level is validated before any IO.
data Compression = Compression Int (Map.Map CbdMember Int)
  deriving (Eq, Show)

-- | All members use ZIP STORED unless explicitly configured.
defaultCompression :: Compression
defaultCompression = Compression 0 Map.empty

-- | Apply one @LEVEL@ or @TYPE=LEVEL@ argument. Levels are the decimal digits
-- 0 through 9; 0 selects STORED and the others select raw ZIP Deflate. The last
-- setting wins within its own scope; globals never erase member overrides.
--
-- >>> compressionLevel DataMember <$> (setCompression "data=1" defaultCompression >>= setCompression "9")
-- Right 1
setCompression :: String -> Compression -> Either String Compression
setCompression argument (Compression global overrides) = case break (== '=') argument of
  (value,[]) -> do
    level <- parseLevel value
    pure (Compression level overrides)
  (name,'=':value) -> do
    member <- case lookup name [(memberName member,member) | member <- [minBound..maxBound]] of
      Just found -> Right found
      Nothing -> Left ("Unknown CBD member type: " ++ name)
    level <- parseLevel value
    pure (Compression global (Map.insert member level overrides))
  _ -> Left "Expected CBD compression LEVEL or TYPE=LEVEL"
  where
    parseLevel [digit] | digit >= '0' && digit <= '9' = Right (fromEnum digit-fromEnum '0')
    parseLevel _ = Left "CBD compression level must be a decimal digit from 0 through 9"

-- | Resolve one member without losing the precedence of explicit overrides.
compressionLevel :: CbdMember -> Compression -> Int
compressionLevel member (Compression global overrides) = Map.findWithDefault global member overrides

-- | Parse all encoder options before the caller opens an input or creates an
-- output. The returned Boolean requests explicit omission of debug maps.
parseEncodingOptions :: [String] -> Either String (Compression,Bool,FilePath,FilePath)
parseEncodingOptions = go defaultCompression False
  where
    go policy _ ("--without-debug":rest) = go policy True rest
    go policy omit ("--cbd-compression":value:rest) = setCompression value policy >>= \next -> go next omit rest
    go _ _ ["--cbd-compression"] = Left "Missing --cbd-compression value"
    go policy omit (option:rest)
      | Just value <- stripPrefix "--cbd-compression=" option =
          setCompression value policy >>= \next -> go next omit rest
    go policy omit [source,destination]
      | not ("--" `isPrefixOf` source || "--" `isPrefixOf` destination) = Right (policy,omit,source,destination)
    go _ _ _ = Left "Expected encode [--without-debug] [--cbd-compression LEVEL|TYPE=LEVEL]... MODULE.json MODULE.cbd"
