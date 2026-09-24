{-# LANGUAGE ForeignFunctionInterface, InterruptibleFFI #-}
module ForeignCallAudit where

import Foreign.C.Types (CInt(..))
import Foreign.Ptr (Ptr, FunPtr)
import Data.Word (Word8)

-- Compiler-only declarations: do not execute or link the negative controls.
-- These three machine signatures match GHC.Internal.Fingerprint's imports;
-- they do not replace any Haskell Fingerprint body or implement a host ABI.
foreign import ccall unsafe "__hsbase_MD5Init"
  md5Init :: Ptr Word8 -> IO ()
foreign import ccall unsafe "__hsbase_MD5Update"
  md5Update :: Ptr Word8 -> Ptr Word8 -> CInt -> IO ()
foreign import ccall unsafe "__hsbase_MD5Final"
  md5Final :: Ptr Word8 -> Ptr Word8 -> IO ()

-- Same symbol with different GHC call contracts must stay distinguishable.
foreign import ccall safe "__hsbase_MD5Init"
  safeInit :: Ptr Word8 -> IO ()
foreign import ccall interruptible "__hsbase_MD5Init"
  interruptibleInit :: Ptr Word8 -> IO ()
foreign import ccall unsafe "__hsbase_MD5Init"
  wrongSignatureInit :: Ptr Word8 -> CInt -> IO ()
foreign import ccall unsafe "dynamic"
  dynamicInit :: FunPtr (Ptr Word8 -> IO ()) -> Ptr Word8 -> IO ()
foreign import ccall unsafe "__thc_unsupported_foreign"
  unknownCall :: Double -> IO Double

-- An ordinary Haskell occurrence resembling a target is not an FCallId.
__hsbase_MD5Init :: Int -> Int
__hsbase_MD5Init x = x + 1
