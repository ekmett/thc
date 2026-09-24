-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module THHelper (zero) where

import Language.Haskell.TH (Exp, Lit(IntegerL), Q, litE)

zero :: Q Exp
zero = litE (IntegerL 0)
