-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts
import qualified FloatingRemainderAudit as P
import qualified THC.InverseHyperbolic as Example

invoke :: String -> Int -> Int -> [Int]
invoke name (I# a) (I# b) = case name of
  "asinhFloat" -> [I# (P.asinhFloat a b), 0, 0, 0]
  "acoshFloat" -> [I# (P.acoshFloat a b), 0, 0, 0]
  "atanhFloat" -> [I# (P.atanhFloat a b), 0, 0, 0]
  "minFloat" -> [I# (P.minFloat a b), 0, 0, 0]
  "maxFloat" -> [I# (P.maxFloat a b), 0, 0, 0]
  "asinhDouble" -> [I# (P.asinhDouble a b), 0, 0, 0]
  "acoshDouble" -> [I# (P.acoshDouble a b), 0, 0, 0]
  "atanhDouble" -> [I# (P.atanhDouble a b), 0, 0, 0]
  "minDouble" -> [I# (P.minDouble a b), 0, 0, 0]
  "maxDouble" -> [I# (P.maxDouble a b), 0, 0, 0]
  "asinhExample" -> [I# (Example.asinhExample a), 0, 0, 0]
  "decodeWordsDirect" -> [I# (P.decodeWordsDirect a 0#), I# (P.decodeWordsDirect a 1#), I# (P.decodeWordsDirect a 2#), I# (P.decodeWordsDirect a 3#)]
  "decodeWordsCall" -> [I# (P.decodeWordsCall a 0#), I# (P.decodeWordsCall a 1#), I# (P.decodeWordsCall a 2#), I# (P.decodeWordsCall a 3#)]
  _ -> error "Unknown floating remainder entry"

emit :: [String] -> IO ()
emit [name,a,b] = putStrLn (unwords ([name,a,b] ++ map show (invoke name (read a) (read b))))
emit _ = error "Malformed floating remainder input"
main :: IO ()
main = getContents >>= mapM_ (emit . words) . lines
