-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
{-# OPTIONS_GHC -fno-worker-wrapper -Wno-tabs #-}
module SourceNotes where
import GHC.Exts

{-# OPAQUE unicode #-}
unicode :: Int# -> Int#
unicode n = {- 😀 source columns count characters, not UTF-16 units -} n +# 1#

{-# OPAQUE tabbed #-}
tabbed :: Int# -> Int#
tabbed n =
	 n +# 2#

{-# LINE 200 "missing-original-source.hs" #-}
{-# OPAQUE missing #-}
missing :: Int# -> Int#
missing n = n +# 3#
