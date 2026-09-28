-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.Cache
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Platform cache-directory conventions from directory and filepath
--
-- Choose the Core cache directory from explicit and platform-specific defaults.
module THC.Driver.Cache (coreCacheDirectory) where

import System.Directory (XdgDirectory(XdgCache), getHomeDirectory, getXdgDirectory)
import System.Environment (lookupEnv)
import System.FilePath ((</>), isAbsolute)
import System.Info (os)

-- | Resolve the cache location: absolute @THC_CACHE_HOME@ when set, otherwise
-- @Library/Caches/thc@ on macOS or the platform XDG cache directory.
-- A relative override is rejected, and this query does not create directories.
-- Cache files are disposable and shared between projects. Keep them out of
-- both the source checkout and the directories used for persistent app data.
coreCacheDirectory :: IO FilePath
coreCacheDirectory = do
  override <- lookupEnv "THC_CACHE_HOME"
  case override of
    Just path | isAbsolute path -> pure path
              | otherwise -> fail "THC_CACHE_HOME must be an absolute path"
    Nothing | os == "darwin" -> (</> "Library/Caches/thc") <$> getHomeDirectory
            | otherwise -> getXdgDirectory XdgCache "thc"
