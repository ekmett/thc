-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module THC.Driver.Cache (coreCacheDirectory) where

import System.Directory (XdgDirectory(XdgCache), getHomeDirectory, getXdgDirectory)
import System.Environment (lookupEnv)
import System.FilePath ((</>), isAbsolute)
import System.Info (os)

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
