-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}
module TupleCaptureAudit where
import GHC.Exts

data Box = Box Int#
data Failure = Failure Int#
data Fn = Fn (Int# -> Int#)
data Lazy = Lazy Int
{-# OPAQUE lazyBottom #-}
lazyBottom :: Box
lazyBottom = raise# (Failure 999#)

type Payload = (# (# #), (# Int#, Box #), Float#, Double#, Addr# #)

{-# OPAQUE produce #-}
produce :: Int# -> Payload
produce x = (# (# #), (# x, lazyBottom #), 3.75#, -5.25##, "tuple-capture"# #)

{-# OPAQUE consume #-}
consume :: Payload -> Int# -> Int#
consume (# (# #), (# x, _ #), f, d, address #) y =
  x +# y *# 3# +# float2Int# f +# double2Int# d +# ord# (indexCharOffAddr# address 2#)

{-# OPAQUE makeClosure #-}
makeClosure :: Payload -> Int# -> Fn
makeClosure t x = Fn (\y -> consume t (x +# y))

{-# OPAQUE makeThunk #-}
makeThunk :: Payload -> Int# -> Lazy
makeThunk t x = Lazy (I# (consume t (x -# 7#)))

{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply f x = f x

{-# OPAQUE escaped #-}
escaped :: Int# -> Int#
escaped x = case retainedClosure x of Fn f -> apply f 13# +# apply f 17#

{-# OPAQUE retainedClosure #-}
retainedClosure :: Int# -> Fn
retainedClosure x = case produce x of t -> makeClosure t (x +# 11#)

{-# OPAQUE thunk #-}
thunk :: Int# -> Int#
thunk x = case retainedThunk x of Lazy boxed -> case boxed of I# result -> result +# result

{-# OPAQUE retainedThunk #-}
retainedThunk :: Int# -> Lazy
retainedThunk x = case produce x of t -> makeThunk t (x +# 19#)

{-# OPAQUE twoCaptured #-}
twoCaptured :: Payload -> Payload -> Fn
twoCaptured first second = Fn (\y -> consume first y -# consume second (y +# 23#))

{-# OPAQUE independent #-}
independent :: Int# -> Int#
independent x = case produce x of
  first -> case produce (negateInt# x) of
    second -> case twoCaptured first second of
      Fn f -> apply f 29# +# apply f 31#

{-# OPAQUE makePap #-}
makePap :: Payload -> Fn
makePap t = Fn (consume t)

{-# OPAQUE papReuse #-}
papReuse :: Int# -> Int#
papReuse x = case produce x of
  t -> case makePap t of
    Fn f -> case makeClosure t (x +# 37#) of
      Fn g -> apply f 41# +# apply g 43# +# apply f 47#

{-# OPAQUE returnTuple #-}
returnTuple :: Payload -> Int# -> Payload
returnTuple t _ = t

{-# OPAQUE nestedClosure #-}
nestedClosure :: Payload -> Int# -> Fn
nestedClosure t x = Fn (\y -> case returnTuple t (x +# y) of u -> consume u y)

{-# OPAQUE nested #-}
nested :: Int# -> Int#
nested x = case produce x of t -> case nestedClosure t x of Fn f -> apply f 53#

{-# OPAQUE emptyConsumer #-}
emptyConsumer :: (# (# #), (# #) #) -> Int# -> Int#
emptyConsumer (# (# #), (# #) #) x = x +# 59#

{-# OPAQUE emptyMaker #-}
emptyMaker :: (# (# #), (# #) #) -> Fn
emptyMaker t = Fn (\x -> emptyConsumer t (x +# 61#))

{-# OPAQUE emptyCapture #-}
emptyCapture :: Int# -> Int#
emptyCapture x = case retainedEmpty x of Fn f -> apply f x

{-# OPAQUE retainedEmpty #-}
retainedEmpty :: Int# -> Fn
retainedEmpty _ = emptyMaker (# (# #), (# #) #)

{-# OPAQUE stateConsumer #-}
stateConsumer :: (# State# RealWorld, Int# #) -> Int# -> Int#
stateConsumer (# _, x #) y = x +# y *# 5#

{-# OPAQUE stateMaker #-}
stateMaker :: (# State# RealWorld, Int# #) -> Fn
stateMaker t = Fn (\x -> stateConsumer t (x +# 67#))

{-# OPAQUE stateCapture #-}
stateCapture :: Int# -> Int#
stateCapture x = runRW# (\s -> case stateMaker (# s, x #) of Fn f -> apply f x)

{-# OPAQUE lazyMaker #-}
lazyMaker :: (# Int#, Box #) -> Fn
lazyMaker t = Fn (\y -> case t of (# x, _ #) -> x +# y *# 7#)

{-# OPAQUE lazyCapture #-}
lazyCapture :: Int# -> Int#
lazyCapture x = case lazyMaker (# x, lazyBottom #) of Fn f -> apply f 71#
