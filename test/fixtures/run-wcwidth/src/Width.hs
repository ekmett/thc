-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, MagicHash #-}

-- |
-- Module      : Width
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC C API FFI; declared native headers and libraries
--
-- Module for the @run-wcwidth@ integration fixture.
module Width (rawWidth, displayWidth) where

import Foreign.C.Types (CWchar(..), CInt(..))
import GHC.Exts

-- Exact declaration used by tasty-1.5.4 ConsoleReporter.stringWidth.
-- Its source and hash are retained by the fixture producer. The fallback
-- remains at the Haskell call site, not inside a replacement C implementation.
foreign import capi safe "wchar.h wcwidth" wcwidth :: CWchar -> CInt

{-# NOINLINE rawWidth #-}
rawWidth :: Int# -> Int#
rawWidth value = case fromIntegral (wcwidth (fromIntegral (I# value))) of I# width -> width

{-# NOINLINE displayWidth #-}
displayWidth :: Int# -> Int#
displayWidth value = case rawWidth value of width -> if isTrue# (width ==# -1#) then 1# else width
