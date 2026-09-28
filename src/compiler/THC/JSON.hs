-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.JSON
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bytestring
--
-- The producer's ordered JSON representation and byte-exact renderers.
module THC.JSON (J(..), json, jsonBytes, jsonBytesWithBindings) where

import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy as BL
import Data.Char (ord)
import Numeric (showHex)

-- | Emit the same UTF-8 module JSON while recording actual byte positions of
-- top-level binding objects and the complete bindings-array span. All offsets
-- are zero-based and the array end is exclusive. Field and binding order are
-- unchanged; counts come from the exact byte chunks used in the final output.
jsonBytesWithBindings :: J -> (BS.ByteString, [(String, Int)], (Int, Int))
jsonBytesWithBindings (O fields) = case break ((== "bindings") . fst) fields of
  (before, ("bindings", A bindings) : after)
    | all ((/= "bindings") . fst) after ->
      let prefix = BS.init (jsonBytes (O before)) <>
            (if null before then BS.empty else BS.singleton 44) <>
            jsonBytes (S "bindings") <> BS.pack [58, 91]
          start = BS.length prefix - 1
          (chunks, offsets, end) = emit (BS.length prefix) bindings
          suffix = (if null after then BS.empty else BS.singleton 44) <>
            BS.tail (jsonBytes (O after))
      in (BS.concat (prefix : chunks ++ [BS.singleton 93, suffix]), offsets, (start, end + 1))
  _ -> error "Core module requires one bindings array"
  where
    emit position [] = ([], [], position)
    emit position (value@(O members) : rest) = case [key | ("id", S key) <- members] of
      [key] ->
        let bytes = jsonBytes value
            delimiter = [BS.singleton 44 | not (null rest)]
            next = position + BS.length bytes + length delimiter
            (chunks, offsets, end) = emit next rest
        in (bytes : delimiter ++ chunks, (key, position) : offsets, end)
      _ -> error "Core binding requires one string ID"
    emit _ _ = error "Core bindings must be objects"
jsonBytesWithBindings _ = error "Core module must be an object"

-- | Ordered JSON: objects, arrays, strings, arbitrary-size integers, booleans
-- and null. Ordering is part of the producer's serialized output contract.
data J = O [(String,J)] | A [J] | S String | N Integer | B Bool | Z

-- | Render compact JSON with stable object-field order and integer spelling.
--
-- >>> putStrLn (json (O [("count", N 2), ("ready", B True)]))
-- {"count":2,"ready":true}
json :: J -> String
json value = render value ""
  where
    -- Append directly to the enclosing output instead of copying each child's
    -- complete String at every ancestor. Deep Core with source-note metadata
    -- otherwise spends minutes repeatedly copying the same JSON characters.
    render :: J -> ShowS
    render (O xs) = showChar '{' . separated field xs . showChar '}'
    render (A xs) = showChar '[' . separated render xs . showChar ']'
    render (S s) = showChar '"' . foldr ((.) . escape) id s . showChar '"'
    render (N n) = shows n
    render (B b) = showString (if b then "true" else "false")
    render Z = showString "null"
    field (key, item) = render (S key) . showChar ':' . render item
    separated :: (a -> ShowS) -> [a] -> ShowS
    separated _ [] = id
    separated item (x:xs) = item x . foldr (\y rest -> showChar ',' . item y . rest) id xs
    escape '"' = showString "\\\""
    escape '\\' = showString "\\\\"
    escape c | ord c < 32 = showString "\\u" . showString (replicate (4-length h) '0') . showString h
      where h = showHex (ord c) ""
    escape c = showChar c

-- | UTF-8 bytes with exactly the same field order, escaping and numeric spelling
-- as 'json'. Forcing the strict result finishes rendering before a caller emits
-- any success bytes. In particular, it does not retain a character-list copy of
-- the complete document. Reject surrogate code points as a UTF-8 Handle would;
-- the Builder primitives themselves do not validate them.
--
-- >>> BS.unpack (jsonBytes (A [N 1, Z]))
-- [91,49,44,110,117,108,108,93]
jsonBytes :: J -> BS.ByteString
jsonBytes = BL.toStrict . Builder.toLazyByteString . render
  where
    render (O xs) = Builder.char7 '{' <> separated field xs <> Builder.char7 '}'
    render (A xs) = Builder.char7 '[' <> separated render xs <> Builder.char7 ']'
    render (S s) = Builder.char7 '"' <> foldMap escape s <> Builder.char7 '"'
    render (N n) = Builder.integerDec n
    render (B b) = Builder.string7 (if b then "true" else "false")
    render Z = Builder.string7 "null"
    field (key, item) = render (S key) <> Builder.char7 ':' <> render item
    separated _ [] = mempty
    separated item (x:xs) = item x <> foldMap (\y -> Builder.char7 ',' <> item y) xs
    escape '"' = Builder.string7 "\\\""
    escape '\\' = Builder.string7 "\\\\"
    escape c
      | ord c < 32 = let h = showHex (ord c) ""
        in Builder.string7 ("\\u" ++ replicate (4-length h) '0' ++ h)
      | ord c >= 0xd800 && ord c <= 0xdfff = error "THC JSON string contains a surrogate code point"
      | otherwise = Builder.charUtf8 c
