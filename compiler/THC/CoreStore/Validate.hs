-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE LambdaCase #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell, dense unsigned32 format identities
--
-- Structural and lexical validation, separate from operation/ABI admission.
-- Records and vector role summaries are computed once by dense ID; dominance
-- uses one verified scope-forest traversal, not copied ancestor dictionaries.
module THC.CoreStore.Validate
  ( ValidatedStore, validateStore, validatedRoot, validatedNodeCount
  , validatedBinderCount, validatedScopeCount
  , validatedNode, validatedBinder, validatedScopeParent, validatedSymbols
  ) where

import Control.Monad (forM_, unless)
import Control.Monad.ST (ST)
import Data.Array (Array, (!), listArray, bounds, inRange)
import Data.Array.ST (STUArray, newArray, readArray, writeArray, runSTArray)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import qualified Data.IntMap.Strict as IM
import Data.STRef (newSTRef, readSTRef, modifySTRef')
import qualified Data.Text.Encoding as Text
import THC.CoreStore.Model

data ValidatedStore = ValidatedStore !Ref !(Array Int Node) !(Array Int BinderInfo)
  !(Array Int (Maybe Scope)) ![(Ref,Ref)]

validatedRoot :: ValidatedStore -> Ref
validatedRoot (ValidatedStore root _ _ _ _) = root
validatedNodeCount :: ValidatedStore -> Int
validatedNodeCount (ValidatedStore _ records _ _ _) = snd (bounds records)+1
validatedBinderCount :: ValidatedStore -> Int
validatedBinderCount (ValidatedStore _ _ binders _ _) = snd (bounds binders)+1
validatedScopeCount :: ValidatedStore -> Int
validatedScopeCount (ValidatedStore _ _ _ scopes _) = snd (bounds scopes)+1
validatedNode :: ValidatedStore -> Ref -> Either String Node
validatedNode (ValidatedStore _ records _ _ _) (Ref index)
  | inRange (bounds records) index = Right (records ! index)
  | otherwise = Left "Core record ID outside validated store"
validatedBinder :: ValidatedStore -> Binder -> Either String BinderInfo
validatedBinder (ValidatedStore _ _ binders _ _) (Binder index)
  | inRange (bounds binders) index = Right (binders ! index)
  | otherwise = Left "Core binder ID outside validated store"
validatedScopeParent :: ValidatedStore -> Scope -> Either String (Maybe Scope)
validatedScopeParent (ValidatedStore _ _ _ scopes _) (Scope index)
  | inRange (bounds scopes) index = Right (scopes ! index)
  | otherwise = Left "Core scope ID outside validated store"
validatedSymbols :: ValidatedStore -> [(Ref,Ref)]
validatedSymbols (ValidatedStore _ _ _ _ symbols) = symbols

data Uniform a = Empty | Uniform !a | Mixed deriving (Eq,Show)
uniform :: Eq a => [Maybe a] -> Uniform a
uniform = foldl' step Empty
  where
    step _ Nothing = Mixed
    step Empty (Just value) = Uniform value
    step (Uniform old) (Just value) | old == value = Uniform old
    step _ _ = Mixed
matches :: Eq a => a -> Uniform a -> Bool
matches _ Empty = True
matches expected (Uniform actual) = expected == actual
matches _ Mixed = False

data Facts = Facts
  { vectorValues :: !(Maybe (Array Int Ref))
  , expressionScopes :: !(Uniform Scope)
  , alternativeParents :: !(Uniform Scope)
  , definitionScopes :: !(Uniform (Scope,Scope))
  , binderOwners :: !(Uniform Scope)
  , metadataPositions :: !(IM.IntMap Int)
  , booleanElements :: !Bool
  }

-- Compressed byte trie: long strings occupy one edge slice, not a node per
-- byte. Edge fanout is at most256 and matching consumes each input byte once.
-- Canonical string identity therefore does not rely on expected hash behavior.
data Names = Names !Int !(IM.IntMap (BS.ByteString,Names))
canonicalName :: Int -> BS.ByteString -> Names -> (Int,Names)
canonicalName fresh value (Names terminal edges)
  | BS.null value = if terminal < 0 then (fresh,Names fresh edges) else (terminal,Names terminal edges)
  | otherwise =
      let first = fromIntegral (BS.head value)
      in case IM.lookup first edges of
        Nothing -> (fresh,Names terminal (IM.insert first (value,Names fresh IM.empty) edges))
        Just (prefix,child) ->
          let count = commonPrefixLength prefix value
              shared = BS.take count prefix
              oldRest = BS.drop count prefix
              newRest = BS.drop count value
          in if count == 0 then error "Core name trie lost its first-byte invariant"
            else if BS.null oldRest then
                let (identity,next) = canonicalName fresh newRest child
                in (identity,Names terminal (IM.insert first (prefix,next) edges))
            else
                let oldEdge = IM.singleton (fromIntegral (BS.head oldRest)) (oldRest,child)
                    middle = if BS.null newRest then Names fresh oldEdge else
                      Names (-1) (IM.insert (fromIntegral (BS.head newRest)) (newRest,Names fresh IM.empty) oldEdge)
                in (fresh,Names terminal (IM.insert first (shared,middle) edges))

commonPrefixLength :: BS.ByteString -> BS.ByteString -> Int
commonPrefixLength left right = go 0
  where
    limit = min (BS.length left) (BS.length right)
    go index | index < limit && BS.index left index == BS.index right index = go (index+1)
             | otherwise = index

data RefTrie = RefTrie !Int !(IM.IntMap RefTrie)
canonicalRefs :: Int -> [Ref] -> RefTrie -> (Int,RefTrie)
canonicalRefs fresh [] (RefTrie terminal edges)
  | terminal < 0 = (fresh,RefTrie fresh edges)
  | otherwise = (terminal,RefTrie terminal edges)
canonicalRefs fresh (Ref first:rest) (RefTrie terminal edges) =
  let (identity,next) = canonicalRefs fresh rest (IM.findWithDefault (RefTrie (-1) IM.empty) first edges)
  in (identity,RefTrie terminal (IM.insert first next edges))

validateStore :: Store -> Either String ValidatedStore
validateStore store = do
  unless (nodeCount > 0 && inRange (bounds records) (unRef (storeRoot store))) (Left "Invalid Core root")
  unless (scopeCount > 0) (Left "Missing Core module scope")
  forM_ (zip [0..] parentList) $ \(index,parentValue) -> unless
    (case parentValue of Nothing -> index == 0; Just (Scope p) -> index > 0 && p >= 0 && p < index)
    (Left "Invalid Core scope forest")
  -- Establish bounds before any dense indexing performed by the memo tables.
  forM_ (zip [0..] nodeList) $ \(index,value) -> do
    unless (all (\(Ref r) -> r >= 0 && r < index) (children value)) (Left "Non-backward Core child")
    unless (all validScope (nodeScope value) && all validBinder (usedBinders value))
      (Left "Unknown logical Core scope/binder")
  forM_ (zip [0..] binderList) $ \(index,info) -> do
    unless (validScope (binderScope info) && validRef (binderName info) && validRef (binderDeclaration info))
      (Left "Invalid indexed Core binder")
    requireString (binderName info)
    case records ! unRef (binderDeclaration info) of
      Declaration identity metadata | identity == Binder index -> do
        actualName <- requiredField 0 metadata
        unless (canonical ! unRef actualName == canonical ! unRef (binderName info))
          (Left "Binder index and declaration name disagree")
      _ -> Left "Binder declaration index mismatch"
  uniqueBinderNames
  uniqueSymbols
  forM_ (zip [0..] nodeList) $ \(index,value) -> case value of
    Declaration{} -> pure ()
    _ -> unless (all (\identity -> unRef (binderDeclaration (binderInfo identity)) < index) (usedBinders value))
      (Left "Logical binder use precedes its declaration")
  forM_ (zip [0..] nodeList) checkNode
  pure (ValidatedStore (storeRoot store) records binders parents (storeSymbols store))
  where
    nodeList = toList (storeNodes store)
    binderList = toList (storeBinders store)
    parentList = toList (storeScopes store)
    nodeCount = length nodeList; scopeCount = length parentList
    records = listArray (0,nodeCount-1) nodeList
    binders = listArray (0,length binderList-1) binderList
    parents = listArray (0,scopeCount-1) parentList
    validRef (Ref index) = inRange (bounds records) index
    validScope (Scope index) = inRange (bounds parents) index
    validBinder (Binder index) = inRange (bounds binders) index
    binderInfo (Binder index) = binders ! index
    parent (Scope index) = parents ! index
    node (Ref index) = records ! index
    expressionScope = \case
      Variable s _ _ -> Just s; Primitive s _ _ -> Just s; Constructor s _ _ _ -> Just s
      Literal s _ _ _ -> Just s; Application s _ _ _ _ _ _ -> Just s
      Lambda s _ _ _ _ -> Just s; Let s _ _ _ _ _ -> Just s; Case s _ _ _ _ _ -> Just s
      Void s _ -> Just s; Unsupported s _ -> Just s
      _ -> Nothing
    usedBinders = \case
      Variable _ _ (Local b) -> [b]; Case _ _ _ b _ _ -> [b]
      Definition b _ _ _ -> [b]; Declaration b _ -> [b]; BinderVector bs -> bs
      _ -> []
    canonical = listArray (0,nodeCount-1) (reverse (snd (foldl' nameStep (Names (-1) IM.empty,[]) (zip [0..] nodeList))))
    nameStep (trie,ids) (index,String value) = let (identity,next) = canonicalName index value trie in (next,identity:ids)
    nameStep (trie,ids) _ = (trie,(-1):ids)
    canonicalVectors = listArray (0,nodeCount-1)
      (reverse (snd (foldl' vectorStep (RefTrie (-1) IM.empty,[]) (zip [0..] nodeList))))
    vectorStep (trie,ids) (index,value) = case value of
      Vector refs -> insert refs
      BinderVector identities -> insert [metadata | identity <- identities,
        Declaration _ metadata <- [node (binderDeclaration (binderInfo identity))]]
      _ -> (trie,(-1):ids)
      where insert refs = let (identity,next) = canonicalRefs index refs trie in (next,identity:ids)
    keyKind value = case value of
      String bytes | bytes == BS.pack [105,100] -> Just 0
                   | bytes == BS.pack [98,105,110,100,101,114] -> Just 1
                   | bytes == BS.pack [98,105,110,100,101,114,115] -> Just 2
                   | bytes == BS.pack [101,118,97,108,117,97,116,101,100] -> Just 3
      _ -> Nothing
    facts = listArray (0,nodeCount-1) (map derive nodeList)
    derive value = case value of
      Vector refs -> Facts (Just (listArray (0,length refs-1) refs))
        (uniform [expressionScope (node r) | r <- refs])
        (uniform [case node r of Alternative scope _ _ _ _ _ -> parent scope; _ -> Nothing | r <- refs])
        (uniform (map definitionScope refs))
        Mixed (IM.fromList [(kind,index) | (index,r) <- zip [0..] refs, Just kind <- [keyKind (node r)]])
        (all (\ref -> case node ref of Boolean{} -> True; _ -> False) refs)
      BinderVector identities -> Facts Nothing Mixed Mixed Mixed
        (uniform (map (Just . binderScope . binderInfo) identities)) IM.empty False
      _ -> Facts Nothing Mixed Mixed Mixed Mixed IM.empty False
    fact (Ref index) = facts ! index
    definitionScope ref = case node ref of
      Definition identity body _ _ -> (,) (binderScope (binderInfo identity)) <$> expressionScope (node body)
      _ -> Nothing
    -- Stamp canonical string IDs once per unique vector. An object reusing a
    -- key vector reuses this fact rather than rescanning all of its fields.
    uniqueKeys = runSTArray $ do
      stamps <- newArray (0,nodeCount-1) (-1) :: ST s (STUArray s Int Int)
      results <- newArray (0,nodeCount-1) False
      forM_ (zip [0..] nodeList) $ \(index,value) -> case value of
        Vector refs -> do
          valid <- newSTRef True
          forM_ refs $ \r -> do
            let name = canonical ! unRef r
            if name < 0 then modifySTRef' valid (const False) else do
              previous <- readArray stamps name
              if previous == index then modifySTRef' valid (const False) else writeArray stamps name index
          readSTRef valid >>= writeArray results index
        _ -> pure ()
      pure results
    vector ref = maybe (Left "Expected Core vector") Right (vectorValues (fact ref))
    object ref = case node ref of
      Object keys values -> do
        ks <- vector keys; vs <- vector values
        unless (bounds ks == bounds vs && uniqueKeys ! unRef keys) (Left "Invalid/duplicate Core object fields")
        pure (keys,vs)
      _ -> Left "Expected Core metadata object"
    requiredField kind ref = do
      (keys,values) <- object ref
      case IM.lookup kind (metadataPositions (fact keys)) of
        Just position -> pure (values ! position)
        Nothing -> Left "Missing Core identity/scope metadata field"
    requireString ref = case node ref of String{} -> pure (); _ -> Left "Expected Core string"
    atScope wanted ref = unless (expressionScope (node ref) == Just wanted) (Left "Core expression edge changes lexical scope")
    childScope outer inner = unless (parent inner == Just outer) (Left "Invalid Core child-scope transition")
    allExpressions wanted ref = vector ref >> unless (matches wanted (expressionScopes (fact ref))) (Left "Core argument scope mismatch")
    allBinders wanted ref = case node ref of
      BinderVector{} -> unless (matches wanted (binderOwners (fact ref))) (Left "Core binder-owner mismatch")
      _ -> Left "Expected logical Core binder vector"
    intervals = scopeIntervals parentList
    dominates outer inner = let (a,b) = intervals ! unScope outer; (c,d) = intervals ! unScope inner in a <= c && d <= b
    sameBinderMetadata identity metadata = case node (binderDeclaration (binderInfo identity)) of
      Declaration _ expected -> unless (metadata == expected) (Left "Core binder occurrence proof differs from declaration")
      _ -> Left "Missing Core binder declaration"
    uniqueBinderNames = foldl' (\result info -> do
      seen <- result
      let owner = unScope (binderScope info); name = canonical ! unRef (binderName info)
          names = IM.findWithDefault IM.empty owner seen
      unless (IM.notMember name names) (Left "Duplicate logical binder name in one scope")
      pure (IM.insert owner (IM.insert name () names) seen)) (Right IM.empty) binderList >> pure ()
    uniqueSymbols = foldl' (\result (name,definition) -> do
      seen <- result
      unless (validRef name && validRef definition) (Left "Symbol index outside records")
      requireString name
      let identity = canonical ! unRef name
      unless (IM.notMember identity seen) (Left "Duplicate module symbol")
      case node definition of
        Definition binder body _ _ -> do
          unless (binderScope (binderInfo binder) == Scope 0 &&
            canonical ! unRef (binderName (binderInfo binder)) == identity) (Left "Module symbol identity mismatch")
          atScope (Scope 0) body
        _ -> Left "Module symbol is not a definition"
      pure (IM.insert identity () seen)) (Right IM.empty) (storeSymbols store) >> pure ()
    checkNode (index,value) = case value of
      Object{} -> objectRef value >> pure ()
      Layout fields -> do
        (keys,_) <- object fields
        unless (IM.notMember 3 (metadataPositions (fact keys))) (Left "Canonical layout contains outer evaluated proof")
      Representation layout proof -> case node layout of
        Layout fields -> do
          (_,values) <- object fields
          let size = snd (bounds values)+1
          case proof of
            Absent -> pure ()
            Evaluated position -> unless (position >= 0 && position <= size) (Left "Invalid evaluated insertion")
            Unevaluated position -> unless (position >= 0 && position <= size) (Left "Invalid evaluated insertion")
        _ -> Left "Representation does not reference a layout"
      Variable use metadata target -> do
        object metadata >> pure ()
        case target of
          Global name -> requireString name
          Local identity -> unless (dominates (binderScope (binderInfo identity)) use) (Left "Local Core use escapes its binder scope")
      Primitive _ metadata name -> object metadata >> requireString name
      Constructor _ metadata name arity -> object metadata >> requireString name >> unless (arity >= 0) (Left "Negative constructor arity")
      Literal _ metadata kind payload -> object metadata >> requireString kind >> requireString payload
      Application use metadata function arguments lifted _ _ -> do
        object metadata >> atScope use function >> allExpressions use arguments
        args <- vector arguments; flags <- vector lifted
        unless (bounds args == bounds flags && booleanElements (fact lifted)) (Left "Invalid application operand/lifted vector")
      Lambda use metadata bodyScope parameters body ->
        object metadata >> childScope use bodyScope >> allBinders bodyScope parameters >> atScope bodyScope body
      Let use metadata recursive bodyScope definitions body -> do
        object metadata >> childScope use bodyScope >> atScope bodyScope body
        vector definitions >> unless (matches (bodyScope,if recursive then bodyScope else use)
          (definitionScopes (fact definitions))) (Left "Let RHS/binder scope mismatch")
      Case use metadata branchScope identity scrutinee alternatives -> do
        childScope use branchScope >> atScope use scrutinee
        unless (binderScope (binderInfo identity) == branchScope) (Left "Case binder scope mismatch")
        requiredField 1 metadata >>= sameBinderMetadata identity
        vector alternatives >> unless (matches branchScope (alternativeParents (fact alternatives))) (Left "Alternative parent-scope mismatch")
      Void _ metadata -> object metadata >> pure ()
      Unsupported _ reason -> requireString reason
      Definition identity body metadata position -> do
        unless (expressionScope (node body) /= Nothing) (Left "Binding body is not an expression")
        name <- requiredField 0 metadata
        unless (name == binderName (binderInfo identity)) (Left "Definition and declaration names differ")
        (_,values) <- object metadata
        unless (position >= 0 && position <= snd (bounds values)+1) (Left "Binding expr insertion outside fields")
      Declaration identity metadata -> do
        unless (binderDeclaration (binderInfo identity) == Ref index) (Left "Duplicate/unindexed binder declaration")
        sameBinderMetadata identity metadata
      Alternative bodyScope kind discriminator parameters body metadata -> do
        atScope bodyScope body >> allBinders bodyScope parameters
        case (kind,node discriminator) of
          (DefaultAlt,Null) -> pure (); (DataAlt,String{}) -> pure ()
          (LiteralAlt,Vector [a,b]) -> requireString a >> requireString b
          _ -> Left "Invalid alternative discriminator"
        actual <- requiredField 2 metadata
        vector actual >> unless (canonicalVectors ! unRef actual == canonicalVectors ! unRef parameters)
          (Left "Alternative binder metadata differs from indexed declarations")
      String bytes -> case Text.decodeUtf8' bytes of
        Left _ -> Left "Invalid Core UTF8 string"
        Right _ -> pure ()
      _ -> pure ()
    objectRef (Object keys values) = do
      ks <- vector keys; vs <- vector values
      unless (bounds ks == bounds vs && uniqueKeys ! unRef keys) (Left "Invalid/duplicate Core object fields")
    objectRef _ = Left "Expected ordered object"

scopeIntervals :: [Maybe Scope] -> Array Int (Int,Int)
scopeIntervals parents = runSTArray $ do
  let count = length parents; range = (0,count-1)
  first <- newArray range (-1) :: ST s (STUArray s Int Int)
  next <- newArray range (-1) :: ST s (STUArray s Int Int)
  forM_ (reverse (zip [0..] parents)) $ \(index,parent) -> case parent of
    Nothing -> pure ()
    Just (Scope owner) -> readArray first owner >>= writeArray next index >> writeArray first owner index
  result <- newArray range (0,0)
  tick <- newSTRef 0
  let visit index = do
        enter <- readSTRef tick
        modifySTRef' tick (+1)
        child <- readArray first index
        siblings child
        leave <- readSTRef tick
        writeArray result index (enter,leave)
      siblings index | index < 0 = pure ()
                     | otherwise = visit index >> readArray next index >>= siblings
  visit 0
  pure result
