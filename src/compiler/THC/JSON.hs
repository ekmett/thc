-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.JSON
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; aeson module values
--
-- Compiler construction vocabulary. The historical module name does not imply
-- a serialized JSON path: the value enters the existing typed CBD encoder.
module THC.JSON (J(..), moduleValue) where

import qualified Data.Aeson as Aeson
import Data.Char (ord)
import Data.String (fromString)

data J = O [(String,J)] | A [J] | S String | N Integer | B Bool | Z

-- | Structural handoff only; never render, parse, or index JSON text.
moduleValue :: J -> Aeson.Value
moduleValue (O fields) = Aeson.object [(fromString (unicode key),moduleValue value) | (key,value) <- fields]
moduleValue (A values) = Aeson.toJSON (map moduleValue values)
moduleValue (S value) = Aeson.toJSON (unicode value)
moduleValue (N value) = Aeson.toJSON value
moduleValue (B value) = Aeson.Bool value
moduleValue Z = Aeson.Null

unicode :: String -> String
unicode value
  | any (\c -> ord c >= 0xd800 && ord c <= 0xdfff) value = error "THC module string contains a surrogate code point"
  | otherwise = value
