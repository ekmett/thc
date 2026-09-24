-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
-- GHC normally eta-expands the State# callback and moves this checkpoint into
-- its body. Keep the action head genuinely lazy for the capture regression.
{-# OPTIONS_GHC -fno-do-lambda-eta-expansion #-}
module LazyIOCallbackAudit where

import GHC.Exts

data Box = Box Int#

{-# OPAQUE action42 #-}
action42 :: State# RealWorld -> (# State# RealWorld, Box #)
action42 s = (# s, Box 42# #)

{-# OPAQUE lazyActionHead #-}
lazyActionHead :: State# RealWorld -> (# State# RealWorld, Box #)
lazyActionHead = case noDuplicate# realWorld# of _ -> action42

{-# OPAQUE lazyHandlerHead #-}
lazyHandlerHead :: Box -> Box -> State# RealWorld -> (# State# RealWorld, Box #)
lazyHandlerHead (Box added) = case noDuplicate# realWorld# of { _ ->
  \(Box value) s -> (# s, Box (value +# added) #) }

{-# OPAQUE catchLazyActionHead #-}
catchLazyActionHead :: Int
catchLazyActionHead =
  case catch# lazyActionHead (\_ s -> (# s, Box 0# #)) realWorld# of
    (# _, Box result #) -> I# result

{-# OPAQUE catchLazyHandlerHead #-}
catchLazyHandlerHead :: Int
catchLazyHandlerHead =
  case catch# (\s -> raiseIO# (Box 7#) s)
              (lazyHandlerHead (Box 70#)) realWorld# of
    (# _, Box result #) -> I# result
