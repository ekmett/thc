-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Compact.Types
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010
--
-- Executable recovery terms. These retain lexical pi structure and the nominal
-- views consumed by kind, representation and coercion endpoint recovery, not
-- source declarations, optimizer metadata or evaluatedness. A binder scopes
-- subsequent binders and the body, but not its own kind.
module THC.Compact.Types where

import qualified Data.ByteString as BS
import Data.Word (Word8, Word32, Word64)

-- | Actual compiler identity: unit, module, namespace, field parent, occurrence.
data TypeName = TypeName !BS.ByteString !BS.ByteString !Word8 !(Maybe BS.ByteString) !BS.ByteString
  deriving (Eq, Ord, Show)

data Visibility = Required | Specified | Inferred deriving (Eq, Ord, Enum, Bounded, Show)
data Role = Nominal | Representational | Phantom deriving (Eq, Ord, Enum, Bounded, Show)
data TupleSort = BoxedTuple | UnboxedTuple | ConstraintTuple deriving (Eq, Ord, Enum, Bounded, Show)
data FunFlag = TypeToType | TypeToConstraint | ConstraintToType | ConstraintToConstraint
  deriving (Eq, Ord, Enum, Bounded, Show)
data TyConSort = NormalTyCon | TupleTyCon !Word64 !TupleSort | SumTyCon !Word64 | EqualityTyCon
  deriving (Eq, Ord, Show)

data TypeBinder = TypeBinder !BS.ByteString !Bool !TypeTerm deriving (Eq, Show)
data TypeArgument = TypeArgument !Visibility !TypeTerm deriving (Eq, Show)
data TypeTerm = TypeVar !BS.ByteString
  | TypeCon !TypeName !Bool !TyConSort ![TypeArgument]
  | TypeApp !TypeTerm ![TypeArgument]
  | TypeFun !FunFlag !TypeTerm !TypeTerm !TypeTerm
  | TypeForall !TypeBinder !Visibility !TypeTerm
  | TypeTuple !TupleSort !Bool ![TypeArgument]
  | TypeNat !Integer | TypeSymbol ![Word32] | TypeChar !Word32
  | TypeCast !TypeTerm !CoTerm | TypeCoercion !CoTerm
  deriving (Eq, Show)

data UnivProvenance = PhantomProvenance | ProofIrrelevance | PluginProvenance !BS.ByteString
  deriving (Eq, Show)
data CoSelector = TyConSelector !Word64 !Role | ForallSelector | MultiplicitySelector
  | ArgumentSelector | ResultSelector deriving (Eq, Show)
data AxiomRule = BuiltinRule !BS.ByteString | UnbranchedRule !TypeName | BranchedRule !TypeName !Word64
  deriving (Eq, Show)
data CoTerm = CoRefl !TypeTerm | CoGRefl !Role !TypeTerm !(Maybe CoTerm)
  | CoFun !Role !CoTerm !CoTerm !CoTerm
  | CoCon !Role !TypeName !Bool !TyConSort ![CoTerm]
  | CoApp !CoTerm !CoTerm
  | CoForall !TypeBinder !Visibility !Visibility !CoTerm !CoTerm
  | CoVar !BS.ByteString
  | CoUniv !UnivProvenance !Role !TypeTerm !TypeTerm ![CoTerm]
  | CoSym !CoTerm | CoTrans !CoTerm !CoTerm | CoSelect !CoSelector !CoTerm
  | CoLeft !CoTerm | CoRight !CoTerm | CoInst !CoTerm !CoTerm
  | CoKind !CoTerm | CoSub !CoTerm | CoAxiom !AxiomRule ![CoTerm]
  deriving (Eq, Show)

-- | Named pi binders and anonymous arrows remain distinct in quantified kinds.
data ParameterVisibility = AnonymousParameter | NamedParameter !Visibility deriving (Eq, Show)
data TypeParameter = TypeParameter !TypeBinder !ParameterVisibility deriving (Eq, Show)
data NominalForm = PrimitiveForm | DataForm | NewtypeForm | SynonymForm | FamilyForm
  | ClassForm | UnaryClassForm | AbstractClassForm
  deriving (Eq, Ord, Enum, Bounded, Show)
data NominalFact = NominalFact !TypeName ![TypeParameter] !TypeTerm ![Role]
  !NominalForm !(Maybe TypeTerm) !(Maybe TypeName) ![TypeName] deriving (Eq, Show)
data AxiomBranch = AxiomBranch ![TypeBinder] ![Role] ![TypeTerm] !TypeTerm deriving (Eq, Show)
data AxiomFact = AxiomFact !TypeName !TypeName !Role ![AxiomBranch] deriving (Eq, Show)
data ScaledType = ScaledType !TypeTerm !TypeTerm deriving (Eq, Show)

-- | One scope binds universals, then existential TyCo variables, then fields.
-- This telescope is independent of the worker's callable user-binder order.
-- Tag, strictness and fixed storage remain in the ordinary Constructor record.
data ConstructorFact = ConstructorFact !TypeName !TypeName !TypeName
  ![TypeBinder] ![TypeBinder] ![ScaledType] !TypeTerm
  deriving (Eq, Show)
data RecoveryFacts = RecoveryFacts ![NominalFact] ![ConstructorFact] ![AxiomFact]
  deriving (Eq, Show)
