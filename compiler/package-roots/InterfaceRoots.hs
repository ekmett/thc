-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module InterfaceRoots where
import GHC.Exts (noinline)
import qualified GHC.Internal.Exception as Exception
import GHC.Internal.Stack.Types (CallStack)
import GHC.Internal.Exception.Type (SomeException)
{-# OPAQUE exceptionInterfaceRoot #-}
exceptionInterfaceRoot :: String -> CallStack -> SomeException
exceptionInterfaceRoot = noinline Exception.errorCallWithCallStackException
