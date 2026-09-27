-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Compiler fixture for proxy void predicate Core and metadata.
module Main where
import Control.Monad (unless)
import GHC.Plugins
import GHC.Types.Id.Make (proxyHashId, realWorldPrimId)
import GHC.Types.RepType (typePrimRep_maybe)
import THC.Wired (isWiredVoid, wiredOrigin)

main :: IO ()
main = do
  let wrong = setIdUnique proxyHashId (getUnique (mkTemplateLocal 103 (idType proxyHashId)))
      checks =
        [ ("wired proxy", isWiredVoid proxyHashId)
        , ("wired state", isWiredVoid realWorldPrimId)
        , ("same name and type, different key", not (isWiredVoid wrong))
        , ("zero physical registers", typePrimRep_maybe (idType proxyHashId) == Just [])
        , ("distinct proxy origin", wiredOrigin proxyHashId == Just "GHC.Types.Id.Make.proxyHashId/zero-width-proxy")
        , ("unchanged state origin", wiredOrigin realWorldPrimId == Just "GHC.Types.Id.Make.realWorldPrimId/zero-width-state")
        ]
  mapM_ (\(label, passed) -> unless passed (error label) >> putStrLn ("PASS " ++ label)) checks
