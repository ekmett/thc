{-# LANGUAGE ForeignFunctionInterface #-}
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- |
-- Module      : THC.JsonIndex
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : FFI; portable scalar C, optional x86 AVX2 and AArch64 NEON
--
-- Source-backed JSON navigation sections. Interest bits are regenerated from
-- the immutable JSON bytes, not retained. Construction uses two native passes
-- with bounded scratch and writes final directory, lexer-state and topology
-- buffers directly. These records are navigation data, not JSON validation or
-- authorization to execute Core.
module THC.JsonIndex
  ( Layout (..)
  , Sections (..)
  , sectionLayout
  , buildSections
  , sidecarChunks
  , encodeSidecar
  , writeSidecar
  , validateSidecar
  ) where

import Control.Monad (unless)
import Data.Bits ((.|.), shiftL)
import qualified Crypto.Hash.SHA256 as SHA
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import qualified Data.ByteString.Builder as BB
import qualified Data.ByteString.Internal as BSI
import qualified Data.ByteString.Lazy as BL
import qualified Data.ByteString.Unsafe as BSU
import Data.Word (Word8, Word32, Word64)
import Foreign.C.Types (CInt (..))
import Foreign.Marshal.Alloc (alloca)
import Foreign.Ptr (Ptr, castPtr, plusPtr)
import Foreign.Storable (peek)
import System.IO (Handle)
import THC.JsonIndex.Scanner (Backend (..), LexerState (..), selectedBackend)

-- | Derived section sizes. Every section is padded to whole little-endian
-- 64-bit words. Calculations happen as Integer before checked Int conversion.
data Layout = Layout
  { epochBytes :: !Int
  , poppyBytes :: !Int
  , stateBytes :: !Int
  , topologyBytes :: !Int
  , payloadBytes :: !Int
  } deriving (Eq, Show)

-- | Four slices of one allocation. Poppy blocks contain interleaved Word32
-- relative prefixes and independent quarter populations at shifts 0,11,22.
-- Lexer states occupy two bits per 512 source bytes. Topology contains two
-- chronological bits per interest event: open=11, close=00, delimiter=01.
data Sections = Sections
  { sourceBytes :: !Word64
  , interestCount :: !Word64
  , endingState :: !LexerState
  , epochs :: !BS.ByteString
  , poppy :: !BS.ByteString
  , lexerStates :: !BS.ByteString
  , topology :: !BS.ByteString
  } deriving (Eq, Show)

sectionLayout :: Word64 -> Word64 -> Either String Layout
sectionLayout source events
  | events > source = Left "JSON index event count exceeds source bytes"
  | toInteger source > toInteger (maxBound :: Int) = Left "JSON source exceeds addressable memory"
  | total > toInteger (maxBound :: Int) = Left "JSON index sections exceed addressable memory"
  | otherwise = Right (Layout (n e) (n p) (n s) (n t) (n total))
  where
    u = toInteger source
    m = toInteger events
    wordsFor count per = ((count + per - 1) `div` per) * 8
    e = wordsFor u 4294967296
    p = wordsFor u 2048
    s = wordsFor u 16384
    t = wordsFor m 32
    total = e + p + s + t
    n = fromInteger

-- Whole-document operations must release the capability. Both calls borrow
-- immutable ByteString memory synchronously; C retains no pointers/callbacks.
foreign import ccall safe "thc_json_simple_count"
  c_count :: Ptr Word8 -> Word64 -> CInt -> Ptr Word64 -> Ptr Word32 -> IO CInt
foreign import ccall safe "thc_json_simple_build"
  c_build :: Ptr Word8 -> Word64 -> CInt -> Word64
          -> Ptr Word8 -> Word64 -> Ptr Word8 -> Word64
          -> Ptr Word8 -> Word64 -> Ptr Word8 -> Word64 -> IO CInt

buildSections :: Backend -> BS.ByteString -> IO Sections
buildSections requested source = do
  backend <- selectedBackend requested
  let sourceSize = fromIntegral (BS.length source)
      backendCode = fromIntegral (fromEnum backend)
      check status = unless (status == 0) $
        ioError (userError ("JSON index native builder failed: status " ++ show status))
  BSU.unsafeUseAsCString source $ \borrowed ->
    alloca $ \countPtr -> alloca $ \statePtr -> do
      let input = castPtr borrowed
      c_count input sourceSize backendCode countPtr statePtr >>= check
      events <- peek countPtr
      state <- peek statePtr
      unless (state <= 2) $ ioError (userError "JSON index invalid ending lexer state")
      layout <- either (ioError . userError) pure (sectionLayout sourceSize events)
      let e = epochBytes layout
          p = poppyBytes layout
          s = stateBytes layout
          t = topologyBytes layout
      bytes <- BSI.create (payloadBytes layout) $ \output ->
        c_build input sourceSize backendCode events
          output (fromIntegral e)
          (output `plusPtr` e) (fromIntegral p)
          (output `plusPtr` (e + p)) (fromIntegral s)
          (output `plusPtr` (e + p + s)) (fromIntegral t) >>= check
      pure Sections
        { sourceBytes = sourceSize
        , interestCount = events
        , endingState = toEnum (fromIntegral state)
        , epochs = BS.take e bytes
        , poppy = BS.take p (BS.drop e bytes)
        , lexerStates = BS.take s (BS.drop (e + p) bytes)
        , topology = BS.drop (e + p + s) bytes
        }

-- | The jointly versioned v2 envelope. JSON is not rewritten. The source digest
-- identifies the immutable bytes; the trailer detects sidecar corruption, not
-- authority. Readers must check the exact version before interpreting sections.
-- The list has a fixed six chunks regardless of document size.
sidecarChunks :: Backend -> BS.ByteString -> IO [BS.ByteString]
sidecarChunks backend source = do
  sections <- buildSections backend source
  let header = BL.toStrict $ BB.toLazyByteString $
        BB.byteString (BSC.pack "THCJSIX1") <> BB.word32LE 2 <> BB.word32LE 0 <>
        BB.word64LE (sourceBytes sections) <> BB.word64LE (interestCount sections) <>
        BB.byteString (SHA.hash source)
      body = [header, epochs sections, poppy sections, lexerStates sections, topology sections]
      digest = SHA.finalize (foldl' SHA.update SHA.init body)
  pure (body ++ [digest])

-- | Convenience for bounded goldens/transport. This copies the encoded sections
-- once; production file output should use 'writeSidecar'.
encodeSidecar :: Backend -> BS.ByteString -> IO BS.ByteString
encodeSidecar backend source = BS.concat <$> sidecarChunks backend source

-- | Write header, borrowed section slices and trailer directly without a second
-- whole-sidecar allocation. The caller owns the handle and immutable source.
writeSidecar :: Handle -> Backend -> BS.ByteString -> IO ()
writeSidecar output backend source = sidecarChunks backend source >>= mapM_ (BS.hPut output)

-- | Check an optional package sidecar's envelope against its exact JSON bytes.
-- This establishes version, length and digest binding, not JSON grammar or the
-- correctness of navigation records. A navigation reader must validate those
-- records before using them; the Core auditor still checks the source itself.
validateSidecar :: BS.ByteString -> BS.ByteString -> Either String ()
validateSidecar source index = do
  unless (BS.length index >= 96) (Left "truncated JSON index envelope")
  unless (BS.take 8 index == BSC.pack "THCJSIX1" && word 8 4 == 2 && word 12 4 == 0)
    (Left "unsupported JSON index format")
  let sourceSize = word 16 8
      events = word 24 8
  unless (sourceSize == fromIntegral (BS.length source)) (Left "JSON index source length mismatch")
  layout <- sectionLayout sourceSize events
  unless (toInteger (BS.length index) == 96 + toInteger (payloadBytes layout))
    (Left "JSON index section length mismatch")
  unless (BS.take 32 (BS.drop 32 index) == SHA.hash source)
    (Left "JSON index source digest mismatch")
  let bodySize = BS.length index - 32
  unless (BS.drop bodySize index == SHA.hash (BS.take bodySize index))
    (Left "JSON index trailer mismatch")
  where
    word :: Int -> Int -> Word64
    word offset width = foldl' (\value i -> value .|.
      (fromIntegral (BS.index index (offset + i)) `shiftL` (i * 8))) 0 [0 .. width - 1]
