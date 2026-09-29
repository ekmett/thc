-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC Backpack
--
-- Exercise both instantiations and return to the first one in a single program.
module Main (main) where

import qualified AddClient
import qualified MultiplyClient

main :: IO ()
main = print (AddClient.evaluate 7, MultiplyClient.evaluate 7, AddClient.evaluate 2)
