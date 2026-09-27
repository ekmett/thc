-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE LambdaCase #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell
--
-- Explicit inspection of shared Core as the existing ordered JSON grammar.
-- Expansion is deliberately separate from production loading. The caller may
-- bound expanded record visits or explicitly request unlimited inspection.
module THC.CoreStore.Inspect (inspectBytes, inspectBuilder) where

import Control.Monad (foldM)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as BB
import qualified Data.ByteString.Lazy as BL
import qualified Data.Text.Encoding as Text
import qualified Data.Text as Text
import THC.CoreStore.Model
import THC.JSON (J(..), jsonBytes)

inspectBytes :: Maybe Integer -> Store -> Either String BS.ByteString
inspectBytes budget store = BL.toStrict . BB.toLazyByteString <$> inspectBuilder budget store

inspectBuilder :: Maybe Integer -> Store -> Either String BB.Builder
inspectBuilder budget store = snd <$> render budget (storeRoot store)
  where
    tick Nothing = Right Nothing
    tick (Just remaining)
      | remaining > 0 = Right (Just (remaining - 1))
      | otherwise = Left "Core inspection expansion budget exhausted (explicitly override to expand further)"
    literal value = BB.byteString (jsonBytes value)
    string value = literal (S value)
    text value = case Text.decodeUtf8' value of
      Left _ -> Left "Core string is not valid UTF8"
      Right decoded -> Right (Text.unpack decoded)
    many opening closing separator actions initial = do
      (remaining,values) <- foldM (\(left,acc) action -> do
        (next,value) <- action left
        pure (next,value : acc)) (initial,[]) actions
      pure (remaining,opening <> separated separator (reverse values) <> closing)
    separated _ [] = mempty
    separated separator (x:xs) = x <> foldMap (separator <>) xs
    array actions = many (BB.char7 '[') (BB.char7 ']') (BB.char7 ',') actions
    fixed value remaining = Right (remaining,value)
    token = fixed . string
    boolean = fixed . literal . B
    object entries = many (BB.char7 '{') (BB.char7 '}') (BB.char7 ',')
      [\remaining -> do (next,value) <- action remaining; pure (next,string key <> BB.char7 ':' <> value)
        | (key,action) <- entries]
    vector ref = nodeAt store ref >>= \case
      Vector values -> Right values
      _ -> Left "Core inspection expected a vector"
    fields ref = nodeAt store ref >>= \case
      Object keys values -> do
        names <- vector keys >>= mapM (\key -> nodeAt store key >>= \case
          String name -> text name
          _ -> Left "Core object key is not a string")
        items <- vector values
        if length names == length items then Right (zip names items)
          else Left "Core object keys/value lengths differ"
      _ -> Left "Core inspection expected an object"
    binders ref = nodeAt store ref >>= \case
      BinderVector values -> Right values
      _ -> Left "Core inspection expected a logical binder vector"
    binderMetadata identity = binderAt store identity >>= \info ->
      nodeAt store (binderDeclaration info) >>= \case
        Declaration actual value | actual == identity -> Right value
        _ -> Left "Core binder declaration index mismatch"
    binderNameAction identity remaining = do
      info <- binderAt store identity
      render remaining (binderName info)
    binderAction identity remaining = binderMetadata identity >>= render remaining
    insertAt position value values
      | position < 0 || position > length values = Left "Core inspection field insertion outside object"
      | otherwise = let (before,after) = splitAt position values in Right (before ++ [value] ++ after)
    render remaining ref = do
      next <- tick remaining
      nodeAt store ref >>= \case
        Null -> fixed (literal Z) next
        Boolean value -> boolean value next
        Integer value -> fixed (literal (N value)) next
        String value -> text value >>= \valueText -> token valueText next
        Vector values -> array (map (flip render) values) next
        Object{} -> fields ref >>= \values -> object [(key,flip render value) | (key,value) <- values] next
        Layout value -> render next value
        Representation layout proof -> do
          layoutFields <- nodeAt store layout >>= \case
            Layout value -> fields value
            _ -> Left "Core representation does not reference a layout"
          let values = [(key,flip render value) | (key,value) <- layoutFields]
          restored <- case proof of
            Absent -> Right values
            Unevaluated position -> insertAt position ("evaluated",boolean False) values
            Evaluated position -> insertAt position ("evaluated",boolean True) values
          object restored next
        Variable _ meta target -> array [token "var",case target of
          Global name -> flip render name
          Local identity -> binderNameAction identity,flip render meta] next
        Primitive _ meta name -> array [token "prim",flip render name,flip render meta] next
        Constructor _ meta name arity -> array
          [token "con",flip render name,fixed (literal (N (toInteger arity))),flip render meta] next
        Literal _ meta kind value -> array [token "lit",flip render kind,flip render value,flip render meta] next
        Application _ meta function arguments lifted hnf speculation -> array
          [token "app",flip render function,flip render arguments,flip render lifted,
           boolean hnf,boolean speculation,flip render meta] next
        Lambda _ meta _ parameters body -> do
          identities <- binders parameters
          array [token "lam",array (map binderAction identities),flip render body,flip render meta] next
        Let _ meta recursive _ definitions body -> array
          [token "let",boolean recursive,flip render definitions,flip render body,flip render meta] next
        Case _ meta _ identity scrutinee alternatives -> array
          [token "case",flip render scrutinee,binderNameAction identity,flip render alternatives,flip render meta] next
        Void _ meta -> array [token "void",flip render meta] next
        Unsupported _ reason -> array [token "unsupported",flip render reason] next
        Definition _ expression metadata position -> do
          values <- fields metadata
          restored <- insertAt position ("expr",flip render expression)
            [(key,flip render value) | (key,value) <- values]
          object restored next
        Declaration _ metadata -> render next metadata
        Alternative _ kind discriminator parameters body meta -> do
          identities <- binders parameters
          array [token (case kind of DefaultAlt -> "default"; DataAlt -> "data"; LiteralAlt -> "lit"),
            flip render discriminator,array (map binderNameAction identities),flip render body,flip render meta] next
        BinderVector _ -> Left "Logical binder vector requires its typed expression context"
