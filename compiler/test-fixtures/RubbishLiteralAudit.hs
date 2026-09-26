-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module RubbishLiteralAudit where
import GHC.Exts

-- There is deliberately no Haskell syntax purporting to mean RUBBISH.
-- The GHC API preparer retains these bodies beneath genuine typed Core
-- DEFAULT cases. The continuation must still execute, including its input.
template :: Int# -> Int#
template n = n +# 17#
nativeTemplate :: Int -> Int
nativeTemplate (I# n) = I# (n +# 17#)
