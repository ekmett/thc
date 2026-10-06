-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
{-# OPTIONS_GHC -Wno-orphans #-}
-- The diagnostic instances live outside the executable term algebra.

-- |
-- Module      : THC.Compact.Types.JSON
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : OverloadedStrings
--
-- Explicit diagnostics and wired metadata share the existing interface AST.
-- Production binding publication passes typed terms directly to the CBD codec.
module THC.Compact.Types.JSON () where

import Data.Aeson
import Data.Aeson.Types (Parser)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import THC.Compact.Types

bytes :: Value -> Parser BS.ByteString
bytes = fmap T.encodeUtf8 . parseJSON
text :: BS.ByteString -> Value
text = toJSON . T.decodeUtf8
node :: T.Text -> [Value] -> Value
node name fields = toJSON (String name : fields)
array :: String -> ([Value] -> Parser a) -> Value -> Parser a
array label parse = withArray label (parse . toList)
invalid :: Parser a
invalid = fail "Invalid executable recovery term"
ordinal :: (Enum a, Bounded a) => Int -> Value -> Parser a
ordinal base value = do
  n <- parseJSON value
  let result = toEnum (n-base)
  if n >= base && n-base <= fromEnum (maxBound `asTypeOf` result) then pure result else invalid
instance ToJSON Visibility where toJSON = toJSON . fromEnum
instance FromJSON Visibility where parseJSON = ordinal 0
instance ToJSON Role where toJSON = toJSON . (+1) . fromEnum
instance FromJSON Role where parseJSON = ordinal 1
instance ToJSON TupleSort where toJSON = toJSON . fromEnum
instance FromJSON TupleSort where parseJSON = ordinal 0
instance ToJSON FunFlag where toJSON = toJSON . fromEnum
instance FromJSON FunFlag where parseJSON = ordinal 0
instance ToJSON TypeName where
  toJSON (TypeName unit owner namespace parent occurrence) = toJSON [text unit,text owner,toJSON namespace,maybe Null text parent,text occurrence]
instance FromJSON TypeName where
  parseJSON = array "compiler Name" $ \values -> case values of
    [unit,owner,namespace,parent,occurrence] -> do
      ns <- parseJSON namespace
      if ns > 4 then invalid else TypeName <$> bytes unit <*> bytes owner <*> pure ns
        <*> (if parent == Null then pure Nothing else Just <$> bytes parent) <*> bytes occurrence
    _ -> invalid
instance ToJSON TyConSort where
  toJSON value = toJSON (case value of
    NormalTyCon -> [toJSON (0::Int)]
    TupleTyCon arity sort -> [toJSON (1::Int),toJSON arity,toJSON sort]
    SumTyCon arity -> [toJSON (2::Int),toJSON arity]
    EqualityTyCon -> [toJSON (3::Int)])
instance FromJSON TyConSort where
  parseJSON = array "TyCon sort" $ \values -> case values of
    [Number 0] -> pure NormalTyCon
    [Number 1,arity,sort] -> TupleTyCon <$> parseJSON arity <*> parseJSON sort
    [Number 2,arity] -> SumTyCon <$> parseJSON arity
    [Number 3] -> pure EqualityTyCon
    _ -> invalid
instance ToJSON TypeBinder where toJSON (TypeBinder name co kind) = toJSON [text name,toJSON co,toJSON kind]
instance FromJSON TypeBinder where
  parseJSON = array "lexical binder" $ \values -> case values of
    [name,co,kind] -> TypeBinder <$> bytes name <*> parseJSON co <*> parseJSON kind
    _ -> invalid
instance ToJSON TypeArgument where toJSON (TypeArgument vis ty) = toJSON [toJSON vis,toJSON ty]
instance FromJSON TypeArgument where
  parseJSON = array "type argument" $ \values -> case values of
    [vis,ty] -> TypeArgument <$> parseJSON vis <*> parseJSON ty
    _ -> invalid
instance ToJSON TypeTerm where
  toJSON value = case value of
    TypeVar name -> node "var" [text name]
    TypeCon name promoted sort args -> node "con" [toJSON name,toJSON promoted,toJSON sort,toJSON args]
    TypeApp headType args -> node "app" [toJSON headType,toJSON args]
    TypeFun flag mult arg res -> node "fun" [toJSON flag,toJSON mult,toJSON arg,toJSON res]
    TypeForall binder vis body -> node "forall" [toJSON binder,toJSON vis,toJSON body]
    TypeTuple sort promoted args -> node "tuple" [toJSON sort,toJSON promoted,toJSON args]
    TypeNat n -> node "lit" [toJSON (1::Int),toJSON (show n)]
    TypeSymbol points -> node "lit" [toJSON (2::Int),toJSON points]
    TypeChar point -> node "lit" [toJSON (3::Int),toJSON point]
    TypeCast ty co -> node "cast" [toJSON ty,toJSON co]
    TypeCoercion co -> node "coercion" [toJSON co]
instance FromJSON TypeTerm where
  parseJSON = array "type term" $ \values -> case values of
    [String "var",name] -> TypeVar <$> bytes name
    [String "con",name,promoted,sort,args] -> TypeCon <$> parseJSON name <*> parseJSON promoted <*> parseJSON sort <*> parseJSON args
    [String "app",headType,args] -> TypeApp <$> parseJSON headType <*> parseJSON args
    [String "fun",flag,mult,arg,res] -> TypeFun <$> parseJSON flag <*> parseJSON mult <*> parseJSON arg <*> parseJSON res
    [String "forall",binder,vis,body] -> TypeForall <$> parseJSON binder <*> parseJSON vis <*> parseJSON body
    [String "tuple",sort,promoted,args] -> TypeTuple <$> parseJSON sort <*> parseJSON promoted <*> parseJSON args
    [String "lit",Number 1,raw] -> do
      spelling <- parseJSON raw
      case reads spelling of [(n,"")] | show n == spelling -> pure (TypeNat n); _ -> invalid
    [String "lit",Number 2,raw] -> TypeSymbol <$> parseJSON raw
    [String "lit",Number 3,raw] -> TypeChar <$> parseJSON raw
    [String "cast",ty,co] -> TypeCast <$> parseJSON ty <*> parseJSON co
    [String "coercion",co] -> TypeCoercion <$> parseJSON co
    _ -> invalid
instance ToJSON UnivProvenance where
  toJSON value = toJSON (case value of PhantomProvenance -> [toJSON (1::Int)]; ProofIrrelevance -> [toJSON (2::Int)]; PluginProvenance name -> [toJSON (3::Int),text name])
instance FromJSON UnivProvenance where
  parseJSON = array "universal provenance" $ \values -> case values of
    [Number 1] -> pure PhantomProvenance
    [Number 2] -> pure ProofIrrelevance
    [Number 3,name] -> PluginProvenance <$> bytes name
    _ -> invalid
instance ToJSON CoSelector where
  toJSON value = toJSON (case value of
    TyConSelector index role -> [toJSON (0::Int),toJSON index,toJSON role]
    ForallSelector -> [toJSON (1::Int)]; MultiplicitySelector -> [toJSON (2::Int)]
    ArgumentSelector -> [toJSON (3::Int)]; ResultSelector -> [toJSON (4::Int)])
instance FromJSON CoSelector where
  parseJSON = array "coercion selector" $ \values -> case values of
    [Number 0,index,role] -> TyConSelector <$> parseJSON index <*> parseJSON role
    [Number 1] -> pure ForallSelector; [Number 2] -> pure MultiplicitySelector
    [Number 3] -> pure ArgumentSelector; [Number 4] -> pure ResultSelector
    _ -> invalid
instance ToJSON AxiomRule where
  toJSON value = toJSON (case value of
    BuiltinRule name -> [toJSON (0::Int),text name]
    UnbranchedRule name -> [toJSON (1::Int),toJSON name]
    BranchedRule name branch -> [toJSON (2::Int),toJSON name,toJSON branch])
instance FromJSON AxiomRule where
  parseJSON = array "axiom rule" $ \values -> case values of
    [Number 0,name] -> BuiltinRule <$> bytes name
    [Number 1,name] -> UnbranchedRule <$> parseJSON name
    [Number 2,name,branch] -> BranchedRule <$> parseJSON name <*> parseJSON branch
    _ -> invalid
instance ToJSON CoTerm where
  toJSON value = case value of
    CoRefl ty -> node "refl" [toJSON ty]
    CoGRefl role ty co -> node "grefl" [toJSON role,toJSON ty,toJSON co]
    CoFun role mult arg res -> node "funco" [toJSON role,toJSON mult,toJSON arg,toJSON res]
    CoCon role name promoted sort args -> node "conco" [toJSON role,toJSON name,toJSON promoted,toJSON sort,toJSON args]
    CoApp a b -> binary "appco" a b
    CoForall binder vl vr kind body -> node "forallco" [toJSON binder,toJSON vl,toJSON vr,toJSON kind,toJSON body]
    CoVar name -> node "varco" [text name]
    CoUniv provenance role a b args -> node "univ" [toJSON provenance,toJSON role,toJSON a,toJSON b,toJSON args]
    CoSym co -> unary "sym" co; CoTrans a b -> binary "trans" a b
    CoSelect selector co -> node "sel" [toJSON selector,toJSON co]
    CoLeft co -> node "lr" [toJSON (0::Int),toJSON co]; CoRight co -> node "lr" [toJSON (1::Int),toJSON co]
    CoInst a b -> binary "inst" a b
    CoKind co -> unary "kind" co; CoSub co -> unary "sub" co
    CoAxiom rule args -> node "axiom" [toJSON rule,toJSON args]
    where unary tag co = node tag [toJSON co]
          binary tag a b = node tag [toJSON a,toJSON b]
instance FromJSON CoTerm where
  parseJSON = array "coercion term" $ \values -> case values of
    [String "refl",ty] -> CoRefl <$> parseJSON ty
    [String "grefl",role,ty,co] -> CoGRefl <$> parseJSON role <*> parseJSON ty <*> parseJSON co
    [String "funco",role,mult,arg,res] -> CoFun <$> parseJSON role <*> parseJSON mult <*> parseJSON arg <*> parseJSON res
    [String "conco",role,name,promoted,sort,args] -> CoCon <$> parseJSON role <*> parseJSON name <*> parseJSON promoted <*> parseJSON sort <*> parseJSON args
    [String "appco",a,b] -> CoApp <$> parseJSON a <*> parseJSON b
    [String "forallco",binder,vl,vr,kind,body] -> CoForall <$> parseJSON binder <*> parseJSON vl <*> parseJSON vr <*> parseJSON kind <*> parseJSON body
    [String "varco",name] -> CoVar <$> bytes name
    [String "univ",provenance,role,a,b,args] -> CoUniv <$> parseJSON provenance <*> parseJSON role <*> parseJSON a <*> parseJSON b <*> parseJSON args
    [String "sym",co] -> CoSym <$> parseJSON co
    [String "trans",a,b] -> CoTrans <$> parseJSON a <*> parseJSON b
    [String "sel",selector,co] -> CoSelect <$> parseJSON selector <*> parseJSON co
    [String "lr",Number 0,co] -> CoLeft <$> parseJSON co
    [String "lr",Number 1,co] -> CoRight <$> parseJSON co
    [String "inst",a,b] -> CoInst <$> parseJSON a <*> parseJSON b
    [String "kind",co] -> CoKind <$> parseJSON co
    [String "sub",co] -> CoSub <$> parseJSON co
    [String "axiom",rule,args] -> CoAxiom <$> parseJSON rule <*> parseJSON args
    _ -> invalid
instance ToJSON TypeParameter where
  toJSON (TypeParameter binder visibility) = toJSON [toJSON binder,toJSON (case visibility of AnonymousParameter -> [toJSON (0::Int)]; NamedParameter flag -> [toJSON (1::Int),toJSON flag])]
instance FromJSON TypeParameter where
  parseJSON = array "type parameter" $ \values -> case values of
    [binder,flags] -> TypeParameter <$> parseJSON binder <*> array "parameter visibility" (\vis -> case vis of [Number 0] -> pure AnonymousParameter; [Number 1,flag] -> NamedParameter <$> parseJSON flag; _ -> invalid) flags
    _ -> invalid
instance ToJSON NominalForm where
  toJSON value = toJSON (case value of PrimitiveForm -> "primitive"; DataForm -> "data"; NewtypeForm -> "newtype"; SynonymForm -> "synonym"; FamilyForm -> "family"; ClassForm -> "class"; UnaryClassForm -> "unary-class"; AbstractClassForm -> "abstract-class" :: T.Text)
instance FromJSON NominalForm where
  parseJSON = withText "nominal form" $ \value -> case value of
    "primitive" -> pure PrimitiveForm; "data" -> pure DataForm; "newtype" -> pure NewtypeForm
    "synonym" -> pure SynonymForm; "family" -> pure FamilyForm; "class" -> pure ClassForm
    "unary-class" -> pure UnaryClassForm; "abstract-class" -> pure AbstractClassForm; _ -> invalid
instance ToJSON NominalFact where
  toJSON (NominalFact name binders result roles form rhs axiom selectors) = object ["name" .= name,"binders" .= binders,"resultKind" .= result,"roles" .= roles,"form" .= form,"rhs" .= rhs,"axiom" .= axiom,"selectors" .= selectors]
instance FromJSON NominalFact where
  parseJSON = withObject "nominal fact" $ \v -> NominalFact <$> v .: "name" <*> v .: "binders" <*> v .: "resultKind" <*> v .: "roles" <*> v .: "form" <*> v .: "rhs" <*> v .: "axiom" <*> v .: "selectors"
instance ToJSON ScaledType where toJSON (ScaledType mult ty) = toJSON [toJSON mult,toJSON ty]
instance FromJSON ScaledType where
  parseJSON = array "scaled field" $ \values -> case values of [mult,ty] -> ScaledType <$> parseJSON mult <*> parseJSON ty; _ -> invalid
instance ToJSON ConstructorFact where
  toJSON (ConstructorFact name parent worker univ ex fields workerType) = object ["name" .= name,"parent" .= parent,"worker" .= worker,"universal" .= univ,"existential" .= ex,"fields" .= fields,"type" .= workerType]
instance FromJSON ConstructorFact where
  parseJSON = withObject "constructor fact" $ \v -> ConstructorFact <$> v .: "name" <*> v .: "parent" <*> v .: "worker" <*> v .: "universal" <*> v .: "existential" <*> v .: "fields" <*> v .: "type"
instance ToJSON AxiomBranch where
  toJSON (AxiomBranch binders roles lhs rhs) = object ["binders" .= binders,"roles" .= roles,"lhs" .= lhs,"rhs" .= rhs]
instance FromJSON AxiomBranch where
  parseJSON = withObject "axiom branch" $ \v -> AxiomBranch <$> v .: "binders" <*> v .: "roles" <*> v .: "lhs" <*> v .: "rhs"
instance ToJSON AxiomFact where
  toJSON (AxiomFact name tycon role branches) = object ["name" .= name,"tycon" .= tycon,"role" .= role,"branches" .= branches]
instance FromJSON AxiomFact where
  parseJSON = withObject "axiom fact" $ \v -> AxiomFact <$> v .: "name" <*> v .: "tycon" <*> v .: "role" <*> v .: "branches"
instance ToJSON RecoveryFacts where
  toJSON (RecoveryFacts nominal constructors axioms) = object ["nominal" .= nominal,"constructors" .= constructors,"axioms" .= axioms]
instance FromJSON RecoveryFacts where
  parseJSON = withObject "recovery facts" $ \v -> RecoveryFacts <$> v .: "nominal" <*> v .: "constructors" <*> v .: "axioms"
