-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main where

import GHC.Internal.Foreign.C.Error (Errno(..), errnoToIOError)
import System.IO.Error (ioeGetErrorString)

-- The installed ghc-internal implementation calls its original
-- base_strerror_r, then peeks the caller-owned C buffer into a String.
main :: IO ()
main = print [(number, ioeGetErrorString (errnoToIOError "fixture"
  (Errno (fromIntegral number)) Nothing Nothing)) | number <- [2 :: Int, 22]]
