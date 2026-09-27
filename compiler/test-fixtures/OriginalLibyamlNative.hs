-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC and the selected libyaml-0.1.4 package products
--
-- Independent native observations using the package's actual helper and C
-- library. These sizes and declarations are those of Text.Libyaml 0.1.4.
module Main (main) where

import Control.Exception (bracket, finally)
import Control.Monad (forM_, unless)
import qualified Data.ByteString as B
import qualified Data.ByteString.Char8 as B8
import Foreign
import Foreign.C.Types
import Numeric (showHex)
import System.Environment (getArgs)

foreign import ccall unsafe "yaml_parser_initialize" parserInitialize :: Ptr () -> IO CInt
foreign import ccall unsafe "yaml_parser_delete" parserDelete :: Ptr () -> IO ()
foreign import ccall unsafe "yaml_parser_set_input_string" parserInput :: Ptr () -> Ptr CUChar -> CULong -> IO ()
foreign import ccall unsafe "yaml_parser_parse" parserParse :: Ptr () -> Ptr () -> IO CInt
foreign import ccall unsafe "yaml_event_delete" eventDelete :: Ptr () -> IO ()
foreign import ccall unsafe "get_event_type" eventType :: Ptr () -> IO CInt
foreign import ccall unsafe "get_scalar_value" scalarValue :: Ptr () -> IO (Ptr CUChar)
foreign import ccall unsafe "get_scalar_length" scalarLength :: Ptr () -> IO CULong
foreign import ccall unsafe "yaml_emitter_initialize" emitterInitialize :: Ptr () -> IO CInt
foreign import ccall unsafe "yaml_emitter_delete" emitterDelete :: Ptr () -> IO ()
foreign import ccall unsafe "yaml_emitter_emit" emitterEmit :: Ptr () -> Ptr () -> IO CInt
foreign import ccall unsafe "buffer_init" bufferInitialize :: Ptr () -> IO ()
foreign import ccall unsafe "my_emitter_set_output" emitterOutput :: Ptr () -> Ptr () -> IO ()
foreign import ccall unsafe "get_buffer_buff" bufferData :: Ptr () -> IO (Ptr CUChar)
foreign import ccall unsafe "get_buffer_used" bufferUsed :: Ptr () -> IO CULong

main :: IO ()
main = do
  [inputPath] <- getArgs
  let input = B8.pack ("key: '" ++ replicate 6000 'a' ++ "'\nlist: [a, b, c]\n")
  B.writeFile inputPath input
  allocaBytes 16 $ \buffer -> forM_ [0,2147483647,2147483648,4294967295 :: CUInt] $ \value -> do
    pokeByteOff buffer 12 value
    actual <- bufferUsed buffer
    putStrLn ("unsigned-result\t" ++ show actual)
  forM_ [1..3 :: Int] $ \iteration -> withParser input $ \parser ->
    allocaBytes 104 $ \event -> do
      putStrLn ("parse\t" ++ show iteration)
      let loop = do
            success =<< parserParse parser event
            kind <- eventType event
            flip finally (eventDelete event) $ do
              bytes <- if kind /= 6 then pure B.empty else do
                pointer <- scalarValue event
                size <- scalarLength event
                B.packCStringLen (castPtr pointer, fromIntegral size)
              putStrLn (show kind ++ "\t" ++ show (B.length bytes) ++ "\t" ++ hex bytes)
            unless (kind == 2) loop
      loop
  withParser input $ \parser -> allocaBytes 104 $ \event ->
    bracket (mallocBytes 432) free $ \emitter -> do
      success =<< emitterInitialize emitter
      flip finally (emitterDelete emitter) $ allocaBytes 16 $ \buffer -> do
        bufferInitialize buffer
        flip finally (bufferData buffer >>= free) $ do
          emitterOutput emitter buffer
          let loop = do
                success =<< parserParse parser event
                kind <- eventType event
                -- yaml_emitter_emit consumes the event, including its scalar.
                success =<< emitterEmit emitter event
                unless (kind == 2) loop
          loop
          pointer <- bufferData buffer
          size <- bufferUsed buffer
          bytes <- B.packCStringLen (castPtr pointer, fromIntegral size)
          putStrLn ("encoded\t" ++ show (B.length bytes) ++ "\t" ++ hex bytes)

withParser :: B.ByteString -> (Ptr () -> IO a) -> IO a
withParser input body = bracket (mallocBytes 480) free $ \parser -> do
  success =<< parserInitialize parser
  flip finally (parserDelete parser) $ B.useAsCStringLen input $ \(pointer,size) -> do
    parserInput parser (castPtr pointer) (fromIntegral size)
    body parser

success :: CInt -> IO ()
success result = unless (result == 1) (fail "Original libyaml operation failed")

hex :: B.ByteString -> String
hex = concatMap (\byte -> let digits = showHex byte "" in if length digits == 1 then '0':digits else digits) . B.unpack
