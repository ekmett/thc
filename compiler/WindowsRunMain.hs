-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module THC.WindowsRunMain (thcRunMain) where

import GHC.Exts (RealWorld, State#)
import GHC.IO (IO(..))
import qualified Main

-- Without stock interface optimizations Main.main may be a thunk producing an
-- IO action. Preserve that binding, including its laziness, and let GHC expose
-- the existing host-entry ABI as a genuine partial application. OPAQUE keeps
-- the state-transformer boundary visible without changing the user's program.
{-# OPAQUE enter #-}
enter :: IO () -> State# RealWorld -> (# State# RealWorld, () #)
enter (IO action) state = action state

thcRunMain :: IO ()
thcRunMain = IO (enter Main.main)
