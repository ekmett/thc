-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import Control.Monad (forM_)
import qualified ClosureInspectionAudit as C
main :: IO ()
main = forM_ [minBound, -1, 0, 1, maxBound :: Int] $ \x@(I# n) ->
  forM_ [("payload", I# (C.payload n)), ("sizeConsistent", I# (C.sizeConsistent n)),
    ("pointerCount", I# (C.pointerCount n)), ("notStack", I# (C.notStack n)),
    ("noCCS", I# (C.noCCS n)), ("noProvenance", I# (C.noProvenance n)),
    ("cleared", I# (C.cleared n)), ("annotated", I# (C.annotated n)),
    ("annotatedResume", I# (C.annotatedResume n))] $ \(name, result) ->
      putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show result)
