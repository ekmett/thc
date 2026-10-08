-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, CPP, MagicHash #-}
#ifdef THC_EXTRA_FILE
{-# LANGUAGE TemplateHaskell #-}
#endif

-- |
-- Module      : ForeignImportStubs
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC C API FFI; declared native headers and libraries
--
-- Compiler fixture for foreign import stubs Core and metadata.
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
#ifdef THC_LABELS
import qualified Foreign.Ptr as Pointer
-- Deliberately unprovided: proving no generated C obligations must not grant
-- either address runtime admission or manufacture an executable call ABI.
#ifdef THC_CAPI_LABELS
#define THC_LABEL_CONVENTION capi
#else
#define THC_LABEL_CONVENTION ccall
#endif
#ifdef THC_LABEL_HEADER
foreign import THC_LABEL_CONVENTION "stdlib.h &thc_provenance_unknown_data" unknownData :: Pointer.Ptr CInt
foreign import THC_LABEL_CONVENTION "stdlib.h &thc_provenance_unknown_function" unknownFunction :: Pointer.FunPtr (CInt -> IO CInt)
#else
foreign import THC_LABEL_CONVENTION "&thc_provenance_unknown_data" unknownData :: Pointer.Ptr CInt
foreign import THC_LABEL_CONVENTION "&thc_provenance_unknown_function" unknownFunction :: Pointer.FunPtr (CInt -> IO CInt)
#endif
#ifdef THC_FINALIZER_LABEL
foreign import capi "stdlib.h &thc_provenance_unlinked_finalizer" pointerFinalizer :: Pointer.FunPtr (Pointer.Ptr () -> IO ())
foreign import ccall "&thc_provenance_unlinked_environment_finalizer" environmentFinalizer :: Pointer.FunPtr (Pointer.Ptr () -> Pointer.Ptr () -> IO ())
#endif
#endif
foreign import capi unsafe "stdlib.h abs" first :: CInt -> IO CInt
foreign import capi unsafe "stdlib.h abs" second :: CInt -> IO CInt
foreign import capi unsafe "stdio.h value SEEK_SET" seekSet :: CInt
foreign import ccall unsafe "stdlib.h labs" direct :: CLong -> IO CLong
probe :: Int# -> Int#
probe x = x +# 7#
