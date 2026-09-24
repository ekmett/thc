module THC.Driver.Json (Json(..), renderJson) where

import Data.Char (ord)
import Data.List (intercalate)
import Numeric (showHex)

-- Keep the bootstrap dependency set within the packages shipped with GHC.
data Json
  = Object [(String, Json)]
  | Array [Json]
  | String String
  | Boolean Bool
  | Null

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
