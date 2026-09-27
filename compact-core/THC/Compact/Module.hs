-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Module
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC fingerprint API; scoped binary output
--
-- One-pass typed module publication. Binding positions are captured as bytes
-- are written; the existing canonical MD5 encoder sorts only lookup records.
module THC.Compact.Module (writeModule) where

import Control.Monad (foldM, unless, void)
import Data.Bits ((.|.))
import Data.IORef
import THC.CoreSymbols (symbolDigest, encodeMd5Symbols)
import THC.Compact.Core
import THC.Compact.Encode
import THC.Compact.Facts
import THC.Compact.Wire
import THC.Compact.Writer

-- | Publish typed records with no debug streams. Callers must explicitly choose
-- this debug-free path; original debug information is not parsed or discarded
-- by this function. Module-level known unsupported provenance fails preparation.
writeModule :: FilePath -> Facts -> [Binding] -> IO Footer
writeModule destination facts bindings = do
  encoderSlot <- newIORef Nothing
  let prepare streams = do
        encoder <- newEncoder streams
        writeIORef encoderSlot (Just encoder)
        encodeFacts encoder facts
      produce streams = do
        encoder <- readIORef encoderSlot >>= maybe (fail "Missing prepared compact encoder") pure
        (!count,!alias,rows) <- foldM (binding encoder) (0,False,[]) bindings
        directory <- either fail pure (encodeMd5Symbols rows)
        void (appendBytes streams Fingerprints directory)
        control <- containsDelimitedControl encoder
        let summaries = (if control then 1 else 0) .|.
              (if registration then 2 else 0) .|. (if alias then 4 else 0)
        pure (count,summaries)
      binding encoder (!count,!alias,rows) value = do
        key <- case bindingIdentity value of
          Global global -> pure global
          Local _ -> fail "Compact top-level binding requires a global identity"
        unless (count < maxBound) (fail "Compact binding count exceeds uint64")
        offset <- encodeBinding encoder value
        digest <- symbolDigest key
        let !isAlias = alias || key == "main::" <> factsModule facts <> ".main"
        pure (count+1,isAlias,(digest,offset):rows)
      registration = case factsForeign facts of
        Known (ForeignArtifacts _ _ stubs files) -> not (null files) || case stubs of
          Known (Stubs _ _ initializers finalizers) -> not (null initializers && null finalizers)
          _ -> False
        _ -> False
  writeContainerStreamed destination prepare produce
