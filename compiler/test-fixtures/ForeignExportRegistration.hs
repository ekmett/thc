-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, NoImplicitPrelude, UnboxedTuples #-}
module ForeignExportRegistration where

import GHC.Exts (Int#, deRefStablePtr#)
import GHC.Internal.Stable (StablePtr(..))
import GHC.Internal.Types (IO(..), Int(..))

-- The same callback type and body as the original Conc.Bound export.
foreign export ccall "thc_registration_entry" callback :: StablePtr (IO ()) -> IO ()
foreign import ccall "thc_registration_entry" callbackAgain :: StablePtr (IO ()) -> IO ()
callback :: StablePtr (IO ()) -> IO ()
callback (StablePtr pointer) = IO (\state -> case deRefStablePtr# pointer state of
  (# next, IO action #) -> action next)

-- Direct imports add Core but no native registration products. Selecting this
-- binding must still fail the ordinary foreign-call capability check.
foreign import ccall "abort" unsupported :: IO ()

-- Registering an exported CAF must not evaluate it.
foreign export ccall "thc_registration_bottom" bottom :: Int
{-# NOINLINE bottom #-}
bottom :: Int
bottom = bottom

probe :: Int# -> Int#
probe value = value

constant :: Int
constant = I# 17#
