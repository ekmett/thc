-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Compact.Decode
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bounded binary slices
--
-- Native selected-record decoder for round-trip controls and flat inspection.
-- Earlier shape definitions are addressed directly; no preceding Core tree is
-- decoded to find a selected binding. Runtime mmap ownership is independent.
module THC.Compact.Decode (decodeBindingAt, decodeExprAt, decodeRepAt) where

import Control.Monad (replicateM, unless)
import Data.Binary.Get hiding (Decoder)
import Data.Bits (shiftL, (.|.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Int (Int64)
import qualified Data.Set as Set
import qualified Data.Text.Encoding as Text
import Data.Word (Word64)
import THC.Compact.Core
import THC.Compact.Wire

data Decoder = Decoder
  { sourceData :: !BS.ByteString
  , sourceStrings :: !BS.ByteString
  , recordBase :: !Word64
  , activeShapes :: !(Set.Set Word64)
  }

-- | Return a selected typed binding and its end-exclusive relative byte offset.
decodeBindingAt :: BS.ByteString -> BS.ByteString -> Word64 -> Either String (Binding, Word64)
decodeBindingAt bytes strings offset = runAt bytes offset (binding (Decoder bytes strings offset Set.empty))

decodeExprAt :: BS.ByteString -> BS.ByteString -> Word64 -> Either String (Expr, Word64)
decodeExprAt bytes strings offset = runAt bytes offset (expression (Decoder bytes strings offset Set.empty))

decodeRepAt :: BS.ByteString -> BS.ByteString -> Word64 -> Either String (Rep, Word64)
decodeRepAt bytes strings offset = runAt bytes offset (representation (Decoder bytes strings offset Set.empty))

runAt :: BS.ByteString -> Word64 -> Get a -> Either String (a, Word64)
runAt bytes offset parser = do
  checkedSpan (fromIntegral (BS.length bytes)) (Span offset 0)
  case runGetOrFail parser (BL.fromStrict (BS.drop (fromIntegral offset) bytes)) of
    Left (_,_,problem) -> Left problem
    Right (_,consumed,value) -> Right (value, offset+fromIntegral consumed)

boolean :: Get Bool
boolean = getWord8 >>= \value -> case value of
  0 -> pure False
  1 -> pure True
  _ -> fail "Invalid compact Boolean"

enumeration :: (Enum a, Bounded a) => Get a
enumeration = do
  ordinal <- fromIntegral <$> getWord8
  let value = toEnum ordinal
  unless (ordinal <= fromEnum (maxBound `asTypeOf` value)) (fail "Unknown compact enum tag")
  pure value

present :: Get a -> Get (Presence a)
present parser = getWord8 >>= \kind -> case kind of
  0 -> pure Missing
  1 -> pure Unknown
  2 -> Known <$> parser
  _ -> fail "Invalid compact optional-field discriminator"

count :: Decoder -> Get Int
count decoder = do
  value <- getUVar
  unless (value <= fromIntegral (BS.length (sourceData decoder))) (fail "Compact count exceeds selected data extent")
  pure (fromIntegral value)

list :: Decoder -> Get a -> Get [a]
list decoder parser = count decoder >>= flip replicateM parser

string :: Decoder -> Get BS.ByteString
string decoder = do
  span'@(Span start size) <- getSpan
  let bytes = sourceStrings decoder
  either fail pure (checkedSpan (fromIntegral (BS.length bytes)) span')
  let selected = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
  either (fail . show) (const (pure selected)) (Text.decodeUtf8' selected)

identity :: Decoder -> Get Identity
identity decoder = getWord8 >>= \kind -> case kind of
  0 -> Global <$> string decoder
  1 -> Local <$> getUVar
  _ -> fail "Unknown compact identity tag"

binding :: Decoder -> Get Binding
binding decoder = Binding <$> identity decoder <*> enumeration <*> present boolean <*> getUVar
  <*> present (representation decoder) <*> present (idInfo decoder)
  <*> present (list decoder boolean) <*> present (string decoder) <*> present getUVar
  <*> present (representation decoder) <*> expression decoder

binder :: Decoder -> Get Binder
binder decoder = Binder <$> getUVar <*> enumeration <*> present boolean <*> present boolean
  <*> present (representation decoder) <*> present (idInfo decoder)

idInfo :: Decoder -> Get IdInfo
idInfo decoder = IdInfo <$> present getUVar <*> present boolean <*> present (list decoder boolean)

expression :: Decoder -> Get Expr
expression decoder = do
  kind <- getWord8
  unless (kind <= 8) (fail "Unknown compact expression tag")
  metadata <- meta decoder
  case kind of
    0 -> Var metadata <$> identity decoder
    1 -> Prim metadata <$> string decoder
    2 -> Lit metadata <$> literal decoder
    3 -> Lam metadata <$> list decoder (binder decoder) <*> child
    4 -> Con metadata <$> string decoder <*> getUVar
    5 -> App metadata <$> child <*> list decoder child <*> list decoder liftedElement <*> boolean <*> boolean
    6 -> Let metadata <$> boolean <*> list decoder (binding decoder) <*> child
    7 -> Case metadata <$> child <*> getUVar <*> present (binder decoder) <*> list decoder (alternative decoder)
    _ -> pure (Void metadata)
  where
    child = expression decoder
    liftedElement = do
      value <- present boolean
      unless (value /= Missing) (fail "Absent compact argument-lifted array element")
      pure value

meta :: Decoder -> Get Meta
meta decoder = Meta <$> present (representation decoder) <*> present (representation decoder)
  <*> present (list decoder boolean) <*> present (string decoder) <*> present (callDemand decoder)
  <*> present (foreignCall decoder) <*> present (exceptionPayload decoder)
  <*> present (enumFamily decoder) <*> present (tagFamily decoder) <*> present (string decoder)

callDemand :: Decoder -> Get CallDemand
callDemand decoder = CallDemand <$> getUVar <*> list decoder boolean

exceptionPayload :: Decoder -> Get ExceptionPayload
exceptionPayload decoder = ExceptionPayload <$> getUVar <*> string decoder

enumFamily :: Decoder -> Get EnumFamily
enumFamily decoder = EnumFamily <$> string decoder <*> list decoder (string decoder)

tagFamily :: Decoder -> Get TagFamily
tagFamily decoder = TagFamily <$> enumFamily decoder <*> getUVar <*> boolean

foreignCall :: Decoder -> Get ForeignCall
foreignCall decoder = ForeignCall <$> getUVar <*> target <*> enumeration <*> enumeration
  <*> getUVar <*> getUVar <*> list decoder (representation decoder) <*> representation decoder
  <*> present (string decoder) <*> present (string decoder)
  where
    target = getWord8 >>= \kind -> case kind of
      0 -> StaticTarget <$> string decoder <*> present (string decoder) <*> boolean
      1 -> pure DynamicTarget
      _ -> fail "Unknown compact foreign-target tag"

alternative :: Decoder -> Get Alternative
alternative decoder = getWord8 >>= \kind -> case kind of
  0 -> DefaultAlt <$> parameters <*> body
  1 -> DataAlt <$> string decoder <*> parameters <*> body
  2 -> LiteralAlt <$> literal decoder <*> parameters <*> body
  _ -> fail "Unknown compact alternative tag"
  where parameters = list decoder (binder decoder)
        body = expression decoder

representation :: Decoder -> Get Rep
representation decoder = do
  layout <- shapeUse decoder
  Rep layout <$> evaluation layout

shapeUse :: Decoder -> Get Shape
shapeUse decoder = do
  consumed <- bytesRead
  let position = recordBase decoder + fromIntegral consumed
  kind <- getWord8
  case kind of
    0 -> shapeDefinition decoder { activeShapes = Set.insert position (activeShapes decoder) }
    1 -> do
      offset <- getUVar
      unless (offset < position && not (Set.member offset (activeShapes decoder)))
        (fail "Compact shape reference is forward or cyclic")
      let referenced = decoder { recordBase = offset, activeShapes = Set.insert offset (activeShapes decoder) }
          parseDefinition = do
            marker <- getWord8
            unless (marker == 0) (fail "Compact shape reference does not identify a definition")
            shapeDefinition referenced
      either fail (pure . fst) (runAt (sourceData decoder) offset parseDefinition)
    _ -> fail "Unknown compact shape-use tag"

shapeDefinition :: Decoder -> Get Shape
shapeDefinition decoder = Shape <$> enumeration <*> present (list decoder primRep)
  <*> present vector <*> present enumeration
  <*> present (list decoder (shapeUse decoder)) <*> present (list decoder (shapeUse decoder))
  <*> present getUVar <*> present (list decoder (list decoder getUVar))

evaluation :: Shape -> Get Evaluation
evaluation layout = Evaluation <$> present boolean <*> mapM evaluation (shapeChildren layout)

vector :: Get Vector
vector = Vector <$> getUVar <*> enumeration

primRep :: Get PrimRep
primRep = do
  kind <- getWord8
  case kind of
    0 -> pure IntRep; 1 -> pure WordRep
    2 -> pure Int8Rep; 3 -> pure Int16Rep; 4 -> pure Int32Rep; 5 -> pure Int64Rep
    6 -> pure Word8Rep; 7 -> pure Word16Rep; 8 -> pure Word32Rep; 9 -> pure Word64Rep
    10 -> pure FloatRep; 11 -> pure DoubleRep; 12 -> pure AddrRep
    13 -> pure BoxedUnknown; 14 -> pure BoxedLifted; 15 -> pure BoxedUnlifted
    16 -> VecRep <$> vector
    _ -> fail "Unknown compact PrimRep tag"

literal :: Decoder -> Get Literal
literal decoder = do
  kind <- getWord8
  case kind of
    0 -> LitInt <$> getSVar; 1 -> LitWord <$> getUVar
    2 -> LitInt8 <$> signed (-128) 127
    3 -> LitInt16 <$> signed (-32768) 32767
    4 -> LitInt32 <$> signed (-2147483648) 2147483647
    5 -> LitInt64 <$> getSVar
    6 -> LitWord8 <$> unsigned 255; 7 -> LitWord16 <$> unsigned 65535
    8 -> LitWord32 <$> unsigned 4294967295; 9 -> LitWord64 <$> getUVar
    10 -> do
      bytes <- raw
      unless (BS.null bytes || BS.last bytes /= 0) (fail "Noncanonical compact BigNat magnitude")
      pure (LitBigNat (BS.foldr (\byte rest -> fromIntegral byte .|. (rest `shiftL` 8)) 0 bytes))
    11 -> do
      value <- getUVar
      unless (value <= 0x10ffff) (fail "Char literal exceeds Unicode code-point range")
      pure (LitChar (fromIntegral value))
    12 -> LitBytes <$> raw
    13 -> LitFloatBits <$> getWord32le
    14 -> LitDoubleBits <$> getWord64le
    15 -> pure LitNullAddr
    16 -> LitRubbish <$> primRep
    17 -> LitFunctionAddr <$> string decoder
    18 -> LitDataAddr <$> string decoder
    _ -> fail "Unknown compact literal tag"
  where
    raw = count decoder >>= getByteString
    signed :: Int64 -> Int64 -> Get Int64
    signed lo hi = do
      value <- getSVar
      unless (lo <= value && value <= hi) (fail "Signed literal exceeds its declared width")
      pure value
    unsigned hi = do
      value <- getUVar
      unless (value <= hi) (fail "Unsigned literal exceeds its declared width")
      pure value
