-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Original imports deliberately remain intact: no replacement FFI or formatter.
module OriginalStackAudit
  ( captureOriginal, decodeOriginal, renderOriginal, renderOriginalNames
  , peekOriginalInfoTable, lookupOriginalIPE, peekOriginalInfoProv
  ) where

import Foreign.Ptr (Ptr)
import Data.Maybe (mapMaybe)
import GHC.Internal.Heap.Closures (StackFrame)
import qualified GHC.Internal.Heap.InfoTable as Heap
import qualified GHC.Internal.InfoProv.Types as Ipe
import GHC.Internal.Stack.CloneStack (StackSnapshot, cloneMyStack)
import GHC.Internal.Stack.Decode (decodeStackWithIpe, prettyStackFrameWithIpe)

{-# NOINLINE captureOriginal #-}
captureOriginal :: IO StackSnapshot
captureOriginal = cloneMyStack

{-# NOINLINE decodeOriginal #-}
decodeOriginal :: StackSnapshot -> IO [(StackFrame, Maybe Ipe.InfoProv)]
decodeOriginal = decodeStackWithIpe

{-# NOINLINE renderOriginal #-}
renderOriginal :: (StackFrame, Maybe Ipe.InfoProv) -> Maybe String
renderOriginal = prettyStackFrameWithIpe

-- Keep the actual decoder and formatter together as one executable consumer.
-- This does not reinterpret a THC frame or replace either GHC function.
{-# NOINLINE renderOriginalNames #-}
renderOriginalNames :: StackSnapshot -> IO [String]
renderOriginalNames snapshot = mapMaybe prettyStackFrameWithIpe <$> decodeStackWithIpe snapshot

{-# NOINLINE peekOriginalInfoTable #-}
peekOriginalInfoTable :: Ptr Heap.StgInfoTable -> IO Heap.StgInfoTable
peekOriginalInfoTable = Heap.peekItbl

{-# NOINLINE lookupOriginalIPE #-}
lookupOriginalIPE :: Ptr Ipe.StgInfoTable -> IO (Maybe Ipe.InfoProv)
lookupOriginalIPE = Ipe.lookupIPE

{-# NOINLINE peekOriginalInfoProv #-}
peekOriginalInfoProv :: Ptr Ipe.InfoProvEnt -> IO Ipe.InfoProv
peekOriginalInfoProv = Ipe.peekInfoProv . Ipe.ipeProv
