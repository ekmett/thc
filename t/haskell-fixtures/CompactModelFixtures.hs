-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (041 compact-model)
-- Purpose: Encode small caller-supplied Core models for runtime and codec tests.
-- Produces/consumed result: Exactly the requested temporary CBD from input JSON; shared
--   executable location.
-- Cost and overlap: Useful shared tool, not an oracle fixture. Build it once; do not
--   create per-test archives or let several recipes publish its location file.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 041.
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC; host filesystem
--
-- Encode synthetic test models with the same CBD encoder as the compiler.
module CompactModelFixtures (writeCompactModel) where

import Control.Monad (void)
import Data.Aeson (eitherDecodeStrict')
import qualified Data.ByteString as BS
import THC.Compact.Module (writeModuleValue)

-- | @thc-fixtures compact-model INPUT_JSON OUTPUT_CBD@ reads a test model.
-- JSON is only fixture notation; the produced runtime input is a CBD archive.
writeCompactModel :: FilePath -> FilePath -> IO ()
writeCompactModel input output = do
  value <- BS.readFile input >>= either fail pure . eitherDecodeStrict'
  void (writeModuleValue output value)
