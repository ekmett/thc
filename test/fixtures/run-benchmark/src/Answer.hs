-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Answer (answer) where
import GHC.Exts (Int(I#))
answer :: Int
answer = I# 42#
