-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module Main (main) where
import Control.Monad (unless)
import qualified Data.Aeson as A
import qualified Data.ByteString as B
import Data.Word (Word64)
import AesonBenchmarks (decodedContentFingerprint)

fingerprint :: B.ByteString -> Maybe Word64
fingerprint input = decodedContentFingerprint <$> (A.decodeStrict input :: Maybe A.Value)

main :: IO ()
main = do
  let original = fingerprint "{\"a\":[1,{\"nested\":\"value\"}],\"b\":true}"
      reordered = fingerprint "{\"b\":true,\"a\":[1,{\"nested\":\"value\"}]}"
      changes =
        [ "{\"a\":[1,{\"nested\":\"other\"}],\"b\":true}"
        , "{\"a\":[2,{\"nested\":\"value\"}],\"b\":true}"
        , "{\"a\":[1,{\"nested\":\"value\"}],\"b\":false}"
        , "{\"a\":[{\"nested\":\"value\"},1],\"b\":true}"
        , "{\"a\":[1,{\"changed\":\"value\"}],\"b\":true}"
        ]
  unless (original /= Nothing && original == reordered) $
    error "content fingerprint depends on object member order"
  mapM_ (\input -> unless (fingerprint input /= original && fingerprint input /= Nothing) $
    error "content fingerprint missed a nested content change") changes
  unless (fingerprint "invalid" == Nothing) $
    error "invalid JSON accepted"
