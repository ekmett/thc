-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Compact.Annotations
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; source annotations independent of semantic records
--
-- Original display annotations in semantic emission order. They neither supply
-- executable identities nor participate in shape equality or interning.
module THC.Compact.Annotations where

import qualified Data.ByteString as BS
import Control.Monad (forM_, unless)
import Data.IORef
import Data.List (findIndex)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.Word (Word64)
import THC.Compact.Core (Identity(..))
import THC.Compact.Debug

data RecordKind = BindingRecord !Identity | BinderRecord !Word64 | ExpressionRecord
  deriving (Eq, Show)
data Annotation = Annotation !RecordKind !(Maybe BS.ByteString)
  !(Maybe BS.ByteString) ![BS.ByteString]
  deriving (Eq, Show)
type SourceCatalog = Map.Map BS.ByteString (SourceFile,SourcePosition)

data ModuleAnnotations = ModuleAnnotations
  { annotationSources :: !SourceCatalog
  , constructorNames :: ![(Word64,BS.ByteString)]
  } deriving (Eq, Show)

-- | Hooks observe actual DATA byte positions while records are written. They
-- cannot change the emitted semantic fields or recover origins by rescanning.
data RecordObserver = RecordObserver
  { enterRecord :: RecordKind -> Word64 -> IO ()
  , leaveRecord :: Word64 -> IO ()
  }

-- | Associate original annotations with the encoder's actual preorder records.
-- The completion action rejects missing/extra annotations instead of silently
-- assigning one source location to the wrong semantic node.
annotationObserver :: DebugEncoder -> SourceCatalog -> [Annotation] -> IO (RecordObserver, IO ())
annotationObserver debug catalog annotations = do
  pending <- newIORef annotations
  current <- newIORef (Nothing,0)
  stack <- newIORef []
  let enter kind offset = do
        remaining <- readIORef pending
        (name,source,notes) <- case remaining of
          Annotation expected name source notes : rest -> do
            unless (kind == expected) (fail "Compact annotation and semantic record orders differ")
            writeIORef pending rest
            pure (name,source,notes)
          [] -> fail "Missing compact record annotation"
        previous@(inherited,scope) <- readIORef current
        location <- resolveLocation catalog inherited source notes
        let binding = case kind of BindingRecord (Global _) -> offset; _ -> scope
        forM_ name $ \value -> case kind of
          BindingRecord (Global _) -> recordName debug binding 0 value
          BindingRecord (Local ordinal) -> localName binding ordinal value
          BinderRecord ordinal -> localName binding ordinal value
          ExpressionRecord -> fail "Expression annotation contains a declaration name"
        recordLocation debug offset location
        modifyIORef' stack (previous:)
        writeIORef current (location,binding)
      localName binding ordinal value = do
        unless (ordinal < maxBound) (fail "Compact local debug name ordinal overflow")
        recordName debug binding (ordinal+1) value
      leave offset = do
        parents <- readIORef stack
        case parents of
          previous@(location,_) : rest -> do
            recordLocation debug offset location
            writeIORef stack rest
            writeIORef current previous
          [] -> fail "Unbalanced compact annotation exit"
      finish = do
        rest <- readIORef pending
        parents <- readIORef stack
        unless (null rest && null parents) (fail "Unconsumed compact record annotations")
  pure (RecordObserver enter leave,finish)

resolveLocation :: SourceCatalog -> Maybe SourceLocation -> Maybe BS.ByteString -> [BS.ByteString]
  -> IO (Maybe SourceLocation)
resolveLocation _ inherited Nothing [] = pure inherited
resolveLocation catalog inherited primary ids = do
  let selectedId = case primary of Just value -> Just value; Nothing -> case reverse ids of value:_ -> Just value; [] -> Nothing
  selected <- traverse lookupNote selectedId
  explicit <- mapM lookupNote ids
  let previous = case inherited of Just (SourceLocation _ inheritedNotes) -> inheritedNotes; Nothing -> []
      notes = distinct Set.empty (previous ++ explicit ++ maybe [] (:[]) selected)
  case selected of
    Nothing -> pure inherited
    Just note -> case findIndex ((== noteId note) . noteId) notes of
      Nothing -> fail "Compact primary source note disappeared"
      Just index -> pure (Just (SourceLocation (fromIntegral index) notes))
  where
    lookupNote identifier = maybe (fail "Unknown original compact source note") pure (Map.lookup identifier catalog)
    noteId (_,SourcePosition identifier _ _ _ _ _ _ _) = identifier
    distinct _ [] = []
    distinct seen (value:rest)
      | Set.member (noteId value) seen = distinct seen rest
      | otherwise = value:distinct (Set.insert (noteId value) seen) rest
