{-# LANGUAGE MagicHash #-}
module InterfaceRoots where
import GHC.Exts (noinline)
import qualified GHC.Internal.Exception as Exception
import GHC.Internal.Stack.Types (CallStack)
import GHC.Internal.Exception.Type (SomeException)
{-# OPAQUE exceptionInterfaceRoot #-}
exceptionInterfaceRoot :: String -> CallStack -> SomeException
exceptionInterfaceRoot = noinline Exception.errorCallWithCallStackException
