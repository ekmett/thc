-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
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
