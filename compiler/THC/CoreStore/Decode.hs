-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE LambdaCase #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell; 64-bit host required for large input files
--
-- Whole-store decoding for explicit inspection and structural audit. This is
-- not the on-demand runtime reader: it verifies every block and retains one
-- record per physical ID, never the expanded JSON tree.
module THC.CoreStore.Decode (decodeStore) where

import Control.Monad (ap, foldM, replicateM, unless)
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Bits ((.&.), (.|.), shiftL, testBit)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as BB
import qualified Data.ByteString.Lazy as BL
import Data.Int (Int64)
import qualified Data.Sequence as Seq
import qualified Data.Text.Encoding as Text
import Data.Word (Word8, Word64)
import THC.CoreStore.Binary (unsignedLEB, signedLEB)
import THC.CoreStore.Model

newtype Parser a = Parser { parse :: BS.ByteString -> Either String (a,BS.ByteString) }
instance Functor Parser where fmap f (Parser g) = Parser $ \input -> do (a,rest) <- g input; pure (f a,rest)
instance Applicative Parser where pure a = Parser $ \input -> Right (a,input); (<*>) = ap
instance Monad Parser where Parser f >>= g = Parser $ \input -> do (a,rest) <- f input; parse (g a) rest

reject :: String -> Parser a
reject message = Parser $ \_ -> Left ("Core store: " ++ message)
require :: Bool -> String -> Parser ()
require condition message = unless condition (reject message)
takeBytes :: Int -> Parser BS.ByteString
takeBytes count = Parser $ \input -> if count >= 0 && count <= BS.length input
  then Right (BS.take count input,BS.drop count input) else Left "Core store: truncated field"
byte :: Parser Word8
byte = BS.head <$> takeBytes 1
little :: Int -> Parser Word64
little width = do
  value <- takeBytes width
  pure (sum [fromIntegral item `shiftL` (8 * index) | (index,item) <- zip [0..] (BS.unpack value)])
hostInt :: Word64 -> Parser Int
hostInt value = require (toInteger value <= toInteger (maxBound::Int)) "size exceeds host indexing" >> pure (fromIntegral value)
u16, u32 :: Parser Int
u16 = little 2 >>= hostInt
u32 = little 4 >>= hostInt
u64 :: Parser Word64
u64 = little 8
exact :: Parser a -> BS.ByteString -> Either String a
exact parser input = do
  (value,rest) <- parse parser input
  unless (BS.null rest) (Left "Core store: trailing record/index bytes")
  pure value
liftEither :: Either String a -> Parser a
liftEither value = either reject pure value

uleb :: Parser Int
uleb = do
  (value,encoded) <- collect 0 0 []
  require (value <= 0xffffffff) "ULEB exceeds unsigned32"
  require (BS.pack (reverse encoded) == builderBytes (unsignedLEB (fromInteger value))) "noncanonical ULEB"
  hostInt (fromInteger value)
  where
    collect bit value encoded = do
      require (bit <= 28) "overlong ULEB"
      item <- byte
      let next = value .|. (toInteger (item .&. 127) `shiftL` bit)
      if item .&. 128 == 0 then pure (next,item:encoded) else collect (bit+7) next (item:encoded)

sleb :: Parser Integer
sleb = do
  (value,encoded) <- collect 0 0 []
  require (value >= toInteger (minBound::Int64) && value <= toInteger (maxBound::Int64)) "SLEB exceeds signed64"
  require (BS.pack (reverse encoded) == builderBytes (signedLEB (fromInteger value))) "noncanonical SLEB"
  pure value
  where
    collect bit value encoded = do
      require (bit <= 63) "overlong SLEB"
      item <- byte
      let next = value .|. (toInteger (item .&. 127) `shiftL` bit)
      if item .&. 128 == 0
        then pure (if testBit item 6 then next - (1 `shiftL` (bit+7)) else next,item:encoded)
        else collect (bit+7) next (item:encoded)

builderBytes :: BB.Builder -> BS.ByteString
builderBytes = BL.toStrict . BB.toLazyByteString

boolean :: Parser Bool
boolean = byte >>= \value -> require (value <= 1) "invalid boolean" >> pure (value == 1)
ref :: Parser Ref
ref = Ref <$> uleb
scope :: Parser Scope
scope = Scope <$> uleb
binder :: Parser Binder
binder = Binder <$> uleb

data Header = Header !Int !Int !Int !Int !Int !Int !Int
header :: Int -> Parser Header
header actualSize = do
  magic <- takeBytes 8
  require (magic == BS.pack [84,72,67,67,79,82,69,0]) "wrong magic"
  major <- u16; flags <- u16; headerBytes <- u32
  require (major == 1 && flags == 0 && headerBytes == 64) "unsupported header version/flags"
  total <- u64 >>= hostInt; indexOffset <- u64; indexSize <- u64 >>= hostInt
  count <- u32; scopes <- u32; binders <- u32; nodes <- u32; payloads <- u32; reserved <- u32
  require (total == actualSize && indexOffset == 64 && reserved == 0) "header length/offset/reserved mismatch"
  require (count > 0 && nodes == 1 + (count-1) `div` 256) "node page count mismatch"
  require (scopes > 0) "missing module scope"
  require (indexSize <= total - 64) "index exceeds file"
  pure (Header indexSize count scopes binders nodes payloads total)

data Block = Block !Int !Int !BS.ByteString
block :: Parser Block
block = do
  offset <- u64 >>= hostInt; size <- u32; reserved <- u32; digest <- takeBytes 32
  require (reserved == 0 && size > 0 && size <= 65536) "invalid data block row"
  pure (Block offset size digest)

-- | An expected digest authenticates exactly header+index, not unseen payload.
-- Nothing explicitly requests unauthenticated local-file inspection; data block
-- hashes and all structural checks still apply. Runtime admission requires the
-- independently authenticated manifest digest.
decodeStore :: Maybe BS.ByteString -> BS.ByteString -> Either String Store
decodeStore expected input = do
  Header indexSize count scopeCount binderCount nodeCount payloadCount total <- exact (header (BS.length input)) (BS.take 64 input)
  let authenticated = BS.take (64+indexSize) input
  case expected of
    Just digest -> unless (BS.length digest == 32 && SHA256.hash authenticated == digest)
      (Left "Core store: authenticated index hash mismatch")
    Nothing -> pure ()
  (root,parents,binders,symbols,blocks) <- exact (index count scopeCount binderCount nodeCount payloadCount)
    (BS.take indexSize (BS.drop 64 input))
  verified <- verifyBlocks (64+indexSize) total blocks input
  let (nodePages,payloadPages) = splitAt nodeCount verified
      payload = BS.concat payloadPages
  unless (all ((==65536) . BS.length) (take (max 0 (payloadCount-1)) payloadPages))
    (Left "Core store: non-final payload block is partial")
  raw <- concat <$> sequence [nodePage (min 256 (count-indexValue*256)) bytes
    | (indexValue,bytes) <- zip [0..] nodePages]
  validatePartition (BS.length payload) raw
  records <- mapM (exact (node payload)) raw
  mapM_ (checkReferences count scopeCount binderCount) (zip [0..] records)
  let result = Store root (Seq.fromList records) (Seq.fromList parents) (Seq.fromList binders) symbols
  checkIndex result
  pure result
  where
    index count scopeCount binderCount nodeCount payloadCount = do
      root <- Ref <$> u32; symbolCount <- u32
      require (unRef root < count) "root outside record table"
      -- Check minimal required bytes before replicateM creates any list.
      remaining <- Parser $ \rest -> Right (BS.length rest,rest)
      let minimumSize = toInteger scopeCount*4 + toInteger binderCount*12 +
            toInteger symbolCount*8 + (toInteger nodeCount + toInteger payloadCount)*48
      require (minimumSize == toInteger remaining) "index count/length mismatch"
      parents <- mapM (\i -> do
        parent <- u32
        require (if i == 0 then parent == 0xffffffff else parent < i) "scope parent is not earlier/module root"
        pure (if i == 0 then Nothing else Just (Scope parent))) [0..scopeCount-1]
      binders <- replicateM binderCount (BinderInfo <$> (Scope <$> u32) <*> (Ref <$> u32) <*> (Ref <$> u32))
      symbols <- replicateM symbolCount ((,) <$> (Ref <$> u32) <*> (Ref <$> u32))
      blocks <- replicateM (nodeCount+payloadCount) block
      pure (root,parents,binders,symbols,blocks)

-- Full-audit-only pass. Demand readers must not scan earlier records before
-- accessing one span. Partitioning bounds total UTF8/vector byte validation by
-- actual stored payload bytes, including adversarial files with shared IDs.
validatePartition :: Int -> [BS.ByteString] -> Either String ()
validatePartition payloadLength records = do
  final <- foldM step 0 records
  unless (final == toInteger payloadLength) (Left "Core store: unowned payload bytes")
  where
    step expected raw = do
      (spanValue,_) <- parse spanHeader raw
      case spanValue of
        Nothing -> pure expected
        Just (offset,size) -> do
          unless (toInteger offset == expected && toInteger offset + toInteger size <= toInteger payloadLength)
            (Left "Core store: payload spans do not form a record-ordered partition")
          pure (expected + toInteger size)
    spanHeader = byte >>= \case
      4 -> Just <$> ((,) <$> u64 <*> u64)
      7 -> byte >> Just <$> ((,) <$> u64 <*> u64)
      5 -> vectorSpan
      35 -> vectorSpan
      _ -> pure Nothing
    vectorSpan = do
      count <- uleb; mode <- byte
      case mode of
        0 -> pure Nothing
        1 -> do offset <- u64; pure (Just (offset,fromIntegral count*4))
        _ -> reject "unknown vector mode"

verifyBlocks :: Int -> Int -> [Block] -> BS.ByteString -> Either String [BS.ByteString]
verifyBlocks start total blocks input = go start blocks
  where
    go offset [] = unless (offset == total) (Left "Core store: trailing file bytes") >> pure []
    go offset (Block actual size digest:rest) = do
      unless (actual == offset && size <= total - offset) (Left "Core store: overlapping/out-of-range block")
      let value = BS.take size (BS.drop offset input)
      unless (SHA256.hash value == digest) (Left "Core store: data block hash mismatch")
      (value :) <$> go (offset+size) rest

nodePage :: Int -> BS.ByteString -> Either String [BS.ByteString]
nodePage expected input = do
  (offsets,payload) <- parse (do
    count <- u16
    require (count == expected) "record page count mismatch"
    replicateM (count+1) u16) input
  case offsets of
    0:rest -> do
      unless (last offsets == BS.length payload && and (zipWith (<) offsets rest))
        (Left "Core store: invalid page offsets")
      pure [BS.take (end-start) (BS.drop start payload) | (start,end) <- zip offsets rest]
    _ -> Left "Core store: record offsets do not begin at zero"

spanBytes :: BS.ByteString -> Word64 -> Word64 -> Parser BS.ByteString
spanBytes payload offset size = do
  require (toInteger offset + toInteger size <= toInteger (BS.length payload)) "payload span outside blocks"
  pure (BS.take (fromIntegral size) (BS.drop (fromIntegral offset) payload))

node :: BS.ByteString -> Parser Node
node payload = byte >>= \case
  0 -> pure Null
  1 -> pure (Boolean False)
  2 -> pure (Boolean True)
  3 -> Integer <$> sleb
  4 -> do
    value <- (,) <$> u64 <*> u64 >>= uncurry (spanBytes payload)
    case Text.decodeUtf8' value of Left _ -> reject "invalid UTF8"; Right _ -> pure (String value)
  5 -> Vector <$> vector Ref ref
  6 -> Object <$> ref <*> ref
  7 -> do
    negative <- boolean
    value <- (,) <$> u64 <*> u64 >>= uncurry (spanBytes payload)
    require (not (BS.null value) && BS.head value /= 0) "noncanonical integer magnitude"
    let magnitude = BS.foldl' (\acc item -> acc*256 + toInteger item) 0 value
        integer = if negative then negate magnitude else magnitude
    require (integer < toInteger (minBound::Int64) || integer > toInteger (maxBound::Int64)) "large integer fits small encoding"
    pure (Integer integer)
  8 -> Layout <$> ref
  9 -> do
    layout <- ref; proof <- byte; position <- uleb
    selected <- case proof of
      0 -> require (position == 0) "absent proof position is nonzero" >> pure Absent
      1 -> pure (Unevaluated position)
      2 -> pure (Evaluated position)
      _ -> reject "unknown evaluated proof"
    pure (Representation layout selected)
  16 -> do
    useScope <- scope; metadata <- ref; tag <- byte
    target <- case tag of 0 -> Global <$> ref; 1 -> Local <$> binder; _ -> reject "unknown variable target"
    pure (Variable useScope metadata target)
  17 -> Primitive <$> scope <*> ref <*> ref
  18 -> Constructor <$> scope <*> ref <*> ref <*> uleb
  19 -> Literal <$> scope <*> ref <*> ref <*> ref
  20 -> Application <$> scope <*> ref <*> ref <*> ref <*> ref <*> boolean <*> boolean
  21 -> Lambda <$> scope <*> ref <*> scope <*> ref <*> ref
  22 -> Let <$> scope <*> ref <*> boolean <*> scope <*> ref <*> ref
  23 -> Case <$> scope <*> ref <*> scope <*> binder <*> ref <*> ref
  24 -> Void <$> scope <*> ref
  25 -> Unsupported <$> scope <*> ref
  32 -> Definition <$> binder <*> ref <*> ref <*> uleb
  33 -> Declaration <$> binder <*> ref
  34 -> do
    bodyScope <- scope
    kind <- byte >>= \case 0 -> pure DefaultAlt; 1 -> pure DataAlt; 2 -> pure LiteralAlt; _ -> reject "unknown alternative kind"
    Alternative bodyScope kind <$> ref <*> ref <*> ref <*> ref
  35 -> BinderVector <$> vector Binder binder
  _ -> reject "unknown record tag"
  where
    vector constructor small = do
      count <- uleb; mode <- byte
      case mode of
        0 -> require (count <= 16) "oversized inline vector" >> replicateM count small
        1 -> do
          require (count > 16) "noncanonical spilled vector"
          offset <- u64
          values <- spanBytes payload offset (fromIntegral count*4)
          liftEither (exact (replicateM count (constructor <$> u32)) values)
        _ -> reject "unknown vector mode"

checkReferences :: Int -> Int -> Int -> (Int,Node) -> Either String ()
checkReferences _ scopes binders (index,nodeValue) = do
  unless (all (\(Ref r) -> r >= 0 && r < index) (children nodeValue)) (Left "Core store: non-backward structural reference")
  unless (all (\(Scope s) -> s >= 0 && s < scopes) (nodeScope nodeValue)) (Left "Core store: undeclared scope")
  unless (all (\(Binder b) -> b >= 0 && b < binders) logical) (Left "Core store: undeclared binder")
  where
    logical = case nodeValue of
      Variable _ _ (Local b) -> [b]; Case _ _ _ b _ _ -> [b]
      Definition b _ _ _ -> [b]; Declaration b _ -> [b]; BinderVector bs -> bs
      _ -> []

checkIndex :: Store -> Either String ()
checkIndex store = do
  mapM_ checkBinder (zip [0..] (foldr (:) [] (storeBinders store)))
  mapM_ checkSymbol (storeSymbols store)
  where
    checkBinder (index,info) = do
      unless (unScope (binderScope info) >= 0 && unScope (binderScope info) < Seq.length (storeScopes store))
        (Left "Core store: binder owner scope outside index")
      nodeAt store (binderName info) >>= \case String{} -> pure (); _ -> Left "Core store: binder name is not string"
      nodeAt store (binderDeclaration info) >>= \case
        Declaration actual _ | actual == Binder index -> pure ()
        _ -> Left "Core store: binder declaration index mismatch"
    checkSymbol (name,definition) = do
      nodeAt store definition >>= \case
        Definition identity _ _ _ -> do
          info <- binderAt store identity
          unless (binderScope info == Scope 0 && binderName info == name)
            (Left "Core store: symbol binding identity/owner mismatch")
        _ -> Left "Core store: symbol does not address a definition"
