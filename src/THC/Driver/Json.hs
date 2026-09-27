-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.Json
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; base only
--
-- Render the driver's small ordered JSON format without an external JSON dependency.
module THC.Driver.Json (Json(..), renderJson) where

import Data.Char (ord)
import Data.List (intercalate)
import Numeric (showHex)

-- | Ordered JSON values used by the bootstrap planner. Object fields retain
-- their input order; the representation deliberately has no numeric variant.
-- Keep the bootstrap dependency set within the packages shipped with GHC.
data Json
  = Object [(String, Json)]
  | Array [Json]
  | String String
  | Boolean Bool
  | Null

-- | Render compact JSON, escaping quotes, backslashes and control characters.
--
-- >>> putStrLn (renderJson (Object [("ok", Boolean True), ("value", Null)]))
-- {"ok":true,"value":null}
-- >>> putStrLn (renderJson (Array [Boolean False, String "ready"]))
-- [false,"ready"]
renderJson :: Json -> String
renderJson (Object fields) = "{" ++ intercalate "," [quote k ++ ":" ++ renderJson v | (k, v) <- fields] ++ "}"
renderJson (Array values) = "[" ++ intercalate "," (map renderJson values) ++ "]"
renderJson (String value) = quote value
renderJson (Boolean value) = if value then "true" else "false"
renderJson Null = "null"

quote :: String -> String
quote value = '"' : concatMap escape value ++ "\""
  where
    escape '"' = "\\\""
    escape '\\' = "\\\\"
    escape c
      | ord c < 32 = let h = showHex (ord c) "" in "\\u" ++ replicate (4 - length h) '0' ++ h
      | otherwise = [c]
