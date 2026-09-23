{-# LANGUAGE MagicHash #-}
module TagToEnumExternal (External, externalCode) where
import GHC.Exts

data External = Far | Near | Hidden | Last
{-# OPAQUE externalCode #-}
externalCode :: External -> Int#
externalCode Far = 43#
externalCode Near = -7#
externalCode Hidden = 91#
externalCode Last = 1009#
