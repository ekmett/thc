-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, CPP, MagicHash #-}
#ifdef THC_EXTRA_FILE
{-# LANGUAGE TemplateHaskell #-}
#endif
module ForeignImportStubs where
import Foreign.C.Types
import GHC.Exts (Int#, (+#))
#ifdef THC_EXTRA_FILE
import Language.Haskell.TH.Syntax (addForeignSource, ForeignSrcLang(LangC))
$(addForeignSource LangC "int thc_extra_import_product(void) { return 1; }\n" >> pure [])
#endif
#ifdef THC_WRAPPER
import Foreign.Ptr (FunPtr)
foreign import ccall "wrapper" callback :: (CInt -> IO CInt) -> IO (FunPtr (CInt -> IO CInt))
#endif
foreign import capi unsafe "stdlib.h abs" first :: CInt -> IO CInt
foreign import capi unsafe "stdlib.h abs" second :: CInt -> IO CInt
foreign import capi unsafe "stdio.h value SEEK_SET" seekSet :: CInt
foreign import ccall unsafe "stdlib.h labs" direct :: CLong -> IO CLong
probe :: Int# -> Int#
probe x = x +# 7#
