-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import qualified WideCharAddressAudit as Wide

main :: IO ()
main = mapM_ emit [0,1,127,128,255,256,32767,32768,55295,55296,57343,57344,65535,65536,1114111]
  where
    emit value@(I# code) = putStrLn (show value ++ "\t" ++ show (I# (Wide.wideCharRoundtrip code)))
