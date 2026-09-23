{-# LANGUAGE MagicHash, UnboxedTuples #-}
module StateTupleAudit where
import GHC.Exts

data Box = Box Int# Box
data Failure = Failure

{-# OPAQUE pair #-}
pair :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
pair x s = (# s, x +# 7# #)

{-# OPAQUE forward #-}
forward :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
forward x s = pair x s

{-# OPAQUE zero #-}
zero :: State# RealWorld -> (# State# RealWorld, (# #), State# RealWorld #)
zero s = (# s, (# #), s #)

{-# OPAQUE lazyPair #-}
lazyPair :: Int# -> State# RealWorld -> (# State# RealWorld, Box #)
lazyPair x s = (# s, Box x (raise# Failure) #)

{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply f x = f x

{-# OPAQUE pairCase #-}
pairCase :: Int# -> Int#
pairCase x = runRW# (\s ->
  case forward x s of
    (# s1, y #) -> case zero s1 of
      (# s2, _, s3 #) -> case pair y s2 of
        (# _, z #) -> case pair z s3 of
          (# _, w #) -> w)

{-# OPAQUE lazyCase #-}
lazyCase :: Int# -> Int#
lazyCase x = runRW# (\s -> case lazyPair x s of (# _, Box y _ #) -> y)

{-# OPAQUE captureCase #-}
captureCase :: Int# -> Int#
captureCase x = runRW# (\s ->
  case pair x s of
    (# s1, y #) -> apply (\z -> case pair z s1 of (# _, w #) -> w) y)

{-# OPAQUE failState #-}
failState :: Int# -> State# RealWorld
failState x = case x <# 0# of
  1# -> raise# Failure
  _ -> realWorld#

{-# OPAQUE effectPair #-}
effectPair :: Int# -> (# State# RealWorld, Int# #)
effectPair x = (# failState x, x #)

{-# OPAQUE effectCase #-}
effectCase :: Int# -> Int#
effectCase x = case effectPair x of (# _, y #) -> y

-- Structural control: ByteArray# remains one unlifted boxed reference.
{-# OPAQUE byteShape #-}
byteShape :: ByteArray# -> State# RealWorld -> (# State# RealWorld, ByteArray# #)
byteShape a s = (# s, a #)
