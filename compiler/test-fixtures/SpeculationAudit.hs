{-# LANGUAGE MagicHash, NoImplicitPrelude, UndecidableSuperClasses, UndecidableInstances, FlexibleInstances #-}
-- Compiler-only analysis fixture. These expressions validate exported GHC
-- certificates; they do not enlarge the runtime's supported primops/classes.
module SpeculationAudit where
import GHC.Exts (Int#, (-#), quotInt#)
data Box = Box Int#
data Lazy = Lazy Box
{-# OPAQUE ignore #-}
ignore :: Box -> Int#
ignore _ = 1#
{-# OPAQUE addBox #-}
addBox :: Box -> Box -> Box
addBox (Box x) _ = Box x
{-# OPAQUE keepPAP #-}
keepPAP :: (Box -> Box) -> Int#
keepPAP _ = 2#
{-# OPAQUE bottom #-}
bottom :: Box
bottom = bottom
safe :: Int# -> Int#
safe n = ignore (Box (n -# 1#))
unsafe :: Int# -> Int#
unsafe n = ignore (Box (quotInt# n 0#))
safeDivision :: Int# -> Int#
safeDivision n = ignore (Box (quotInt# n 3#))
lazyPAP :: Int# -> Int#
lazyPAP _ = keepPAP (addBox bottom)
lazyField :: Int# -> Lazy
lazyField _ = Lazy bottom
-- CorePrep's guarded-recursive-dictionary pitfall. GHC keeps a recursive
-- group containing the DFun and a saturated call back to it; the latter must
-- not receive a speculative-evaluation certificate while inside that group.
class Foo a => Foo a where
  method :: a -> a
data Wrap a = Wrap a
instance Foo a => Foo (Wrap a) where
  method x = x
