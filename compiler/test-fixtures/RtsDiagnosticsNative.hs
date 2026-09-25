-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module Main (main) where

import qualified Data.ByteString as BS
import Foreign.C.String (CString, withCString)
import Foreign.Ptr (plusPtr)
import GHC.Internal.Conc.Sync (reportHeapOverflow, reportStackOverflow)
import System.Environment (getArgs, getProgName)

-- Exact GHC.Internal.Conc.Sync/TopHandler declaration. This independent ABI
-- oracle invokes the linked original cbits implementation, not a replacement.
foreign import ccall unsafe "HsBase.h errorBelch2"
  errorBelch :: CString -> CString -> IO ()

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["stack"] -> reportStackOverflow
    ["heap"] -> reportHeapOverflow
    [label] -> do
      let (bytes, offset) = case label of
            "ascii" -> ([97,108,112,104,97], 0)
            "empty" -> ([], 0)
            "bytes" -> ([88,89,255,128,37,10,0,90], 2)
            "nul" -> ([97,0,98], 0)
            "newline" -> ([97,10], 0)
            _ -> error "Unknown diagnostic oracle case"
      BS.useAsCString (BS.pack bytes) $ \message ->
        withCString "%s" $ \format -> errorBelch format (message `plusPtr` offset)
    _ -> error "Expected one diagnostic oracle case"
  getProgName >>= putStrLn
  putStrLn "returned"
