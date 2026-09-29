-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module BackendAnnotations (entry, astRoot, byteRoot, value, closureAst, closureByte) where

import GHC.Exts (Int#, Int(I#))

{-# ANN module ("thc:backend=ast" :: String) #-}
{-# ANN byteRoot ("thc:backend=bytecode" :: String) #-}
{-# ANN value ("thc:backend=bytecode" :: String) #-}
{-# ANN closureByte ("thc:backend=bytecode" :: String) #-}
{-# ANN astRoot ("unrelated" :: String) #-}

{-# OPAQUE astRoot #-}
astRoot :: Int -> Int
astRoot x = byteRoot (hidden x) + value

{-# OPAQUE byteRoot #-}
byteRoot :: Int -> Int
byteRoot x = x * 2

{-# OPAQUE value #-}
value :: Int
value = 42

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case astRoot (I# x) of I# result -> result

-- Retained interface unfoldings exercise original policy ownership when the
-- dependency exporter combines them into one synthetic closure module.
{-# NOINLINE closureAst #-}
closureAst :: Int -> Int
closureAst x = x + 1

{-# NOINLINE closureByte #-}
closureByte :: Int -> Int
closureByte x = x + 2

{-# OPAQUE hidden #-}
{-# ANN hidden ("thc:backend=bytecode" :: String) #-}
hidden :: Int -> Int
hidden x = x
