-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where

import GHC.Exts (Int(..))
import HashableProbe

main :: IO ()
main = mapM_ entry probes
  where
    entry (name, invoke) = mapM_ (\salt -> mapM_ (\choice -> putStrLn
      (name ++ "\t" ++ show salt ++ "\t" ++ show choice ++ "\t" ++ show (invoke salt choice)))
      [0 .. 11]) [0, 1, -1, minBound, maxBound]
    probes =
      [("strictText", \(I# salt) (I# choice) -> I# (strictText salt choice)),
       ("strictBytes", \(I# salt) (I# choice) -> I# (strictBytes salt choice)),
       ("shortBytes", \(I# salt) (I# choice) -> I# (shortBytes salt choice)),
       ("lazyText", \(I# salt) (I# choice) -> I# (lazyText salt choice)),
       ("lazyBytes", \(I# salt) (I# choice) -> I# (lazyBytes salt choice))]
