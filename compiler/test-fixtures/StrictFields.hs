{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
module StrictFields where
import GHC.Exts (Int#)

data Box = Box Int#
data Lazy = Lazy Box
data Strict = Strict {-# NOUNPACK #-} !Box

{-# OPAQUE lazyConstruct #-}
lazyConstruct :: Box -> Lazy
lazyConstruct x = Lazy x

{-# OPAQUE strictConstruct #-}
strictConstruct :: Box -> Strict
strictConstruct x = Strict x
