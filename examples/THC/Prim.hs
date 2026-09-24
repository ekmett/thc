-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
-- Small separately compiled module: no custom JVM builtins for these functions.
module THC.Prim (Box(..), Pair(..), List(..), addBox, mulBox, applyBox, ignoreBox, firstBox, chooseFunction, moduleAdd, Unary(..), pickUnary) where

import GHC.Exts (Int#, (+#), (*#), (<=#))

data Box = Box Int#
data Pair = Pair Box Box
data List = Nil | Cons Box List
data Function = Function (Box -> Box -> Box)
data Unary = Unary (Box -> Box)

-- OPAQUE preserves the call boundary and suppresses demand/worker-wrapper
-- information at callers, making runtime application and laziness observable.
{-# OPAQUE addBox #-}
addBox :: Box -> Box -> Box
addBox (Box x) (Box y) = Box (x +# y)

{-# OPAQUE mulBox #-}
mulBox :: Box -> Box -> Box
mulBox (Box x) (Box y) = Box (x *# y)

{-# OPAQUE applyBox #-}
applyBox :: (Box -> Box) -> Box -> Box
applyBox f x = f x

{-# OPAQUE ignoreBox #-}
ignoreBox :: Box -> Box -> Box
ignoreBox _ x = x

{-# OPAQUE firstBox #-}
firstBox :: Pair -> Box
firstBox (Pair x _) = x

{-# OPAQUE chooseFunction #-}
chooseFunction :: Int# -> (Box -> Box -> Box)
chooseFunction n = case pickFunction n of Function f -> f

{-# OPAQUE pickFunction #-}
pickFunction :: Int# -> Function
pickFunction n = case n <=# 0# of
  1# -> Function addBox
  _  -> Function mulBox

{-# OPAQUE moduleAdd #-}
moduleAdd :: Int# -> Int#
moduleAdd n = n +# 29#

-- Five distinct arity-one targets at a shared unknown-call site exceed the
-- prototype's three-target direct-call cache.
{-# OPAQUE increment1 #-}
increment1 :: Box -> Box
increment1 (Box n) = Box (n +# 1#)
{-# OPAQUE increment2 #-}
increment2 :: Box -> Box
increment2 (Box n) = Box (n +# 2#)
{-# OPAQUE increment3 #-}
increment3 :: Box -> Box
increment3 (Box n) = Box (n +# 3#)
{-# OPAQUE increment4 #-}
increment4 :: Box -> Box
increment4 (Box n) = Box (n +# 4#)
{-# OPAQUE increment5 #-}
increment5 :: Box -> Box
increment5 (Box n) = Box (n +# 5#)

{-# OPAQUE pickUnary #-}
pickUnary :: Int# -> Unary
pickUnary n = case n <=# 0# of
  1# -> Unary increment1
  _ -> case n <=# 1# of
    1# -> Unary increment2
    _ -> case n <=# 2# of
      1# -> Unary increment3
      _ -> case n <=# 7# of
        1# -> Unary increment4
        _ -> Unary increment5
