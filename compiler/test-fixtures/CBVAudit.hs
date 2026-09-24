{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
-- Real worker-wrapper eligibility with a boxed sum argument that stays boxed.
module CBVAudit where
import GHC.Exts (Int(I#), Int#, Char(C#), Char#, (+#), (-#), (<=#))

data Spine = Done | More Int# Spine

{-# NOINLINE walk #-}
walk :: Int -> Spine -> Int
walk (I# n) tree = case tree of
  Done -> I# n
  More k rest -> case n <=# 0# of
    1# -> I# k
    _ -> walk (I# (n -# 1#)) rest

-- Strict demand is not, by itself, a CBV calling convention.
{-# OPAQUE plainStrict #-}
plainStrict :: Spine -> Int#
plainStrict tree = case tree of Done -> 0#; More k _ -> k

{-# OPAQUE lazyIgnore #-}
lazyIgnore :: Spine -> Int#
lazyIgnore _ = 41#

{-# OPAQUE workerEntry #-}
workerEntry :: Int# -> Int#
workerEntry n = case walk (I# n) (More (n +# 7#) Done) of I# result -> result

-- Keep the genuine wired Char constructor in the ordinary test export. This
-- also supplies its WordRep field metadata without depending on the Map build.
{-# OPAQUE charBox #-}
charBox :: Char# -> Char
charBox c = C# c
