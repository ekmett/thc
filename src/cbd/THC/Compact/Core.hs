-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Compact.Core
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; fixed-width integers and raw byte strings
--
-- Typed executable records. Display names and source locations do not supply
-- semantic identities or representation facts. Immutable shapes deliberately
-- exclude evaluatedness, including evaluatedness of nested aggregate fields.
module THC.Compact.Core where

import qualified Data.ByteString as BS
import Data.Int (Int64)
import Data.Word (Word32, Word64)
import THC.Compact.Types (TypeTerm)

-- | Preserve absence, an explicitly unknown value, and a known empty value.
data Presence a = Missing | Unknown | Known a deriving (Eq, Ord, Show)

data Identity = Global !BS.ByteString | Local !Word64 deriving (Eq, Ord, Show)
data EntryType = OtherEntry | IOUnit | StateRealWorld deriving (Eq, Ord, Enum, Bounded, Show)
data Kind = LongKind | FloatKind | DoubleKind | AddressKind | VoidKind | DataKind
  | ClosureKind | ObjectKind | VectorKind | UnknownKind
  deriving (Eq, Ord, Enum, Bounded, Show)
data Aggregate = TupleAggregate | SumAggregate deriving (Eq, Ord, Enum, Bounded, Show)
data Element = Int8Element | Int16Element | Int32Element | Int64Element
  | Word8Element | Word16Element | Word32Element | Word64Element
  | FloatElement | DoubleElement deriving (Eq, Ord, Enum, Bounded, Show)
data Vector = Vector !Word64 !Element deriving (Eq, Ord, Show)
data PrimRep = IntRep | WordRep | Int8Rep | Int16Rep | Int32Rep | Int64Rep
  | Word8Rep | Word16Rep | Word32Rep | Word64Rep | FloatRep | DoubleRep | AddrRep
  | BoxedUnknown | BoxedLifted | BoxedUnlifted | VecRep !Vector
  deriving (Eq, Ord, Show)

-- | Logical boundaries and physical projections are both retained. In
-- particular, a zero-width token is not an empty logical tuple.
data Shape = Shape
  { shapeKind :: !Kind
  , shapePrimReps :: !(Presence [PrimRep])
  , shapeVector :: !(Presence Vector)
  , shapeAggregate :: !(Presence Aggregate)
  , shapeComponents :: !(Presence [Shape])
  , shapeAlternatives :: !(Presence [Shape])
  , shapeTagSlot :: !(Presence Word64)
  , shapeAlternativeSlots :: !(Presence [[Word64]])
  } deriving (Eq, Ord, Show)

-- | Child states correspond to known components followed by known alternatives
-- of the shape; the codec checks that exact correspondence.
data Evaluation = Evaluation !(Presence Bool) ![Evaluation] deriving (Eq, Ord, Show)
data Rep = Rep !Shape !Evaluation deriving (Eq, Ord, Show)

-- | Nominal host-only annotations do not change shared execution shapes.
data HostCarrier = HostPlain | HostObject | HostInteropLibrary deriving (Eq, Ord, Enum, Bounded, Show)
data HostType = HostType !Rep ![HostCarrier] deriving (Eq, Show)
data HostSignature = HostSignature ![HostType] !HostType deriving (Eq, Show)

data IdInfo = IdInfo !(Presence Word64) !(Presence Bool) !(Presence [Bool])
  deriving (Eq, Show)
data Binder = Binder
  { binderOrdinal :: !Word64
  , binderEntryType :: !EntryType
  , binderLifted :: !(Presence Bool)
  , binderCoercion :: !(Presence Bool)
  , binderRep :: !(Presence Rep)
  , binderInfo :: !(Presence IdInfo)
  } deriving (Eq, Show)
data Binding = Binding
  { bindingIdentity :: !Identity
  , bindingEntryType :: !EntryType
  , bindingLifted :: !(Presence Bool)
  , bindingArity :: !Word64
  , bindingRep :: !(Presence Rep)
  , bindingInfo :: !(Presence IdInfo)
  , bindingEntryStrict :: !(Presence [Bool])
  , bindingEntryStrictSource :: !(Presence BS.ByteString)
  , bindingJoinValueArity :: !(Presence Word64)
  , bindingJoinResultRep :: !(Presence Rep)
  , bindingCallable :: !(Presence TypeTerm)
  , bindingHostSignature :: !(Presence HostSignature)
  , bindingExpr :: !Expr
  } deriving (Eq, Show)

data CallDemand = CallDemand !Word64 ![Bool] deriving (Eq, Show)
data ExceptionPayload = ExceptionPayload !Word64 !BS.ByteString deriving (Eq, Show)
data EnumFamily = EnumFamily !BS.ByteString ![BS.ByteString] deriving (Eq, Show)
data TagFamily = TagFamily !EnumFamily !Word64 !Bool deriving (Eq, Show)
data Target = StaticTarget !BS.ByteString !(Presence BS.ByteString) !Bool | DynamicTarget
  deriving (Eq, Show)
data Convention = CCall | CApi | StdCall | PrimCall | JavaScriptCall
  deriving (Eq, Ord, Enum, Bounded, Show)
data Safety = UnsafeCall | SafeCall | InterruptibleCall deriving (Eq, Ord, Enum, Bounded, Show)
data ForeignCall = ForeignCall
  { foreignSchema :: !Word64
  , foreignTarget :: !Target
  , foreignConvention :: !Convention
  , foreignSafety :: !Safety
  , foreignArity :: !Word64
  , foreignSuppliedArity :: !Word64
  , foreignArgumentReps :: ![Rep]
  , foreignResultRep :: !Rep
  , foreignIntrinsic :: !(Presence BS.ByteString)
  , foreignJavaScriptSource :: !(Presence BS.ByteString)
  , foreignArgumentTypes :: !(Presence [Presence BS.ByteString])
  } deriving (Eq, Show)

data Meta = Meta
  { metaRep :: !(Presence Rep)
  , metaResultRep :: !(Presence Rep)
  , metaEntryStrict :: !(Presence [Bool])
  , metaEntryStrictSource :: !(Presence BS.ByteString)
  , metaCallDemand :: !(Presence CallDemand)
  , metaForeignCall :: !(Presence ForeignCall)
  , metaExceptionPayload :: !(Presence ExceptionPayload)
  , metaEnumFamily :: !(Presence EnumFamily)
  , metaTagFamily :: !(Presence TagFamily)
  , metaUnsafeEqualityCase :: !(Presence BS.ByteString)
  } deriving (Eq, Show)

data Expr = Var !Meta !Identity | Prim !Meta !BS.ByteString | Lit !Meta !Literal
  | Lam !Meta ![Binder] !Expr | Con !Meta !BS.ByteString !Word64
  | App !Meta !Expr ![Expr] ![Presence Bool] !Bool !Bool
  | Let !Meta !Bool ![Binding] !Expr
  | Case !Meta !Expr !Word64 !(Presence Binder) ![Alternative]
  | Void !Meta | Unsupported !Meta !BS.ByteString deriving (Eq, Show)

data Alternative = DefaultAlt ![Binder] !Expr | DataAlt !BS.ByteString ![Binder] !Expr
  | LiteralAlt !Literal ![Binder] !Expr deriving (Eq, Show)

-- | IEEE payloads are bits, not host-floating equality (which loses NaN
-- reflexivity). String literals are arbitrary bytes, not decoded UTF-8.
data Literal = LitInt !Int64 | LitWord !Word64
  | LitInt8 !Int64 | LitInt16 !Int64 | LitInt32 !Int64 | LitInt64 !Int64
  | LitWord8 !Word64 | LitWord16 !Word64 | LitWord32 !Word64 | LitWord64 !Word64
  | LitBigNat !Integer | LitChar !Word32 | LitBytes !BS.ByteString
  | LitFloatBits !Word32 | LitDoubleBits !Word64 | LitNullAddr | LitRubbish
  | LitFunctionAddr !BS.ByteString | LitDataAddr !BS.ByteString
  | LitUnsupported !BS.ByteString
  deriving (Eq, Show)

data ConstructorKind = BoxedConstructor | TupleConstructor | SumConstructor | NewtypeConstructor
  deriving (Eq, Ord, Enum, Bounded, Show)
data Constructor = Constructor
  { constructorId :: !BS.ByteString
  , constructorArity :: !Word64
  , constructorTag :: !Word64
  , constructorKind :: !ConstructorKind
  , constructorStrictFields :: ![Bool]
  , constructorFieldLifted :: ![Presence Bool]
  , constructorFieldReps :: ![Presence [PrimRep]]
  , constructorFieldTypes :: ![Rep]
  , constructorSumArity :: !(Presence Word64)
  , constructorEnumFamily :: !(Presence EnumFamily)
  , constructorTagFamily :: !(Presence TagFamily)
  } deriving (Eq, Show)

emptyMeta :: Meta
emptyMeta = Meta Missing Missing Missing Missing Missing Missing Missing Missing Missing Missing

shapeChildren :: Shape -> [Shape]
shapeChildren shape = known (shapeComponents shape) ++ known (shapeAlternatives shape)
  where known (Known values) = values
        known _ = []
