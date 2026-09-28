-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THHelper
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC Template Haskell
--
-- Module for the @run-project@ integration fixture.
module THHelper (zero) where

import Language.Haskell.TH (Exp, Lit(IntegerL), Q, litE)

zero :: Q Exp
zero = litE (IntegerL 0)
