-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module FileWaitAudit (waitReadRoot, waitWriteRoot) where

import GHC.Exts (Int#, RealWorld, State#, waitRead#, waitWrite#)

-- These are genuine RTS primops. Their implicit blockedOnBadFD closure must
-- be supplied by the selected installed ghc-internal, never by this fixture.
{-# OPAQUE waitReadRoot #-}
waitReadRoot :: Int# -> State# RealWorld -> State# RealWorld
waitReadRoot fd s = waitRead# fd s

{-# OPAQUE waitWriteRoot #-}
waitWriteRoot :: Int# -> State# RealWorld -> State# RealWorld
waitWriteRoot fd s = waitWrite# fd s
