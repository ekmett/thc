-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module TagToEnumExternal (External, externalCode) where
import GHC.Exts

data External = Far | Near | Hidden | Last
{-# OPAQUE externalCode #-}
externalCode :: External -> Int#
externalCode Far = 43#
externalCode Near = -7#
externalCode Hidden = 91#
externalCode Last = 1009#
