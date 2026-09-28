-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where
import InteropApi (snapshot)
import InteropSupport (support)
main :: IO ()
main = support `seq` snapshot
