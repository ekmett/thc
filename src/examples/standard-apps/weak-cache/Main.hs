-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell2010
--
-- Memoize document analysis without retaining unused document revisions.
module Main (main) where

import Control.Concurrent (threadDelay)
import Control.Exception (evaluate)
import Control.Monad (filterM, unless)
import Data.IORef (IORef, modifyIORef', newIORef, readIORef, writeIORef)
import Data.Maybe (isJust)
import System.Mem (performGC)
import System.Mem.Weak (Weak, deRefWeak, mkWeak)

type Cache key value = IORef [Weak (key, value)]

-- The registry owns only weak handles. Dropping dead entries never stores a
-- lazy projection of a dereferenced (key, value) pair back into the registry.
memo :: Eq key => Cache key value -> key -> IO value -> IO value
memo cache key build = readIORef cache >>= findEntry []
  where
    findEntry live [] = do
      value <- build
      weak <- mkWeak key (key, value) Nothing
      writeIORef cache (weak : reverse live)
      pure value
    findEntry live (weak : rest) = do
      entry <- deRefWeak weak
      case entry of
        Nothing -> findEntry live rest
        Just (other, value)
          | key == other -> do
              writeIORef cache (reverse live ++ weak : rest)
              pure value
          | otherwise -> findEntry (weak : live) rest

prune :: Cache key value -> IO Int
prune cache = do
  live <- readIORef cache >>= filterM (fmap isJust . deRefWeak)
  writeIORef cache live
  pure (length live)

-- A document revision has identity; its text is not edited in place.
data Document = Document !(IORef String)

{-# OPAQUE sameDocument #-}
sameDocument :: Document -> Document -> Bool
sameDocument (Document left) (Document right) = left == right

instance Eq Document where
  (==) = sameDocument

{-# OPAQUE documentText #-}
documentText :: Document -> IO String
documentText (Document text) = readIORef text

-- The cached result really points back to its key, and its counts stay lazy.
data Report = Report Document Int Int

analyze :: IORef Int -> Document -> IO Report
analyze builds document = do
  modifyIORef' builds (+ 1)
  text <- documentText document
  pure (Report document (length (lines text)) (length (words text)))

reportSummary :: Report -> IO (Int, Int, String)
reportSummary (Report document lineCount wordCount) = do
  text <- documentText document
  pure (lineCount, wordCount, takeWhile (/= '\n') text)

-- Finish each use before GC so a caller-held Report cannot keep the cache hit alive.
{-# OPAQUE useReport #-}
useReport :: String -> Cache Document Report -> IORef Int -> Document -> IO ()
useReport label cache builds document = do
  report <- memo cache document (analyze builds document)
  summary <- reportSummary report
  unless (summary == (2, 5, "red blue red")) (fail "cached analysis changed")
  putStrLn (label ++ ": " ++ show summary)

{-# OPAQUE useDocument #-}
useDocument :: Cache Document Report -> IORef Int -> IO ()
useDocument cache builds = do
  document <- newIORef "red blue red\nblue green\n" >>= evaluate . Document
  useReport "First request" cache builds document
  performGC
  threadDelay 10000
  useReport "Second request" cache builds document
  count <- readIORef builds
  unless (count == 1) (fail "live document analysis was recomputed")
  putStrLn "Two requests reused one analysis."

awaitEmpty :: Cache key value -> IO ()
awaitEmpty cache = do
  performGC
  threadDelay 10000
  remaining <- prune cache
  unless (remaining == 0) (awaitEmpty cache)

-- | Keep the cache alive while its last document and report leave scope.
-- Collection timing is deliberately unspecified.
main :: IO ()
main = do
  cache <- newIORef []
  builds <- newIORef 0
  useDocument cache builds
  awaitEmpty cache
  putStrLn "The retained cache released the document and its report."
