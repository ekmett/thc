-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import LazyIOCallbackAudit

main :: IO ()
main = do
  print catchLazyActionHead
  print catchLazyHandlerHead
  print keepAliveScalar
  print keepAliveTuple
