-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GeneralizedNewtypeDeriving #-}
{-# LANGUAGE LambdaCase #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell, requires a 64-bit host for large arenas
--
-- Immutable erased-Core records. Physical references form a backwards DAG;
-- lexical scopes and binders have separate logical namespaces. Construction
-- interns exact records, including ordered children, without hashing away any
-- equality check. No executable Truffle node is represented by this arena.
module THC.CoreStore.Model
  ( Ref(..), Scope(..), Binder(..), Target(..), AltKind(..), Proof(..), Node(..)
  , BinderInfo(..), Store(..), Build, runBuild, abort, intern, getNode
  , newScope, newBinder, addSymbol, internString, internVector, internObject
  , internMetadata, children, nodeScope, binderAt, nodeAt
  , recordForeignCall, foreignCalls, objectFields, amendObject
  ) where

import Control.Monad (ap, unless)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as BB
import qualified Data.ByteString.Lazy as BL
import Data.Char (ord)
import Data.Foldable (toList)
import qualified Data.Map.Strict as Map
import qualified Data.Sequence as Seq
import Data.Word (Word32)
import THC.JSON (J(..))

newtype Ref = Ref { unRef :: Int } deriving (Eq, Ord, Show)
newtype Scope = Scope { unScope :: Int } deriving (Eq, Ord, Show)
newtype Binder = Binder { unBinder :: Int } deriving (Eq, Ord, Show)
data Target = Global !Ref | Local !Binder deriving (Eq, Ord, Show)
data AltKind = DefaultAlt | DataAlt | LiteralAlt deriving (Eq, Ord, Show)
data Proof = Absent | Unevaluated !Int | Evaluated !Int deriving (Eq, Ord, Show)

data Node
  = Null | Boolean !Bool | Integer !Integer | String !BS.ByteString
  | Vector ![Ref] | Object !Ref !Ref
  | Layout !Ref | Representation !Ref !Proof
  | Variable !Scope !Ref !Target
  | Primitive !Scope !Ref !Ref
  | Constructor !Scope !Ref !Ref !Int
  | Literal !Scope !Ref !Ref !Ref
  | Application !Scope !Ref !Ref !Ref !Ref !Bool !Bool
  | Lambda !Scope !Ref !Scope !Ref !Ref
  | Let !Scope !Ref !Bool !Scope !Ref !Ref
  | Case !Scope !Ref !Scope !Binder !Ref !Ref
  | Void !Scope !Ref | Unsupported !Scope !Ref
  | Definition !Binder !Ref !Ref !Int
  | Declaration !Binder !Ref
  | Alternative !Scope !AltKind !Ref !Ref !Ref !Ref
  | BinderVector ![Binder]
  deriving (Eq, Ord, Show)

data BinderInfo = BinderInfo
  { binderScope :: !Scope, binderName :: !Ref, binderDeclaration :: !Ref
  } deriving (Eq, Show)

data Store = Store
  { storeRoot :: !Ref
  , storeNodes :: !(Seq.Seq Node)
  , storeScopes :: !(Seq.Seq (Maybe Scope))
  , storeBinders :: !(Seq.Seq BinderInfo)
  , storeSymbols :: ![(Ref, Ref)]
  } deriving (Eq, Show)

data Construction = Construction
  { records :: !(Seq.Seq Node)
  , common :: !(Map.Map Node Ref)
  , scopes :: !(Seq.Seq (Maybe Scope))
  , binders :: !(Seq.Seq BinderInfo)
  , symbols :: !(Seq.Seq (Ref, Ref))
  , names :: !(Map.Map (Scope, Ref) Binder)
  , symbolNames :: !(Map.Map Ref Ref)
  , observedCalls :: !(Seq.Seq Ref)
  }

newtype Build a = Build { build :: Construction -> Either String (a, Construction) }
instance Functor Build where fmap f (Build g) = Build $ \s -> do (a,t) <- g s; pure (f a,t)
instance Applicative Build where pure a = Build $ \s -> Right (a,s); (<*>) = ap
instance Monad Build where Build f >>= g = Build $ \s -> do (a,t) <- f s; build (g a) t

abort :: String -> Build a
abort message = Build $ \_ -> Left message

runBuild :: Build Ref -> Either String Store
runBuild action = do
  (root, final) <- build action (Construction Seq.empty Map.empty
    (Seq.singleton Nothing) Seq.empty Seq.empty Map.empty Map.empty Seq.empty)
  unless (unRef root >= 0 && unRef root < Seq.length (records final))
    (Left "Core store root is not a constructed record")
  pure (Store root (records final) (scopes final) (binders final) (toList (symbols final)))

bounded :: String -> Int -> Either String ()
bounded what n = unless (n >= 0 && toInteger n <= toInteger (maxBound :: Word32))
  (Left ("Core store " ++ what ++ " exceeds unsigned 32-bit range"))

intern :: Node -> Build Ref
intern node = Build $ \s -> do
  let count = Seq.length (records s)
  bounded "record count" count
  unless (all (\(Ref r) -> r >= 0 && r < count) (children node))
    (Left "Core store structural reference is not backwards")
  mapM_ (\(Scope r) -> unless (r >= 0 && r < Seq.length (scopes s))
    (Left "Core store scope is undeclared")) (nodeScope node)
  mapM_ (\(Binder r) -> unless (r >= 0 && r < Seq.length (binders s))
    (Left "Core store binder is undeclared")) (logicalBinders node)
  case Map.lookup node (common s) of
    Just existing -> Right (existing,s)
    Nothing -> let ref = Ref count in Right (ref,s
      { records = records s Seq.|> node, common = Map.insert node ref (common s) })

getNode :: Ref -> Build Node
getNode (Ref index) = Build $ \s -> case Seq.lookup index (records s) of
  Nothing -> Left "Core store record does not exist"
  Just node -> Right (node,s)

newScope :: Scope -> Build Scope
newScope parent@(Scope index) = Build $ \s -> do
  let count = Seq.length (scopes s)
  bounded "scope count" count
  unless (index >= 0 && index < count) (Left "Core store scope parent is undeclared")
  Right (Scope count,s { scopes = scopes s Seq.|> Just parent })

newBinder :: Scope -> Ref -> Ref -> Build Binder
newBinder owner name metadata = do
  nameNode <- getNode name
  case nameNode of String{} -> pure (); _ -> abort "Core store binder name is not a string"
  value <- Build $ \s -> do
    let index = Seq.length (binders s)
    bounded "binder count" index
    unless (unScope owner >= 0 && unScope owner < Seq.length (scopes s))
      (Left "Core store binder scope is undeclared")
    unless (Map.notMember (owner,name) (names s)) (Left "Duplicate Core binder identity in one scope")
    let identity = Binder index
    Right (identity,s { binders = binders s Seq.|> BinderInfo owner name (Ref (-1))
                      , names = Map.insert (owner,name) identity (names s) })
  declaration <- intern (Declaration value metadata)
  Build $ \s -> Right (value,s { binders = Seq.adjust'
    (\old -> old { binderDeclaration = declaration }) (unBinder value) (binders s) })

addSymbol :: Ref -> Ref -> Build ()
addSymbol name definition = Build $ \s -> do
  unless (Map.notMember name (symbolNames s)) (Left "Duplicate Core module symbol")
  case (Seq.lookup (unRef name) (records s),Seq.lookup (unRef definition) (records s)) of
    (Just String{},Just Definition{}) -> Right ((),s
      { symbols = symbols s Seq.|> (name,definition)
      , symbolNames = Map.insert name definition (symbolNames s) })
    _ -> Left "Core module symbol requires a string and binding definition"

internString :: String -> Build Ref
internString value
  | any (\c -> ord c >= 0xd800 && ord c <= 0xdfff) value = abort "Core store string contains surrogate"
  | otherwise = intern (String (BL.toStrict (BB.toLazyByteString (foldMap BB.charUtf8 value))))

internVector :: [Ref] -> Build Ref
internVector = intern . Vector

internObject :: [(String,Ref)] -> Build Ref
internObject fields = do
  keys <- mapM (internString . fst) fields >>= internVector
  values <- internVector (map snd fields)
  intern (Object keys values)

objectFields :: Ref -> Build [(Ref,Ref)]
objectFields ref = getNode ref >>= \case
  Object keys values -> do
    keyNode <- getNode keys
    valueNode <- getNode values
    case (keyNode,valueNode) of
      (Vector ks,Vector vs) | length ks == length vs -> pure (zip ks vs)
      _ -> abort "Core object has invalid key/value vectors"
  _ -> abort "Core record is not an ordered object"

-- | Persistent outer metadata update. Child records remain immutable/shared.
-- Additions are prepended; keys in removals or additions are replaced exactly.
amendObject :: [(String,Ref)] -> [String] -> Ref -> Build Ref
amendObject additions removals original = do
  old <- objectFields original
  added <- mapM (\(key,value) -> (,) <$> internString key <*> pure value) additions
  removed <- mapM internString removals
  let fields = added ++ filter (\(key,_) -> key `notElem` removed && key `notElem` map fst added) old
  keys <- internVector (map fst fields)
  values <- internVector (map snd fields)
  intern (Object keys values)

recordForeignCall :: Ref -> Build ()
recordForeignCall value = Build $ \state -> Right ((),state { observedCalls = observedCalls state Seq.|> value })

foreignCalls :: Build [Ref]
foreignCalls = Build $ \state -> Right (toList (observedCalls state),state)

-- | Auxiliary evidence only. Executable expressions are emitted directly as
-- typed records by the GHC walker, not passed through this compatibility view.
-- Layout splitting changes no field value or ordering, including nested proofs.
internMetadata :: J -> Build Ref
internMetadata = \case
  Z -> intern Null
  B value -> intern (Boolean value)
  N value -> intern (Integer value)
  S value -> internString value
  A values -> mapM internMetadata values >>= internVector
  O fields
    | any ((== "primReps") . fst) fields && any ((== "kind") . fst) fields -> do
        let evaluated = [(i,b) | (i,("evaluated",B b)) <- zip [0..] fields]
        case evaluated of
          [(position,value)] | length (filter ((== "evaluated") . fst) fields) == 1 -> do
            values <- mapM (\(k,v) -> (,) k <$> internMetadata v)
              (filter ((/= "evaluated") . fst) fields)
            layout <- internObject values >>= intern . Layout
            intern (Representation layout (if value then Evaluated position else Unevaluated position))
          _ -> ordinary fields
    | otherwise -> ordinary fields
  where ordinary fields = mapM (\(k,v) -> (,) k <$> internMetadata v) fields >>= internObject

children :: Node -> [Ref]
children = \case
  Vector xs -> xs
  Object a b -> [a,b]
  Layout a -> [a]
  Representation a _ -> [a]
  Variable _ m (Global n) -> [m,n]
  Variable _ m Local{} -> [m]
  Primitive _ m n -> [m,n]
  Constructor _ m n _ -> [m,n]
  Literal _ m k v -> [m,k,v]
  Application _ m f a l _ _ -> [m,f,a,l]
  Lambda _ m _ bs body -> [m,bs,body]
  Let _ m _ _ bs body -> [m,bs,body]
  Case _ m _ _ scrut alts -> [m,scrut,alts]
  Void _ m -> [m]
  Unsupported _ reason -> [reason]
  Definition _ e m _ -> [e,m]
  Declaration _ m -> [m]
  Alternative _ _ disc bs body m -> [disc,bs,body,m]
  _ -> []

nodeScope :: Node -> [Scope]
nodeScope = \case
  Variable s _ _ -> [s]; Primitive s _ _ -> [s]; Constructor s _ _ _ -> [s]
  Literal s _ _ _ -> [s]; Application s _ _ _ _ _ _ -> [s]
  Lambda s _ body _ _ -> [s,body]; Let s _ _ body _ _ -> [s,body]
  Case s _ branch _ _ _ -> [s,branch]; Void s _ -> [s]
  Unsupported s _ -> [s]; Alternative body _ _ _ _ _ -> [body]
  _ -> []

logicalBinders :: Node -> [Binder]
logicalBinders = \case
  Variable _ _ (Local b) -> [b]; Case _ _ _ b _ _ -> [b]
  Definition b _ _ _ -> [b]; Declaration b _ -> [b]; BinderVector bs -> bs
  _ -> []

nodeAt :: Store -> Ref -> Either String Node
nodeAt store (Ref i) = maybe (Left "Invalid Core record ID") Right (Seq.lookup i (storeNodes store))

binderAt :: Store -> Binder -> Either String BinderInfo
binderAt store (Binder i) = maybe (Left "Invalid Core binder ID") Right (Seq.lookup i (storeBinders store))
