-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#))
import qualified PinnedAddressAudit as P

dispatch :: [String] -> IO ()
dispatch tokens = do
  let answer = case tokens of
        ["pinnedBytes", n, i, x] -> case (read n, read i, read x) of
          (I# a, I# b, I# c) -> I# (P.pinnedBytes a b c)
        ["alignedBytes", n, a, i, x] -> case (read n, read a, read i, read x) of
          (I# b, I# c, I# d, I# e) -> I# (P.alignedBytes b c d e)
        ["keepAliveWord8", x] -> case read x of I# a -> I# (P.keepAliveWord8 a)
        ["keepAliveLazy", x] -> case read x of I# a -> I# (P.keepAliveLazy a)
        ["fingerprintByte", x, y, i] -> case (read x, read y, read i) of
          (I# a, I# b, I# c) -> I# (P.fingerprintByte a b c)
        ["publicFingerprintByte", x, y, i] -> case (read x, read y, read i) of
          (I# a, I# b, I# c) -> I# (P.publicFingerprintByte a b c)
        ["publicFingerprintRoundtrip", x, y, i] -> case (read x, read y, read i) of
          (I# a, I# b, I# c) -> I# (P.publicFingerprintRoundtrip a b c)
        _ -> error "invalid pinned-address request"
  putStrLn (concatMap (++ "\t") tokens ++ show answer)

main :: IO ()
main = getContents >>= mapM_ (dispatch . words) . lines
