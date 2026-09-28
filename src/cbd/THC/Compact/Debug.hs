-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns #-}

-- |
-- Module      : THC.Compact.Debug
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; segment-relative binary debug tables
--
-- Display-only names and source intervals. Tables are assembled alongside the
-- semantic stream; their selected readers never inspect executable records.
module THC.Compact.Debug
  ( SourceFile(..), SourcePosition(..), SourceLocation(..)
  , DebugEncoder, newDebugEncoder, recordName, recordLocation, finishDebug
  , nameAt, locationAt
  ) where

import Control.Monad (forM_, replicateM, unless, void, when)
import Data.Binary.Get hiding (label)
import Data.Binary.Put
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.IORef
import qualified Data.Map.Strict as Map
import qualified Data.Text.Encoding as Text
import Data.Word (Word64)
import THC.Compact.Core (Presence(..))
import THC.Compact.Wire
import THC.Compact.Writer

data SourceFile = SourceFile !BS.ByteString !BS.ByteString !(Presence BS.ByteString)
  deriving (Eq, Ord, Show)
data SourcePosition = SourcePosition !BS.ByteString !(Presence BS.ByteString)
  !Word64 !Word64 !Word64 !Word64 !(Presence Word64) !(Presence Word64)
  deriving (Eq, Ord, Show)
data SourceLocation = SourceLocation !Word64 ![(SourceFile,SourcePosition)]
  deriving (Eq, Ord, Show)

data Interval a = Interval !Word64 !(Maybe a) !Bool
  ![(Word64,Word64,Word64)] !(Map.Map (Maybe a) Word64)
data DebugEncoder = DebugEncoder !Streams !(BS.ByteString -> IO Span)
  !(IORef (Map.Map (Word64,Word64) Span))
  !(IORef (Interval [SourceFile])) !(IORef (Interval (Word64,[SourcePosition])))

newDebugEncoder :: Streams -> (BS.ByteString -> IO Span) -> IO DebugEncoder
newDebugEncoder streams strings = DebugEncoder streams strings <$> newIORef Map.empty
  <*> newIORef initial <*> newIORef initial
  where initial = Interval 0 Nothing False [] Map.empty

-- | Names are exact-keyed by enclosing binding position and ordinal slot.
-- Raw display bytes live in their own segment, never the semantic string pool.
recordName :: DebugEncoder -> Word64 -> Word64 -> BS.ByteString -> IO ()
recordName (DebugEncoder streams _ names _ _) binding slot value = do
  validUtf8 value
  entries <- readIORef names
  let key = (binding,slot)
  when (Map.member key entries) (fail "Duplicate compact debug name key")
  offset <- appendBytes streams RealNames value
  modifyIORef' names (Map.insert key (Span offset (fromIntegral (BS.length value))))

-- | Record an entry or restoration at the actual next DATA byte. Equal states
-- coalesce independently; a same-position restoration replaces an empty range.
recordLocation :: DebugEncoder -> Word64 -> Maybe SourceLocation -> IO ()
recordLocation (DebugEncoder streams strings _ filenames positions) offset location = do
  forM_ location validateLocation
  transition streams FilenameIntervals (putFiles strings) filenames offset
    (fmap (\(SourceLocation _ notes) -> map fst notes) location)
  transition streams LineColumnIntervals (pure . putPositions) positions offset
    (fmap (\(SourceLocation primary notes) -> (primary,map snd notes)) location)

transition :: Ord a => Streams -> Segment -> (a -> IO Put) -> IORef (Interval a)
  -> Word64 -> Maybe a -> IO ()
transition streams segment encode ref position next = do
  current@(Interval start state seen rows payloads) <- readIORef ref
  unless (position >= start) (fail "Compact debug transitions are not in DATA order")
  if state == next then pure () else do
    Interval _ _ _ rows' payloads' <- flushInterval streams segment encode position current
    writeIORef ref $! Interval position next (seen || maybe False (const True) next) rows' payloads'
  -- Keep the untouched fields strict even when there is no transition.
  rows `seq` payloads `seq` pure ()

flushInterval :: Ord a => Streams -> Segment -> (a -> IO Put) -> Word64 -> Interval a -> IO (Interval a)
flushInterval streams segment encode end current@(Interval start state seen rows payloads)
  | start == end = pure current
  | otherwise = do
      (offset,payloads') <- case Map.lookup state payloads of
        Just found -> pure (found,payloads)
        Nothing -> do
          payload <- maybe (pure (putWord8 0)) (fmap (putWord8 1 >>) . encode) state
          offset <- appendRecord streams segment payload
          pure (offset,Map.insert state offset payloads)
      pure $! Interval end state seen ((start,end,offset):rows) payloads'

-- | Append sorted name rows and already ordered source rows, followed by their
-- local directories. This does not seek or patch any construction stream.
finishDebug :: DebugEncoder -> Word64 -> IO ()
finishDebug (DebugEncoder streams strings names filenames positions) dataEnd = do
  entries <- readIORef names
  unless (Map.null entries) $ do
    index <- streamOffset streams RealNames
    void (appendRecord streams RealNames $ forM_ (Map.toAscList entries) $ \((binding,slot),Span start size) ->
      mapM_ putWord64le [binding,slot,start,size])
    void (appendRecord streams RealNames (putWord64le index >> putWord64le (fromIntegral (Map.size entries))))
  finishIntervals streams FilenameIntervals (putFiles strings) filenames dataEnd
  finishIntervals streams LineColumnIntervals (pure . putPositions) positions dataEnd

finishIntervals :: Ord a => Streams -> Segment -> (a -> IO Put) -> IORef (Interval a) -> Word64 -> IO ()
finishIntervals streams segment encode ref dataEnd = do
  current@(Interval start _ seen _ _) <- readIORef ref
  unless (start <= dataEnd) (fail "Compact source transition exceeds DATA")
  when seen $ do
    Interval _ _ _ reversed _ <- flushInterval streams segment encode dataEnd current
    let rows = reverse reversed
    unless (null rows) $ do
      index <- streamOffset streams segment
      void (appendRecord streams segment $ forM_ rows $ \(begin,end,payload) ->
        mapM_ putWord64le [begin,end,payload])
      void (appendRecord streams segment (putWord64le index >> putWord64le (fromIntegral (length rows))))

putFiles :: (BS.ByteString -> IO Span) -> [SourceFile] -> IO Put
putFiles strings files = do
  records <- mapM file files
  pure (putUVar (fromIntegral (length records)) >> sequence_ records)
  where
    file (SourceFile identifier path content) = do
      identifier' <- strings identifier
      path' <- strings path
      content' <- traversePresence strings content
      pure (putSpan identifier' >> putSpan path' >> putPresence putSpan content')

putPositions :: (Word64,[SourcePosition]) -> Put
putPositions (primary,positions) = do
  putUVar primary
  putUVar (fromIntegral (length positions))
  forM_ positions $ \(SourcePosition identifier label sl sc el ec index size) -> do
    putText identifier
    putPresence putText label
    mapM_ putUVar [sl,sc,el,ec]
    putPresence putUVar index
    putPresence putUVar size

putText :: BS.ByteString -> Put
putText bytes = putUVar (fromIntegral (BS.length bytes)) >> putByteString bytes

putPresence :: (a -> Put) -> Presence a -> Put
putPresence _ Missing = putWord8 0
putPresence _ Unknown = putWord8 1
putPresence encode (Known value) = putWord8 2 >> encode value

traversePresence :: Applicative f => (a -> f b) -> Presence a -> f (Presence b)
traversePresence _ Missing = pure Missing
traversePresence _ Unknown = pure Unknown
traversePresence action (Known value) = Known <$> action value

validateLocation :: SourceLocation -> IO ()
validateLocation (SourceLocation primary notes) = do
  unless (primary < fromIntegral (length notes)) (fail "Compact source primary index is out of range")
  forM_ notes $ \(_,position@(SourcePosition identifier label _ _ _ _ _ _)) -> do
    validUtf8 identifier
    forM_ (known label) validUtf8
    either fail pure (validatePosition position)
  where known (Known value) = [value]; known _ = []

validUtf8 :: BS.ByteString -> IO ()
validUtf8 = either (fail . show) (const (pure ())) . Text.decodeUtf8'

validatePosition :: SourcePosition -> Either String ()
validatePosition (SourcePosition _ _ sl sc el ec _ _) =
  unless (sl > 0 && sc > 0 && el > 0 && ec > 0 && (el,ec) >= (sl,sc))
    (Left "Invalid compact source coordinates")

-- | Selected exact name lookup. No names before or after the candidate decode.
nameAt :: BS.ByteString -> Word64 -> Word64 -> Either String (Maybe BS.ByteString)
nameAt bytes binding slot
  | BS.null bytes = Right Nothing
  | otherwise = do
      (index,count) <- table bytes 32
      let row n = at bytes (index+32*n) 32 $ (,,,) <$> getWord64le <*> getWord64le <*> getWord64le <*> getWord64le
          search lo hi
            | lo == hi = Right Nothing
            | otherwise = do
                let middle = lo+(hi-lo) `div` 2
                (key,ordinal,start,size) <- row middle
                case compare (binding,slot) (key,ordinal) of
                  LT -> search lo middle
                  GT -> search (middle+1) hi
                  EQ -> Just <$> utf8Span (BS.take (fromIntegral index) bytes) (Span start size)
      search 0 count

-- | Demand-only paired source lookup. Independent table boundaries are allowed;
-- selected payload note counts and ordering are joined without a table prescan.
locationAt :: BS.ByteString -> BS.ByteString -> BS.ByteString -> Word64 -> Word64
  -> Either String (Maybe SourceLocation)
locationAt filenames positions strings dataSize offset = do
  files <- intervalAt filenames dataSize offset (\limit -> getList limit (getFile strings))
  coords <- intervalAt positions dataSize offset (\limit -> (,) <$> getUVar <*> getList limit (getPosition limit))
  case (files,coords) of
    (Nothing,Nothing) -> Right Nothing
    (Just fs,Just (primary,ps)) -> do
      unless (length fs == length ps && primary < fromIntegral (length ps))
        (Left "Mismatched compact source-note tables")
      pure (Just (SourceLocation primary (zip fs ps)))
    _ -> Left "Mismatched compact source presence"

intervalAt :: BS.ByteString -> Word64 -> Word64 -> (Word64 -> Get a) -> Either String (Maybe a)
intervalAt bytes dataSize position decode
  | BS.null bytes || position >= dataSize = Right Nothing
  | otherwise = do
      (index,count) <- table bytes 24
      let row n = at bytes (index+24*n) 24 ((,,) <$> getWord64le <*> getWord64le <*> getWord64le)
          upper lo hi
            | lo == hi = pure lo
            | otherwise = do
                let middle = lo+(hi-lo) `div` 2
                (start,_,_) <- row middle
                if start <= position then upper (middle+1) hi else upper lo middle
      candidate <- upper 0 count
      if candidate == 0 then pure Nothing else do
        (start,end,payload) <- row (candidate-1)
        unless (start < end && end <= dataSize) (Left "Invalid compact source interval")
        if position >= end then pure Nothing else do
          unless (payload < index) (Left "Compact source payload exceeds its region")
          prefixGet (BS.take (fromIntegral (index-payload)) (BS.drop (fromIntegral payload) bytes)) $ do
            tag <- getWord8
            case tag of 0 -> pure Nothing; 1 -> Just <$> decode (index-payload); _ -> fail "Invalid compact source payload tag"

table :: BS.ByteString -> Word64 -> Either String (Word64,Word64)
table bytes width = do
  unless (BS.length bytes >= 16) (Left "Truncated compact debug directory")
  (index,count) <- decodeExact ((,) <$> getWord64le <*> getWord64le) (BS.drop (BS.length bytes-16) bytes)
  let limit = fromIntegral (BS.length bytes-16)
  unless (index <= limit && count <= (limit-index) `div` width && count*width == limit-index)
    (Left "Invalid compact debug index extent")
  pure (index,count)

at :: BS.ByteString -> Word64 -> Word64 -> Get a -> Either String a
at bytes start size decode = do
  unless (start <= fromIntegral (BS.length bytes) && size <= fromIntegral (BS.length bytes)-start)
    (Left "Compact debug read exceeds segment")
  decodeExact decode (BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes))

prefixGet :: BS.ByteString -> Get a -> Either String a
prefixGet bytes decode = case runGetOrFail decode (BL.fromStrict bytes) of
  Left (_,_,message) -> Left message
  Right (_,_,value) -> Right value

utf8Span :: BS.ByteString -> Span -> Either String BS.ByteString
utf8Span bytes (Span start size) = do
  unless (start <= fromIntegral (BS.length bytes) && size <= fromIntegral (BS.length bytes)-start)
    (Left "Compact debug string exceeds segment")
  let value = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
  either (Left . show) (const (Right value)) (Text.decodeUtf8' value)

getPresence :: Get a -> Get (Presence a)
getPresence decode = do
  tag <- getWord8
  case tag of 0 -> pure Missing; 1 -> pure Unknown; 2 -> Known <$> decode; _ -> fail "Invalid compact debug presence"

getList :: Word64 -> Get a -> Get [a]
getList limit decode = do
  count <- getUVar
  consumed <- bytesRead
  unless (fromIntegral consumed <= limit && count <= limit-fromIntegral consumed) (fail "Compact debug count exceeds payload")
  replicateM (fromIntegral count) decode

getText :: Word64 -> Get BS.ByteString
getText limit = do
  size <- getUVar
  consumed <- bytesRead
  unless (fromIntegral consumed <= limit && size <= limit-fromIntegral consumed) (fail "Compact debug text exceeds payload")
  value <- getByteString (fromIntegral size)
  either (fail . show) (const (pure value)) (Text.decodeUtf8' value)

getFile :: BS.ByteString -> Get SourceFile
getFile strings = SourceFile <$> string <*> string <*> getPresence string
  where string = getSpan >>= either fail pure . utf8Span strings

getPosition :: Word64 -> Get SourcePosition
getPosition limit = do
  value <- SourcePosition <$> getText limit <*> getPresence (getText limit) <*> getUVar <*> getUVar <*> getUVar <*> getUVar
    <*> getPresence getUVar <*> getPresence getUVar
  either fail pure (validatePosition value)
  pure value
