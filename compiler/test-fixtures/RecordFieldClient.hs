-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module RecordFieldClient (fieldAlias, duplicateFields) where

import GHC.Exts
import RecordFieldLibrary

{-# OPAQUE fieldAlias #-}
fieldAlias :: Int# -> Int#
fieldAlias n = applyPlain unique n

{-# OPAQUE duplicateFields #-}
duplicateFields :: Int# -> Int#
duplicateFields n = case leftValue (LeftRecord (I# n)) of
  I# left -> case rightValue (RightRecord (I# (n +# 7#))) of
    I# right -> left *# 3# +# right
