{-# LANGUAGE ForeignFunctionInterface #-}
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- |
-- Module      : THC.JsonIndex.Scanner
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : FFI; portable scalar C, optional x86 AVX2 and AArch64 NEON
--
-- Source-backed JSON structural masks. The scanner carries only the exact
-- quote/escape state across blocks; it does not validate JSON grammar or UTF-8.
-- No persistent input-sized interest bitmap is required. The C implementation
-- credits John Ky's succinctly and retains its MIT license separately.
module THC.JsonIndex.Scanner
  ( Backend (..)
  , LexerState (..)
  , BlockMasks (..)
  , backendAvailable
  , selectedBackend
  , scanBlock
  ) where

import Control.Monad (unless, when)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Internal as BSI
import qualified Data.ByteString.Unsafe as BSU
import Data.Word (Word8, Word32, Word64)
import Foreign.C.Types (CInt (..))
import Foreign.Marshal.Alloc (alloca)
import Foreign.Ptr (Ptr, castPtr, plusPtr)
import Foreign.Storable (peek)

-- | Explicit backends fail when unavailable. Automatic selects a supported ISA
-- without making the executable require that ISA on other machines.
data Backend = Automatic | Scalar | AVX2 | NEON
  deriving (Eq, Show, Enum, Bounded)

-- | State immediately before or after a source block. Escaped means the next
-- byte belongs to the string regardless of its value, then state becomes String.
data LexerState = InJson | InString | InEscape
  deriving (Eq, Show, Enum, Bounded)

-- | Little-endian masks, one bit per source byte, padded to whole 64-bit words.
-- The three slices share one owned allocation. Interest marks outside-string
-- @{}[],:@; open and close masks are disjoint subsets. Remaining interest bits
-- are delimiters. Unused padding bits are zero.
data BlockMasks = BlockMasks
  { finalState :: !LexerState
  , interestMask :: !BS.ByteString
  , openMask :: !BS.ByteString
  , closeMask :: !BS.ByteString
  } deriving (Eq, Show)

foreign import ccall unsafe "thc_json_backend_available"
  c_available :: CInt -> IO CInt
foreign import ccall unsafe "thc_json_backend_selected"
  c_selected :: CInt -> IO CInt
-- A bounded, callback-free 512-byte leaf; this does not hold a GHC capability
-- for a whole input file or retain either pointer after return.
foreign import ccall unsafe "thc_json_scan_block"
  c_scanBlock :: Ptr Word8 -> Word64 -> CInt -> Word32 -> Ptr Word32
              -> Ptr Word8 -> Ptr Word8 -> Ptr Word8 -> Word64 -> IO CInt

backendAvailable :: Backend -> IO Bool
backendAvailable backend = (/= 0) <$> c_available (fromIntegral (fromEnum backend))

selectedBackend :: Backend -> IO Backend
selectedBackend backend = do
  selected <- c_selected (fromIntegral (fromEnum backend))
  unless (selected >= 1 && selected <= 3) $
    ioError (userError ("JSON scanner backend unavailable: " ++ show backend))
  pure (toEnum (fromIntegral selected))

-- | Regenerate at most 512 source bytes. Incomplete strings are legitimate
-- block boundaries. An empty block preserves its incoming state. Input and
-- output are immutable owned bytes; no pointer or borrowed view escapes.
scanBlock :: Backend -> LexerState -> BS.ByteString -> IO BlockMasks
scanBlock backend initial source = do
  let count = BS.length source
      maskBytes = ((count + 63) `div` 64) * 8
  when (count > 512) $ ioError (userError "JSON scanner block exceeds 512 bytes")
  alloca $ \statePtr -> do
    bytes <- BSI.create (3 * maskBytes) $ \output ->
      -- C consumes the explicit length synchronously and retains no pointer;
      -- borrowing avoids useAsCString's unnecessary NUL-terminated copy.
      BSU.unsafeUseAsCString source $ \input -> do
        status <- c_scanBlock (castPtr input) (fromIntegral count)
          (fromIntegral (fromEnum backend)) (fromIntegral (fromEnum initial)) statePtr
          output (output `plusPtr` maskBytes) (output `plusPtr` (2 * maskBytes))
          (fromIntegral maskBytes)
        unless (status == 0) $ ioError (userError ("JSON scanner failed: status " ++ show status))
    state <- peek statePtr
    unless (state <= 2) $ ioError (userError "JSON scanner returned an invalid lexer state")
    pure BlockMasks
      { finalState = toEnum (fromIntegral state)
      , interestMask = BS.take maskBytes bytes
      , openMask = BS.take maskBytes (BS.drop maskBytes bytes)
      , closeMask = BS.drop (2 * maskBytes) bytes
      }
