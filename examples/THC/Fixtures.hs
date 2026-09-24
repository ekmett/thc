-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
module THC.Fixtures
  ( sumLoop, fib, captured, exact, under, over, unknown, shared
  , lazyArgument, lazyField, recursiveCaf, caseList, multiModule
  , cacheSaturation, mutualTail, selfMutualTail, blackhole, fibonacciBox
  , capturedChangingEnv, localMutualClosures, nestedCaptureThunk
  ) where

import GHC.Exts (Int#, (+#), (-#), (*#), (<=#))
import THC.Prim

sumLoop :: Int# -> Int#
sumLoop n = go n 0# where
  go x acc = case x <=# 0# of
    1# -> acc
    _  -> go (x -# 1#) (acc +# x)

fib :: Int# -> Int#
fib n = case n <=# 1# of
  1# -> n
  _  -> fib (n -# 1#) +# fib (n -# 2#)

captured :: Int# -> Int#
captured n = case applyBox (\(Box x) -> Box (n +# x)) (Box 7#) of
  Box r -> r

exact :: Int# -> Int#
exact n = case addBox (Box n) (Box 7#) of Box r -> r

under :: Int# -> Int#
under n = case applyBox (addBox (Box n)) (Box 7#) of Box r -> r

-- The source call has three arguments; the chooser returns another function.
-- The optimized-Core audit determines whether this remains overapplication.
over :: Int# -> Int#
over n = case chooseFunction n (Box 6#) (Box 7#) of Box r -> r

unknown :: Int# -> Int#
unknown n = case applyBox (chooseFunction n (Box 6#)) (Box 7#) of Box r -> r

{-# OPAQUE costlyBox #-}
costlyBox :: Box -> Box
costlyBox (Box n) = Box (n *# n +# 11#)

shared :: Int# -> Int#
shared n = let x = costlyBox (Box n)
           in case addBox x x of Box r -> r

{-# OPAQUE diverge #-}
diverge :: Box
diverge = diverge

lazyArgument :: Int# -> Int#
lazyArgument n = case ignoreBox diverge (Box n) of Box r -> r

lazyField :: Int# -> Int#
lazyField n = case firstBox (Pair (Box n) diverge) of Box r -> r

-- A productive recursive CAF. Traversal must share its back edge.
{-# NOINLINE ones #-}
ones :: List
ones = Cons (Box 1#) ones

recursiveCaf :: Int# -> Int#
recursiveCaf n = sumPrefix n ones 0#

{-# OPAQUE sumPrefix #-}
sumPrefix :: Int# -> List -> Int# -> Int#
sumPrefix n xs acc = case n <=# 0# of
  1# -> acc
  _ -> case xs of
    Nil -> acc
    Cons (Box x) rest -> sumPrefix (n -# 1#) rest (acc +# x)

{-# OPAQUE buildList #-}
buildList :: Int# -> List
buildList n = case n <=# 0# of
  1# -> Nil
  _ -> Cons (Box n) (buildList (n -# 1#))

caseList :: Int# -> Int#
caseList n = sumPrefix n (buildList n) 0#

multiModule :: Int# -> Int#
multiModule n = moduleAdd n

-- Not part of the terminating native oracle; THC should diagnose a blackhole.
blackhole :: Int# -> Int#
blackhole n = case diverge of Box x -> x +# n

-- A lifted recursive source function for optional closure/update experiments.
{-# OPAQUE fibonacciBox #-}
fibonacciBox :: Box -> Box
fibonacciBox (Box n) = case n <=# 1# of
  1# -> Box n
  _ -> addBox (fibonacciBox (Box (n -# 1#)))
              (fibonacciBox (Box (n -# 2#)))

cacheSaturation :: Int# -> Int#
cacheSaturation n = case pickUnary n of
  Unary f -> case applyBox f (Box n) of Box r -> r

mutualTail :: Int# -> Int#
mutualTail n = mutualEven n 0#

{-# OPAQUE mutualEven #-}
mutualEven :: Int# -> Int# -> Int#
mutualEven n acc = case n <=# 0# of
  1# -> acc
  _ -> mutualOdd (n -# 1#) (acc +# 1#)

{-# OPAQUE mutualOdd #-}
mutualOdd :: Int# -> Int# -> Int#
mutualOdd n acc = case n <=# 0# of
  1# -> acc
  _ -> mutualEven (n -# 1#) (acc +# 1#)

-- Each function self-calls before calling its peer. A self-loop must retain
-- ancestor tail-call masks, or this pattern can slowly grow the host stack.
selfMutualTail :: Int# -> Int#
selfMutualTail n = selfMutualA n 0# 0#

{-# OPAQUE selfMutualA #-}
selfMutualA :: Int# -> Int# -> Int# -> Int#
selfMutualA n acc phase = case n <=# 0# of
  1# -> acc
  _ -> case phase <=# 0# of
    1# -> selfMutualA (n -# 1#) (acc +# 1#) 1#
    _ -> selfMutualB (n -# 1#) (acc +# 1#) 0#

{-# OPAQUE selfMutualB #-}
selfMutualB :: Int# -> Int# -> Int# -> Int#
selfMutualB n acc phase = case n <=# 0# of
  1# -> acc
  _ -> case phase <=# 0# of
    1# -> selfMutualB (n -# 1#) (acc +# 1#) 1#
    _ -> selfMutualA (n -# 1#) (acc +# 1#) 0#

-- The closure returned by every factory call has the same body, but a different
-- captured unlifted offset. A self-tail loop must restore captures as well as
-- explicit arguments when entering the next closure instance.
capturedChangingEnv :: Int# -> Int#
capturedChangingEnv n = case changingFactory 17# of
  Unary f -> case applyBox f (Box n) of Box r -> r

{-# OPAQUE changingFactory #-}
changingFactory :: Int# -> Unary
changingFactory offset = Unary (\(Box remaining) ->
  case remaining <=# 0# of
    1# -> Box offset
    _ -> case changingFactory (offset +# 1#) of
      Unary next -> next (Box (remaining -# 1#)))

-- Both local recursive closures escape to an opaque consumer. They must retain
-- the original n and references to each other through the recursive knot.
localMutualClosures :: Int# -> Int#
localMutualClosures n =
  let {-# OPAQUE first #-}
      first :: Box -> Box
      first (Box remaining) = case remaining <=# 0# of
        1# -> Box (n +# 11#)
        _ -> second (Box (remaining -# 1#))
      {-# OPAQUE second #-}
      second :: Box -> Box
      second (Box remaining) = case remaining <=# 0# of
        1# -> Box (n +# 29#)
        _ -> first (Box (remaining -# 1#))
  in case consumeLocalPair first second (Box n) of Box r -> r

{-# OPAQUE consumeLocalPair #-}
consumeLocalPair :: (Box -> Box) -> (Box -> Box) -> Box -> Box
consumeLocalPair first second argument =
  addBox (first argument) (second argument)

-- The outer thunk outlives escapingFactory's frame. The two lambda binders named
-- n deliberately shadow the source input, and a second delayed application is
-- captured by a closure returned from another completed factory invocation.
nestedCaptureThunk :: Int# -> Int#
nestedCaptureThunk n = case escapingFactory n of
  Unary f -> case applyBox f (Box 7#) of Box r -> r

{-# OPAQUE escapingFactory #-}
escapingFactory :: Int# -> Unary
escapingFactory n =
  let outer = costlyBox (Box n)
  in Unary (\(Box n) ->
       let middle = addBox outer (Box n)
       in case retainNested (\(Box n) -> addBox middle (Box n)) of
            Unary f -> f (Box 5#))

{-# OPAQUE retainNested #-}
retainNested :: (Box -> Box) -> Unary
retainNested f = Unary f
