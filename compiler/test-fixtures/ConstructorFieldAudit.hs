{-# LANGUAGE MagicHash, NoImplicitPrelude, GADTs #-}
-- Constructor worker types are not all Object, but lazy fields may hold thunks.
module ConstructorFieldAudit where
import GHC.Exts (Int#)

data Payload = End | Payload Int#
newtype Wrapped = Wrapped Payload

data Record a = Record !Payload Payload !a a !(Int# -> Int#) (Int# -> Int#)
data Primitive = Primitive Int#
data NewtypeField = NewtypeField Wrapped
data Evidence a where
  Evidence :: Payload -> Evidence Payload
