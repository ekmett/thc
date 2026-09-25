-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Control.Monad (forM_, unless, void, when)
import qualified Data.ByteString as BS
import Data.List (isPrefixOf, nub)
import Foreign.C.Error (Errno(..), getErrno)
import GHC.Exts (Int(I#))
import GHC.Internal.System.Posix.Internals (c_close)
import System.Environment (getArgs)
import System.IO (SeekMode(..))
import System.Posix.IO (OpenMode(..), OpenFileFlags(..), closeFd, defaultFileFlags, dup, dupTo, fdRead, fdSeek, fdWrite, openFd)
import System.Posix.Types (Fd(..))
import qualified OriginalPosixDupAudit as Original

number :: Fd -> Int
number (Fd raw) = fromIntegral raw

-- The fixture runner closes stdin. Fill any vacant standard descriptors before
-- opening test files, so lowest0 never closes the source it is meant to copy.
reserveStandardDescriptors :: IO ()
reserveStandardDescriptors = do
  fd <- openFd "/dev/null" ReadWrite defaultFileFlags
  if number fd < 3 then reserveStandardDescriptors else closeFd fd

run :: String -> Int -> Int -> Int
run entry (I# fd) (I# target) = I# (case entry of
  "originalDup" -> Original.originalDup fd
  "originalDupErrno" -> Original.originalDupErrno fd
  "originalDup2" -> Original.originalDup2 fd target
  "originalDup2Errno" -> Original.originalDup2Errno fd target
  _ -> error "unknown original dup entry")

main :: IO ()
main = do
  [entry, scenario, privatePath, otherPath, resultPath] <- getArgs
  reserveStandardDescriptors
  BS.writeFile privatePath (BS.pack [97..102])
  BS.writeFile otherPath (BS.pack [116,97,114,103,101,116])
  source <- openFd privatePath (if scenario == "append" then WriteOnly else ReadWrite)
    defaultFileFlags { append = scenario == "append" }
  other <- openFd otherPath ReadWrite defaultFileFlags
  void (fdSeek source AbsoluteSeek 2)
  void (fdSeek other AbsoluteSeek 1)
  target <- case scenario of
    "self" -> pure source
    "alias" -> dup source
    "bad-target" -> pure (Fd (-1))
    _ -> pure other
  backup <- if "lowest" `isPrefixOf` scenario then do
    let wanted = Fd (read (drop 6 scenario))
    saved <- dup wanted
    void (c_close (fromIntegral (number wanted)))
    pure (Just (wanted, saved))
    else pure Nothing
  when (scenario == "closed") (void (c_close (fromIntegral (number source))))
  let fd = if scenario == "invalid" then -1 else number source
      failed = scenario `elem` ["invalid", "closed", "bad-target"]
      two = "originalDup2" `isPrefixOf` entry
  void (c_close (-1)) -- Seed a genuine sticky EBADF before the original call.
  result <- evaluate (run entry fd (number target))
  Errno errno <- getErrno
  let alias = if two then target else Fd (fromIntegral result)
  unless (failed || result == number alias) (error "original duplication returned the wrong descriptor")
  forM_ backup $ \(wanted, _) -> unless (alias == wanted) (error "dup did not allocate the lowest free descriptor")
  when (scenario == "close-source") (void (c_close (fromIntegral (number source))))
  (position, byte) <- if failed then
    if two && scenario /= "bad-target" then do
      (value, count) <- fdRead target 1
      unless (count == 1) (error "invalid source damaged target")
      pos <- fdSeek target RelativeSeek 0
      pure (fromIntegral pos, fromEnum (head value))
    else pure (-1, -1)
    else if scenario == "append" then do
      void (fdSeek alias AbsoluteSeek 0)
      count <- fdWrite alias "Z"
      unless (count == 1) (error "short native append")
      pos <- fdSeek alias RelativeSeek 0
      pure (fromIntegral pos, -1)
    else do
      (value, count) <- fdRead alias 1
      unless (count == 1) (error "short native alias read")
      pos <- fdSeek alias RelativeSeek 0
      pure (fromIntegral pos, fromEnum (head value))
  forM_ backup $ \(wanted, saved) -> void (dupTo saved wanted)
  forM_ (nub ([source, other, target] ++ [alias | not failed] ++ maybe [] (\(_, saved) -> [saved]) backup)) $ \handle ->
    when (number handle >= 3) (void (c_close (fromIntegral (number handle))))
  contents <- map fromIntegral . BS.unpack <$> BS.readFile privatePath
  writeFile resultPath (show (result, fd, number target, position, byte, fromIntegral errno :: Int, contents :: [Int]) ++ "\n")
