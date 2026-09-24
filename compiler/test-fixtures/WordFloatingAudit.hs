-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module WordFloatingAudit where
import GHC.Exts

-- Runtime arguments keep the conversions present in pre- and post-Tidy Core.
{-# OPAQUE wordFloat #-}
wordFloat :: Word# -> Float#
wordFloat x = keepFloat (word2Float# x)
{-# OPAQUE wordDouble #-}
wordDouble :: Word# -> Double#
wordDouble x = keepDouble (word2Double# x)
{-# OPAQUE keepFloat #-}
keepFloat :: Float# -> Float#
keepFloat x = x
{-# OPAQUE keepDouble #-}
keepDouble :: Double# -> Double#
keepDouble x = x
