-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE Safe #-}
module InteropApi (snapshot) where

import qualified THC.Interop.Buffer as Buffer
import qualified THC.Polyglot as Polyglot

snapshot :: IO ()
snapshot = do
  value <- Polyglot.evalJS "new Uint8Array([20,30]).buffer"# "interop.js"#
  _ <- Buffer.copySlice value 0 2
  pure ()
