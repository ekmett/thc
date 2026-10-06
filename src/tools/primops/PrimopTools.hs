-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : PrimopTools
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Inventory, scalar contracts and native wired declarations share the pinned
-- compiler API. Ordinary library declarations remain owned by their interfaces.
module PrimopTools where

import Control.Monad (foldM, forM_, unless, when)
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Aeson
import Data.Aeson.Types (parseEither)
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import Data.Bits ((.&.), (.|.), finiteBitSize, shiftL, shiftR)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Char (chr, ord)
import Data.List (intercalate)
import qualified Data.Map.Strict as Map
import Data.Maybe (mapMaybe)
import Data.Scientific (base10Exponent)
import qualified Data.Text as T
import qualified Data.Text.IO as T
import Data.Word (Word32)
import GHC.Builtin.PrimOps (allThePrimOps, primOpOcc, primOpSig, primOpType, primOpWrapperId)
import GHC.Builtin.PrimOps.Ids (allThePrimOpIds)
import GHC.Builtin.Utils (knownKeyNames, lookupKnownKeyName, wiredInIds, ghcPrimIds)
import qualified GHC.Builtin.Types as Builtin
import qualified GHC.Builtin.Types.Literals as Literal
import qualified GHC.Builtin.Types.Prim as Prim
import qualified GHC.Core.ConLike as ConLike
import qualified GHC.Core.DataCon as DataCon
import qualified GHC.Core.Coercion.Axiom as Ax
import qualified GHC.Core.TyCo.Rep as Rep
import qualified GHC.Core.TyCo.Tidy as Tidy
import qualified GHC.Types.TyThing as Thing
import GHC.CoreToIface (toIfaceType, toIfaceBndr)
import qualified GHC.Iface.Type as Iface
import qualified GHC.Plugins as GHC
import GHC.Core.Type (Type, splitTyConApp_maybe)
import GHC.Core.TyCon (PrimRep(..), isPrimTyCon)
import GHC.Data.FastString (unpackFS)
import GHC.Settings.Config (cProjectVersion)
import GHC.Types.Name (Name, isExternalName, nameModule, nameOccName, nameUnique)
import GHC.Types.Name.Occurrence (occNameString, occNameSpace, fieldOcc_maybe, isDataConNameSpace, isTcClsNameSpace, isTvNameSpace, isVarNameSpace, isFieldNameSpace)
import GHC.Types.RepType (typePrimRep_maybe)
import GHC.Types.Unique (getKey, getUnique, mkUnique, unpkUnique)
import GHC.Unit.Module (moduleName, moduleNameString, moduleUnit, unitString)
import GHC.Utils.Outputable (defaultSDocContext, ppr, renderWithContext)
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing, doesFileExist, getCurrentDirectory, removeFile)
import System.Environment (getArgs, getExecutablePath, lookupEnv)
import System.Exit (die, exitSuccess)
import System.FilePath ((</>), takeDirectory, takeFileName)
import System.Process (readProcess)
import Text.Printf (printf)
import Text.Read (readMaybe)

type Row = (T.Text, Int, T.Text)
type Declarations = Map.Map T.Text Value
type Signatures = Map.Map T.Text ([T.Text], T.Text)

implementationSource, scalarPath, capabilityPath, checklistPath, knownKeyPath :: FilePath
implementationSource = "src/tools/primops/PrimopTools.hs"
scalarPath = "src/main/resources/thc/scalar-primop-signatures.json"
capabilityPath = "bin/core-capabilities.json"
checklistPath = "docs/primops.md"
knownKeyPath = "src/main/resources/thc/ghc-9.14.1-known-key-names.json"

require :: Bool -> String -> Either String ()
require True _ = Right ()
require False message = Left message

field :: FromJSON a => T.Text -> Value -> Either String a
field key = parseEither (withObject "object" (.: Key.fromText key))

optional :: FromJSON a => T.Text -> a -> Value -> Either String a
optional key fallback = parseEither (withObject "object" (\o -> o .:? Key.fromText key .!= fallback))

setFields :: Value -> [(Key.Key, Value)] -> Value
setFields (Object fields) changes = Object (foldr (uncurry KM.insert) fields changes)
setFields value _ = value

exactArity :: Value -> Int -> Bool
exactArity (Number value) expected = base10Exponent value >= 0 && value == fromIntegral expected
exactArity _ _ = False

declaredPrimitives :: Value -> Either String Declarations
declaredPrimitives cap = do
  declared <- field "primitives" cap
  foldM addFamily declared
    [("tagToEnum", "concrete-nullary-family", ["tagToEnum#"]),
     ("dataToTag", "concrete-algebraic-family-64", ["dataToTagSmall#", "dataToTagLarge#"])]
  where
    addFamily declared (key, contract, names) = do
      let gate = case cap of Object fields -> KM.lookup (Key.fromText key) fields; _ -> Nothing
      case gate of
        Nothing -> pure declared
        Just actual -> do
          require (actual == String contract) ("Unrecognized " ++ T.unpack key ++ " contract: " ++ show actual)
          foldM (\current name -> do
            require (maybe True (`exactArity` 1) (Map.lookup name current))
              ("Contradictory family primitive arity: " ++ T.unpack name)
            pure (Map.insert name (toJSON (1 :: Int)) current)) declared names

report :: [Row] -> Declarations -> Either String Value
report rows advertised = do
  inventory <- foldM insert Map.empty rows
  forM_ (Map.toList advertised) $ \(name, arity) -> do
    expected <- maybe (Left ("Advertised primitive absent from pinned GHC: " ++ T.unpack name)) pure
      (Map.lookup name inventory)
    require (exactArity arity (fst expected)) ("Primitive arity mismatch: " ++ T.unpack name)
  pure $ object
    ["schema" .= (2 :: Int), "ghc" .= ("9.14.1" :: T.Text),
     "counts" .= object ["total" .= Map.size inventory, "advertised" .= Map.size advertised,
                          "unadvertised" .= (Map.size inventory - Map.size advertised)],
     "claim" .= ("Advertised names and value arities only; not proof of runtime semantics, lowering, or tested input coverage." :: T.Text),
     "primitives" .= [object ["name" .= name, "valueArity" .= arity, "signature" .= signature,
                              "advertised" .= Map.member name advertised]
                     | (name, (arity, signature)) <- Map.toAscList inventory]]
  where
    insert inventory (name, arity, signature) = do
      require (Map.notMember name inventory) ("Duplicate GHC primop: " ++ T.unpack name)
      pure (Map.insert name (arity, signature) inventory)

signatureTable :: Value -> Either String Signatures
signatureTable value = do
  schema <- field "schema" value :: Either String Int
  ghc <- field "ghc" value :: Either String T.Text
  bits <- field "targetWordSize" value :: Either String Int
  require (schema == 1 && ghc == "9.14.1" && bits == 64) "Expected the pinned 64-bit scalar signature contract"
  entries <- field "primitives" value :: Either String (Map.Map T.Text Value)
  traverse (\entry -> (,) <$> field "arguments" entry <*> field "result" entry) entries

classify :: Value -> Value -> Value -> Either String Value
classify inventory cap scalars = do
  signatures <- signatureTable scalars
  rows <- field "primitives" inventory :: Either String [Value]
  indexed <- Map.fromList <$> traverse (\row -> (,) <$> field "name" row <*> pure row) rows
  forM_ (Map.toList signatures) $ \(name, (arguments, _)) -> do
    row <- maybe (Left ("Stale scalar signature contract: " ++ T.unpack name)) pure (Map.lookup name indexed)
    advertised <- field "advertised" row
    arity <- field "valueArity" row
    require (advertised && length arguments == arity) ("Stale scalar signature contract: " ++ T.unpack name)
  classified <- traverse (classifyRow signatures) rows
  let implemented = length [() | row <- classified, field "status" row == Right ("implemented" :: T.Text)]
  limitations <- optional "limitations" ([] :: [T.Text]) cap
  pure $ setFields inventory
    [("primitives", toJSON classified),
     ("implementationCounts", object ["implemented" .= implemented, "missing" .= (length rows - implemented)]),
     ("limitations", toJSON limitations),
     ("claim", toJSON ("Runtime implementations registered for the pinned 64-bit GHC primop inventory. Registration follows implementation and ordinary testing, not formal proof. Concrete known limitations remain documented separately; representation alone does not make an implementation partial. Primop coverage is not whole-GHC feature parity." :: T.Text))]
  where
    classifyRow signatures row = do
      name <- field "name" row
      advertised <- field "advertised" row
      families <- traverse (\(key, label) -> do
        entries <- optional key (Map.empty :: Map.Map T.Text Value) cap
        pure [label | Map.member name entries])
        [("tuplePrimitives", "Scalar tuple result"), ("managedByteArrayPrimitives", "Byte-array operation"),
         ("managedArrayPrimitives", "Boxed-array operation"), ("managedMutVarPrimitives", "Mutable-reference operation"),
         ("managedStablePtrPrimitives", "Stable-pointer operation"), ("managedStableNamePrimitives", "Stable-name operation"),
         ("managedWeakPrimitives", "Weak-pointer operation"), ("managedThreadPrimitives", "Thread operation"),
         ("managedSTMPrimitives", "STM operation"), ("managedCompactPrimitives", "Compact-region operation"),
         ("managedCompactImagePrimitives", "Compact image or heap-address operation"),
         ("managedMVarPrimitives", "MVar operation"), ("managedPinnedMemoryPrimitives", "Pointer or pinned-memory operation")]
      let has key = case cap of Object o -> KM.member key o; _ -> False
          contract
            | not advertised = "No declared lowering"
            | name == "tagToEnum#" && has "tagToEnum" = "Nullary constructor-family operation"
            | name `elem` ["dataToTagSmall#", "dataToTagLarge#"] && has "dataToTag" = "Algebraic constructor-family operation"
            | Just (arguments, result) <- Map.lookup name signatures =
                if "AddrRep" `elem` (result:arguments) then "Pointer scalar signature" else "Numeric scalar signature"
            | first:_ <- concat families = first
            | otherwise = "Specialized lowering"
      pure $ setFields row [("status", toJSON (if advertised then "implemented" else "missing" :: T.Text)),
                            ("contract", toJSON (contract :: T.Text))]

scalarRepresentations :: [T.Text]
scalarRepresentations = map (T.pack . show)
  [IntRep, WordRep, Int8Rep, Word8Rep, Int16Rep, Word16Rep, Int32Rep, Word32Rep,
   Int64Rep, Word64Rep, FloatRep, DoubleRep, AddrRep]

deriveScalars :: [(T.Text, [T.Text])] -> Declarations -> Either String Signatures
deriveScalars rows advertised = foldM insert Map.empty rows
  where
    insert signatures (name, _) | Map.notMember name advertised = pure signatures
    insert signatures (name, reps) = do
      require (Map.notMember name signatures && not (null reps) &&
        maybe False (`exactArity` (length reps - 1)) (Map.lookup name advertised) &&
        all (`elem` scalarRepresentations) reps) ("Invalid scalar signature for " ++ T.unpack name)
      pure (Map.insert name (init reps, last reps) signatures)

scalarValue :: Signatures -> Value
scalarValue signatures = object
  ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: T.Text), "targetWordSize" .= (64 :: Int),
   "signatureSource" .= ("GHC.Builtin.PrimOps.primOpSig / GHC.Types.RepType.typePrimRep_maybe" :: T.Text),
   "primitives" .= Map.map (\(arguments, result) -> object ["arguments" .= arguments, "result" .= result]) signatures]

-- Deliberately retain the existing checked-in scalar bytes (including key order).
renderScalars :: Signatures -> T.Text
renderScalars signatures = T.pack $ unlines
  ["{", "  \"schema\": 1,", "  \"ghc\": \"9.14.1\",", "  \"targetWordSize\": 64,",
   "  \"signatureSource\": \"GHC.Builtin.PrimOps.primOpSig / GHC.Types.RepType.typePrimRep_maybe\",",
   "  \"primitives\": " ++ if Map.null signatures then "{}" else "{\n" ++
     intercalate ",\n" ["    " ++ quoted name ++ ": {\n      \"arguments\": " ++ argumentsText arguments ++
       ",\n      \"result\": " ++ quoted result ++ "\n    }" | (name, (arguments, result)) <- Map.toAscList signatures] ++ "\n  }",
   "}"]
  where
    quoted = BL.unpack . encode
    argumentsText [] = "[]"
    argumentsText values = "[\n" ++ intercalate ",\n" ["        " ++ quoted value | value <- values] ++ "\n      ]"

checkDocument :: FilePath -> T.Text -> IO ()
checkDocument path expected = do
  exists <- doesFileExist path
  actual <- if exists then T.readFile path else pure ""
  unless (exists && actual == expected) $ die
    (takeFileName path ++ " is stale; run cabal run exe:thc-primops -- coverage --write-checklist")

checklist :: Value -> Value -> Either String T.Text
checklist inventory _cap = do
  counts <- field "implementationCounts" inventory :: Either String Value
  implemented <- field "implemented" counts :: Either String Int
  missing <- field "missing" counts :: Either String Int
  totals <- field "counts" inventory :: Either String Value
  total <- field "total" totals :: Either String Int
  rows <- field "primitives" inventory :: Either String [Value]
  sections <- traverse (section rows) [("implemented", "Implemented primops"), ("missing", "Missing primops")]
  let percentage = if total == 0 then 0 else 100 * fromIntegral implemented / fromIntegral total :: Double
  pure $ T.unlines $ map T.pack
    ["# Primop checklist", "",
     "<!-- Generated by thc-primops coverage; change capabilities, not this list. -->", "",
     "This inventory uses GHC 9.14.1 on the pinned 64-bit target and THC's",
     "[registered capabilities](../bin/core-capabilities.json).", "",
     printf "**Implemented: %d / %d (%.1f%%).**" implemented total percentage,
     "Missing: " ++ show missing ++ ". Arity counts logical arguments, not flattened tuple fields.", "",
     "The [primop behavior reference](primop-behavior.md) describes target semantics and",
     "remaining limits. An implemented primitive does not establish whole-program compatibility.", "",
     "For regeneration commands, see [contributing](contributing.md#generated-references).",
     "The machine-readable report in `build/primop-coverage.json` also retains GHC signatures",
     "and capability notes."] ++ concat sections
  where
    section rows (status, title) = do
      entries <- fmap concat $ traverse (\row -> do
        actual <- field "status" row
        name <- field "name" row
        arity <- field "valueArity" row :: Either String Int
        contract <- field "contract" row
        pure ["- [" <> (if status == "implemented" then "x" else " ") <> "] `" <> name <>
          "` — arity " <> T.pack (show arity) <> (if status == "implemented" then " — " <> contract else "")
          | actual == (status :: T.Text)]) rows
      pure $ ["", "## " <> title, ""] ++
        (if status == "missing" then ["<details>", "<summary>Remaining GHC primops, in name order</summary>", ""] else []) ++
        entries ++ (if status == "missing" then ["", "</details>"] else [])

validateCompiler :: String -> String -> Int -> Either String ()
validateCompiler version info hostBits = do
  require (version == "9.14.1" && cProjectVersion == "9.14.1") ("THC requires GHC 9.14.1; found " ++ version)
  fields <- maybe (Left "Malformed GHC --info") pure (readMaybe info :: Maybe [(String, String)])
  require (lookup "target word size in bits" fields == Just "64" && hostBits == 64)
    "The capability checklist describes the pinned 64-bit target only"

scalarRep :: Type -> Maybe T.Text
scalarRep ty = case splitTyConApp_maybe ty of
  Just (tc, []) | isPrimTyCon tc -> case typePrimRep_maybe ty of
    Just [rep] | T.pack (show rep) `elem` scalarRepresentations -> Just (T.pack (show rep))
    _ -> Nothing
  _ -> Nothing

queryGhc :: FilePath -> IO ([Row], [(T.Text, [T.Text])], Value)
queryGhc ghc = do
  version <- readProcess ghc ["--numeric-version"] ""
  info <- readProcess ghc ["--info"] ""
  either die pure (validateCompiler (unwords (words version)) info (finiteBitSize (0 :: Int)))
  -- Match the old GHC query's showSDocUnsafe context exactly. A session's user
  -- pretty-printing style hides inferred RuntimeRep/Levity binders in 82 rows.
  let rows = [(T.pack (occNameString (primOpOcc op)), arity,
               T.pack (unwords (words (renderWithContext defaultSDocContext (ppr (primOpType op))))))
              | op <- allThePrimOps, let (_, _, _, arity, _) = primOpSig op]
      scalars = mapMaybe (\op -> let (variables, arguments, result, _, _) = primOpSig op in
        if not (null variables) then Nothing else
          (,) (T.pack (occNameString (primOpOcc op))) <$> traverse scalarRep (arguments ++ [result])) allThePrimOps
  pure (rows, scalars, object ["compilerInfo" .= info, "compiledGhc" .= cProjectVersion,
    "compilerCommands" .= [[ghc, "--numeric-version"], [ghc, "--info"]]])

-- | The unsigned name-reference word used by GHC.Iface.Binary.putName.
-- Decoding its tag/index reconstructs the original known-key Unique.
knownKeyWord :: Name -> Either String Word32
knownKeyWord name = do
  let (tag, index) = unpkUnique (nameUnique name)
  require (ord tag < 256 && index < 2^(22 :: Int)) "Known-key Unique outside interface encoding"
  pure (0x80000000 .|. (fromIntegral (ord tag) `shiftL` 22) .|. fromIntegral index)

-- | Canonical external identity, with the pinned Binary NameSpace byte.
-- Field identity also includes the canonical constructor parent.
knownKeyIdentity :: Name -> Either String (Int, Maybe String, String, String, String)
knownKeyIdentity name = do
  require (isExternalName name) "Internal name in finite known-key catalogue"
  let occ = nameOccName name
      ns = occNameSpace occ
      modId = nameModule name
  namespace <- if isFieldNameSpace ns then Right 4
    else if isTvNameSpace ns then Right 2
    else if isDataConNameSpace ns then Right 1
    else if isTcClsNameSpace ns then Right 3
    else if isVarNameSpace ns then Right 0
    else Left "Unknown known-key namespace"
  pure (namespace, unpackFS <$> fieldOcc_maybe occ, unitString (moduleUnit modId), moduleNameString (moduleName modId), occNameString occ)

-- | Canonical names are compiler identities, including field namespaces.
metadataName :: Name -> Either String Value
metadataName name = do
  (namespace, parent, unit, modName, occurrence) <- knownKeyIdentity name
  pure (toJSON [toJSON unit, toJSON modName, toJSON namespace, toJSON parent, toJSON occurrence])

node :: T.Text -> [Value] -> Value
node tag fields = toJSON (String tag : fields)

roleValue :: GHC.Role -> Value
roleValue role = toJSON (case role of GHC.Nominal -> 1; GHC.Representational -> 2; GHC.Phantom -> 3 :: Int)

visibilityValue :: GHC.ForAllTyFlag -> Value
visibilityValue flag = toJSON (case flag of GHC.Required -> 0; GHC.Invisible GHC.SpecifiedSpec -> 1; GHC.Invisible GHC.InferredSpec -> 2 :: Int)

tupleSortValue :: GHC.TupleSort -> Value
tupleSortValue sort = toJSON (case sort of GHC.BoxedTuple -> 0; GHC.UnboxedTuple -> 1; GHC.ConstraintTuple -> 2 :: Int)

binderValue :: Iface.IfaceBndr -> Either String Value
binderValue binder = case binder of
  Iface.IfaceTvBndr (name, kind) -> make name False kind
  Iface.IfaceIdBndr (_, name, kind) -> make name True kind
  where make name coercion kind = do
          encoded <- ifaceTypeValue kind
          pure (toJSON [toJSON (unpackFS (Iface.ifLclNameFS name)), toJSON coercion, encoded])

sortValue :: Iface.IfaceTyConSort -> Value
sortValue sort = toJSON (case sort of
  Iface.IfaceNormalTyCon -> [toJSON (0 :: Int)]
  Iface.IfaceTupleTyCon arity tuple -> [toJSON (1 :: Int), toJSON arity, tupleSortValue tuple]
  Iface.IfaceSumTyCon arity -> [toJSON (2 :: Int), toJSON arity]
  Iface.IfaceEqualityTyCon -> [toJSON (3 :: Int)])

promotionValue :: GHC.PromotionFlag -> Value
promotionValue flag = toJSON (case flag of GHC.NotPromoted -> False; GHC.IsPromoted -> True)

argumentsValue :: Iface.IfaceAppArgs -> Either String Value
argumentsValue = fmap toJSON . go
  where go Iface.IA_Nil = pure []
        go (Iface.IA_Arg ty visibility rest) = do
          value <- ifaceTypeValue ty
          values <- go rest
          pure (toJSON [visibilityValue visibility, value] : values)

-- | Preserve GHC's interface type algebra. Type/kind substitution remains the
-- native reader's operation; no variable or aggregate is replaced by an erased rep.
ifaceTypeValue :: Iface.IfaceType -> Either String Value
ifaceTypeValue ty = case ty of
  Iface.IfaceTyVar name -> pure (node "var" [toJSON (unpackFS (Iface.ifLclNameFS name))])
  Iface.IfaceAppTy fun args -> node "app" <$> sequence [ifaceTypeValue fun, argumentsValue args]
  Iface.IfaceFunTy flag multiplicity argument result -> do
    let code = case flag of GHC.FTF_T_T -> 0; GHC.FTF_T_C -> 1; GHC.FTF_C_T -> 2; GHC.FTF_C_C -> 3 :: Int
    node "fun" . (toJSON code :) <$> traverse ifaceTypeValue [multiplicity, argument, result]
  Iface.IfaceForAllTy (GHC.Bndr binder visibility) body -> node "forall" <$> sequence [binderValue binder, pure (visibilityValue visibility), ifaceTypeValue body]
  Iface.IfaceTyConApp (Iface.IfaceTyCon name (Iface.IfaceTyConInfo promotion sort)) args -> node "con" <$> sequence [metadataName name, pure (promotionValue promotion), pure (sortValue sort), argumentsValue args]
  Iface.IfaceTupleTy sort promotion args -> node "tuple" <$> sequence [pure (tupleSortValue sort), pure (promotionValue promotion), argumentsValue args]
  Iface.IfaceLitTy literal -> pure (node "lit" (case literal of
    Iface.IfaceNumTyLit value -> [toJSON (1 :: Int), toJSON (show value)]
    Iface.IfaceStrTyLit value -> [toJSON (2 :: Int), toJSON (unpackFS (GHC.getLexicalFastString value))]
    Iface.IfaceCharTyLit value -> [toJSON (3 :: Int), toJSON (ord value)]))
  Iface.IfaceCastTy inner coercion -> node "cast" <$> sequence [ifaceTypeValue inner, ifaceCoercionValue coercion]
  Iface.IfaceCoercionTy coercion -> node "coercion" . (:[]) <$> ifaceCoercionValue coercion
  Iface.IfaceFreeTyVar _ -> Left "Free type variable in compiler-owned interface metadata"

ifaceCoercionValue :: Iface.IfaceCoercion -> Either String Value
ifaceCoercionValue coercion = case coercion of
  Iface.IfaceReflCo ty -> node "refl" . (:[]) <$> ifaceTypeValue ty
  Iface.IfaceGReflCo role ty maybeCo -> node "grefl" <$> sequence [pure (roleValue role), ifaceTypeValue ty, case maybeCo of Iface.IfaceMRefl -> pure Null; Iface.IfaceMCo co -> ifaceCoercionValue co]
  Iface.IfaceFunCo role mult argument result -> node "funco" . (roleValue role :) <$> traverse ifaceCoercionValue [mult, argument, result]
  Iface.IfaceTyConAppCo role (Iface.IfaceTyCon name (Iface.IfaceTyConInfo promotion sort)) coercions -> node "conco" <$> sequence [pure (roleValue role), metadataName name, pure (promotionValue promotion), pure (sortValue sort), listCo coercions]
  Iface.IfaceAppCo a b -> binary "appco" a b
  Iface.IfaceForAllCo binder visL visR kind body -> node "forallco" <$> sequence [binderValue binder, pure (visibilityValue visL), pure (visibilityValue visR), ifaceCoercionValue kind, ifaceCoercionValue body]
  Iface.IfaceCoVarCo name -> pure (node "varco" [toJSON (unpackFS (Iface.ifLclNameFS name))])
  Iface.IfaceUnivCo provenance role a b coercions -> node "univ" <$> sequence [pure (provenanceValue provenance), pure (roleValue role), ifaceTypeValue a, ifaceTypeValue b, listCo coercions]
  Iface.IfaceSymCo co -> unary "sym" co
  Iface.IfaceTransCo a b -> binary "trans" a b
  Iface.IfaceSelCo selector co -> node "sel" <$> sequence [pure (selectorValue selector), ifaceCoercionValue co]
  Iface.IfaceLRCo lr co -> node "lr" <$> sequence [pure (toJSON (case lr of GHC.CLeft -> 0; GHC.CRight -> 1 :: Int)), ifaceCoercionValue co]
  Iface.IfaceInstCo a b -> binary "inst" a b
  Iface.IfaceKindCo co -> unary "kind" co
  Iface.IfaceSubCo co -> unary "sub" co
  Iface.IfaceAxiomCo rule coercions -> node "axiom" <$> sequence [ruleValue rule, listCo coercions]
  Iface.IfaceFreeCoVar _ -> Left "Free coercion variable in compiler-owned interface metadata"
  Iface.IfaceHoleCo _ -> Left "Coercion hole in compiler-owned interface metadata"
  where unary tag co = node tag . (:[]) <$> ifaceCoercionValue co
        binary tag a b = node tag <$> traverse ifaceCoercionValue [a,b]
        listCo = fmap toJSON . traverse ifaceCoercionValue

provenanceValue :: Rep.UnivCoProvenance -> Value
provenanceValue provenance = toJSON (case provenance of
  Rep.PhantomProv -> [toJSON (1 :: Int)]
  Rep.ProofIrrelProv -> [toJSON (2 :: Int)]
  Rep.PluginProv plugin -> [toJSON (3 :: Int), toJSON plugin])

selectorValue :: Rep.CoSel -> Value
selectorValue selector = toJSON (case selector of
  Rep.SelTyCon index role -> [toJSON (0 :: Int), toJSON index, roleValue role]
  Rep.SelForAll -> [toJSON (1 :: Int)]
  Rep.SelFun Rep.SelMult -> [toJSON (2 :: Int)]
  Rep.SelFun Rep.SelArg -> [toJSON (3 :: Int)]
  Rep.SelFun Rep.SelRes -> [toJSON (4 :: Int)])

ruleValue :: Iface.IfaceAxiomRule -> Either String Value
ruleValue rule = case rule of
  Iface.IfaceAR_X name -> pure (toJSON [toJSON (0 :: Int), toJSON (unpackFS (Iface.ifLclNameFS name))])
  Iface.IfaceAR_U name -> toJSON . (toJSON (1 :: Int) :) . (:[]) <$> metadataName name
  Iface.IfaceAR_B name branch -> do
    encoded <- metadataName name
    pure (toJSON [toJSON (2 :: Int), encoded, toJSON branch])

typeValue :: Type -> Either String Value
typeValue = ifaceTypeValue . toIfaceType

-- GHC requires unique lexical names before converting its Unique-bound types
-- to IfaceType. Declaration fields share the same tidied binder environment.
closedTypeValue :: Type -> Either String Value
closedTypeValue = typeValue . Tidy.tidyTopType

idValue :: GHC.Id -> Either String Value
idValue ident = do
  name <- metadataName (GHC.idName ident)
  ty <- closedTypeValue (GHC.idType ident)
  pure (object ["name" .= name, "type" .= ty])

branchValue :: Ax.CoAxBranch -> Either String Value
branchValue branch = do
  let (env, variables) = Tidy.tidyVarBndrs GHC.emptyTidyEnv (Ax.cab_tvs branch ++ Ax.cab_cvs branch)
  binders <- traverse (binderValue . toIfaceBndr) variables
  lhs <- traverse (typeValue . Tidy.tidyType env) (Ax.cab_lhs branch)
  rhs <- typeValue (Tidy.tidyType env (Ax.cab_rhs branch))
  pure (object ["binders" .= binders, "roles" .= map roleValue (Ax.cab_roles branch), "lhs" .= lhs, "rhs" .= rhs])

-- | Worker fields are the actual Core representation fields, including their
-- multiplicities. Existential coercion arguments remain distinct from fields;
-- GHC's rep arity and strictness marks are preserved without certification.
constructorValue :: GHC.DataCon -> Either String Value
constructorValue con = do
  let universals = GHC.dataConUnivTyVars con
      (env, variables) = Tidy.tidyVarBndrs GHC.emptyTidyEnv
        (universals ++ GHC.dataConExTyCoVars con)
      (universalVars, existentialVars) = splitAt (length universals) variables
      arguments = GHC.dataConRepArgTys con
      scoped = typeValue . Tidy.tidyType env
  name <- metadataName (GHC.dataConName con)
  worker <- metadataName (GHC.idName (GHC.dataConWorkId con))
  ty <- closedTypeValue (GHC.dataConRepType con)
  universal <- traverse (binderValue . toIfaceBndr) universalVars
  existential <- traverse (binderValue . toIfaceBndr) existentialVars
  fields <- traverse (scoped . Rep.scaledThing) arguments
  multiplicities <- traverse (scoped . Rep.scaledMult) arguments
  wrapper <- traverse idValue (GHC.dataConWrapId_maybe con)
  pure (object (["name" .= name, "worker" .= worker, "type" .= ty,
    "universal" .= universal, "existential" .= existential,
    "fields" .= fields, "multiplicities" .= multiplicities,
    "tag" .= GHC.dataConTag con, "repArity" .= GHC.dataConRepArity con,
    "strictFields" .= map DataCon.isMarkedStrict (GHC.dataConRepStrictness con)] ++
    maybe [] (\value -> ["wrapper" .= value]) wrapper))

axiomValue :: Ax.CoAxiom branch -> Either String Value
axiomValue ax = do
  name <- metadataName (Ax.coAxiomName ax)
  branches <- traverse branchValue (Ax.fromBranches (Ax.coAxiomBranches ax))
  pure (object ["name" .= name, "role" .= roleValue (Ax.coAxiomRole ax), "branches" .= branches])

tyConValue :: GHC.TyCon -> Either String Value
tyConValue tc = do
  name <- metadataName (GHC.tyConName tc)
  let (env, variables) = Tidy.tidyForAllTyBinders GHC.emptyTidyEnv (GHC.tyConBinders tc)
  kind <- closedTypeValue (GHC.tyConKind tc)
  resultKind <- typeValue (Tidy.tidyType env (GHC.tyConResKind tc))
  binders <- traverse binder variables
  rhs <- traverse (typeValue . Tidy.tidyType env) (if GHC.isNewTyCon tc then Just (snd (GHC.newTyConRhs tc)) else GHC.synTyConRhs_maybe tc)
  axiom <- case GHC.unwrapNewTyCon_maybe tc of
    Just (_,_,ax) -> Just <$> axiomValue ax
    Nothing -> traverse axiomValue (GHC.isClosedSynFamilyTyConWithAxiom_maybe tc)
  constructors <- traverse constructorValue (GHC.tyConDataCons tc)
  let form | GHC.isPrimTyCon tc = "primitive"
           | GHC.isNewTyCon tc = "newtype"
           | GHC.isTypeSynonymTyCon tc = "synonym"
           | GHC.isFamilyTyCon tc = "family"
           | otherwise = "data" :: T.Text
  pure (object (["name" .= name, "binders" .= binders, "kind" .= kind, "resultKind" .= resultKind,
                 "roles" .= map roleValue (GHC.tyConRoles tc), "form" .= form,
                 "constructors" .= constructors] ++
               maybe [] (\value -> ["rhs" .= value]) rhs ++ maybe [] (\value -> ["axiom" .= value]) axiom))
  where binder (GHC.Bndr variable visibility) = do
          encoded <- binderValue (toIfaceBndr variable)
          let vis = case visibility of GHC.AnonTCB -> [toJSON (0 :: Int)]; GHC.NamedTCB flag -> [toJSON (1 :: Int), visibilityValue flag]
          pure (toJSON [encoded, toJSON vis])

-- | Complete compiler-owned declarations, not ordinary library declarations.
-- IO is a known-key name but has no wired-in TyThing: its definition belongs to
-- the real GHC.Internal.Types interface and is deliberately not synthesized here.
wiredMetadata :: Either String [(Key,Value)]
wiredMetadata = do
  let fromName name = case GHC.wiredInNameTyThing_maybe name of
        Just (Thing.ATyCon tc) -> [tc]
        Just (Thing.AConLike (ConLike.RealDataCon con)) -> [GHC.promoteDataCon con]
        _ -> []
      tcs = Prim.primTyCons ++ Builtin.wiredInTyCons ++ Literal.typeNatTyCons ++ concatMap fromName knownKeyNames
  declarations <- traverse (\tc -> (,) <$> knownKeyIdentity (GHC.tyConName tc) <*> pure tc) tcs
  tycons <- traverse tyConValue (Map.elems (Map.fromList declarations))
  primops <- traverse idValue allThePrimOpIds
  ids <- traverse idValue (wiredInIds ++ ghcPrimIds)
  pure [("tycons",toJSON tycons),("primops",toJSON primops),("wiredIds",toJSON ids)]

-- | Finite names round-trip through GHC's actual known-key lookup. Wired
-- declaration ASTs preserve types; algorithmic tuple/sum families remain
-- algorithmic, and ordinary library declarations are not synthesized.
knownKeyCatalogue :: Either String Value
knownKeyCatalogue = do
  (entries, _) <- foldM insert (Map.empty, Map.empty) knownKeyNames
  metadata <- wiredMetadata
  pure $ object
    (["schema" .= (1 :: Int), "ghc" .= cProjectVersion,
     "source" .= ("GHC.Builtin.Utils.knownKeyNames" :: T.Text),
     "scope" .= ("Finite knownKeyNames only; algorithmic GHC.Builtin.Uniques.knownUniqueName families require separate decoding." :: T.Text),
     "names" .= Map.elems entries] ++ metadata)
  where
    categories = Map.fromList $
      [(getKey (getUnique ident), "primop" :: T.Text) | ident <- allThePrimOpIds] ++
      [(getKey (getUnique (primOpWrapperId op)), "primop-wrapper") | op <- allThePrimOps]
    insert (entries, identities) name = do
      word <- knownKeyWord name
      identity@(namespace, parent, unit, modName, occurrence) <- knownKeyIdentity name
      require (Map.notMember word entries) "Duplicate serialized known-key Unique"
      require (Map.notMember identity identities) "Duplicate canonical known-key identity"
      let unique = mkUnique (chr (fromIntegral ((word .&. 0x3fc00000) `shiftR` 22)))
                           (fromIntegral (word .&. 0x003fffff))
      require (unique == nameUnique name) "Known-key Unique encoding did not round-trip"
      recovered <- maybe (Left "Known-key lookup failed") pure (lookupKnownKeyName unique)
      recoveredIdentity <- knownKeyIdentity recovered
      require (recoveredIdentity == identity) "Known-key lookup changed canonical identity"
      let entry = object $ ["nameWord" .= word, "namespace" .= namespace,
            "unit" .= unit, "module" .= modName, "occurrence" .= occurrence,
            "category" .= Map.findWithDefault "known-key" (getKey unique) categories] ++ maybe [] (\fieldParent -> ["fieldParent" .= fieldParent]) parent
      pure (Map.insert word entry entries, Map.insert identity word identities)

-- | Generate/check development metadata. The JVM consumes the checked-in
-- resource directly; it never runs this compiler API query.
runKnownKeys :: Bool -> IO ()
runKnownKeys write = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  _ <- queryGhc ghc
  catalogue <- either die pure knownKeyCatalogue
  let expected = encode catalogue <> "\n"
  if write then do
    createDirectoryIfMissing True (takeDirectory knownKeyPath)
    BL.writeFile knownKeyPath expected
  else do
    actual <- BL.readFile knownKeyPath
    unless (actual == expected) $ die "Known-key catalogue differs; run cabal run exe:thc-primops -- known-keys --write"
  names <- either die pure (field "names" catalogue :: Either String [Value])
  putStrLn ("GHC finite known-key catalogue: " ++ show (length names) ++ " names; Unique encoding and lookup agree")

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either (die . ((path ++ ": ") ++)) pure . eitherDecodeStrict'

writeJson :: FilePath -> Value -> IO ()
writeJson path value = do
  createDirectoryIfMissing True (takeDirectory path)
  BL.writeFile path (encode value <> "\n")

hashFile :: FilePath -> IO String
hashFile path = concatMap hex . BS.unpack . SHA256.hash <$> BS.readFile path
  where hex byte = let digits = showHex byte "" in replicate (2 - length digits) '0' ++ digits

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["known-keys"] -> runKnownKeys False >> exitSuccess
    ["known-keys", "--write"] -> runKnownKeys True >> exitSuccess
    _ -> pure ()
  root <- getCurrentDirectory
  let usage = "Usage (from THC root): thc-primops coverage [--check | --write-checklist] [--output PATH] | scalars [--write] | known-keys [--write]"
      parseCoverage mode output [] = Right (mode, output)
      parseCoverage Nothing output ("--check":rest) = parseCoverage (Just False) output rest
      parseCoverage Nothing output ("--write-checklist":rest) = parseCoverage (Just True) output rest
      parseCoverage mode _ ("--output":path:rest) = parseCoverage mode path rest
      parseCoverage _ _ _ = Left usage
  selection <- case args of
    "coverage":rest -> Left <$> either die pure (parseCoverage Nothing "build/primop-coverage.json" rest)
    ["scalars"] -> pure (Right False)
    ["scalars", "--write"] -> pure (Right True)
    _ -> die usage
  -- A failed/stale coverage check must not leave a prior successful report.
  case selection of
    Left (_, output) -> doesFileExist output >>= \exists -> when exists (removeFile output)
    Right _ -> pure ()
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  (rows, scalarRows, compiler) <- queryGhc ghc
  cap <- readJson capabilityPath
  executable <- getExecutablePath
  executableHash <- hashFile executable
  let provenance = do
        inputs <- traverse (\path -> (,) path <$> hashFile (root </> path))
          [implementationSource, "thc.cabal", capabilityPath, scalarPath]
        pure $ setFields compiler [("command", toJSON (executable:args)),
          ("executableSha256", toJSON executableHash), ("inputs", toJSON (Map.fromList inputs))]
  case selection of
    Left (mode, output) -> do
      declared <- either die pure (declaredPrimitives cap)
      scalarContract <- readJson scalarPath
      inventory <- either die pure (report rows declared >>= \r -> classify r cap scalarContract)
      document <- either die pure (checklist inventory cap)
      case mode of
        Just True -> T.writeFile checklistPath document
        Just False -> checkDocument checklistPath document
        Nothing -> pure ()
      proof <- provenance
      writeJson output (setFields inventory [("provenance", proof)])
      counts <- either die pure (field "implementationCounts" inventory :: Either String Value)
      implemented <- either die pure (field "implemented" counts :: Either String Int)
      putStrLn ("GHC primop inventory: " ++ show implemented ++ " implemented / " ++ show (length rows) ++
        " total; names and arities agree; " ++ BL.unpack (encode counts))
    Right write -> do
      declared <- either die pure (field "primitives" cap)
      signatures <- either die pure (deriveScalars scalarRows declared)
      let expected = renderScalars signatures
      if write then T.writeFile scalarPath expected else do
        actual <- T.readFile scalarPath
        unless (actual == expected) $ die "Scalar signature contract differs from pinned GHC; review then regenerate with --write"
      proof <- provenance
      writeJson "build/scalar-signatures/provenance.json" (setFields proof
        [("ghc", toJSON ("9.14.1" :: T.Text)), ("entries", toJSON (Map.size signatures))])
      putStrLn ("Verified " ++ show (Map.size signatures) ++ " exact monomorphic scalar signatures against GHC 9.14.1")
