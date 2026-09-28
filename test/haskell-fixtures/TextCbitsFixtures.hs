-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : TextCbitsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for text cbits.
module TextCbitsFixtures (prepareTextCbits) where

import Control.Monad (forM_, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Char (isHexDigit)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import qualified Distribution.InstalledPackageInfo as Package
import qualified Distribution.ModuleName as ModuleName
import Distribution.Pretty (prettyShow)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesDirectoryExist)
import System.Environment (lookupEnv)
import System.FilePath ((</>))

prepareTextCbits :: FilePath -> IO ()
prepareTextCbits root = do
  let directory = "build/text-cbits"
      output = root </> directory
      source = "test/fixtures/compiler/TextCbitsAudit.hs"
      nativeSource = "test/fixtures/compiler/TextCbitsNative.hs"
      execute = runLogged 600 root (directory </> "logs")
      corpus = ["", "a", "abc\NULdef\n", "\x00e9\x03bb\x4e2d\x1f642",
        T.replicate 7 "x", T.replicate 16 "x", T.replicate 32 "x", T.replicate 64 "x",
        T.replicate 70 "\x00e9\x4e2d\x1f642", T.replicate 130 "ab\x00e9\n"]
      cases = [unwords [operation, hexBytes (prefix <> bytes <> BS.pack [254,255]),
          show (BS.length prefix), show (BS.length bytes), show count] |
        value <- corpus, let bytes = T.encodeUtf8 value, prefix <- [BS.empty, BS.pack [1,2,3]],
        (operation,counts) <- [("memchr",[0,10,65,97,127,128,195,255]),
          ("measure",[0,1,2,3,7,8,15,16,17,31,32,63,64,65,127,999,2^(64::Int)-1]), ("reverse",[0])],
        count <- counts :: [Integer]]
  createDirectoryIfMissing True output
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (BSC.lines (commandStdout version) == ["9.14.1"]) (fail "text cbits require GHC9.14.1")
  registration <- execute "original-registration" [] ghcPkg ["describe","text","--expand-pkgroot"]
  original <- case Package.parseInstalledPackageInfo (commandStdout registration) of
    Left errors -> fail (show errors)
    Right (_,package) -> pure package
  unless (prettyShow (Package.sourcePackageId original) == "text-2.1.3")
    (fail "Original text cbits require installed text 2.1.3")
  let installedUnit = prettyShow (Package.installedUnitId original)
  let names = map ModuleName.fromString ["Data.Text.Internal.Measure", "Data.Text.Internal.Reverse"]
      exposed = Package.exposedModules original
      hidden = Package.hiddenModules original
      needExpose = filter (\name -> all ((/= name) . Package.exposedName) exposed) names
      visible = original { Package.exposedModules = exposed ++ map (\name -> Package.ExposedModule name Nothing) needExpose,
        Package.hiddenModules = filter (`notElem` names) hidden }
      findDatabase index = do
        let path = output </> "package-db-" ++ show index
        exists <- doesDirectoryExist path
        if exists then findDatabase (index+1) else pure path
  unless (all (`elem` hidden) needExpose) (fail "original text Measure/Reverse module missing")
  database <- findDatabase (0::Int)
  writeFile (output </> "exposed-text.conf") (Package.showInstalledPackageInfo visible)
  _ <- execute "package-init" [] ghcPkg ["init",database]
  _ <- execute "package-register" [] ghcPkg ["--package-db",database,"update",output </> "exposed-text.conf"]
  let packageOptions = ["-package-db",database,"-package","text"]
  forM_ ["pre","post"] $ \stage -> do
    _ <- execute ("export-" ++ stage)
      [("THC_CORE_OUT",output </> stage ++ "-core"),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "bin/export-core.sh" (packageOptions ++ ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- execute "native-build" [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "test/fixtures/compiler","-odir",native,"-hidir",native,
    root </> nativeSource,"-o",native </> "text-cbits-oracle"] ++ packageOptions)
  writeFile (output </> "inputs.tsv") (unlines cases)
  oracle <- runLoggedWithInput (directory </> "inputs.tsv") 600 root (directory </> "logs")
    "native-oracle" [] (native </> "text-cbits-oracle") []
  let rows = BSC.lines (commandStdout oracle)
  unless (length rows == length cases && and (zipWith (\input row ->
    case splitTab (BSC.unpack row) of
      [originalInput,result] -> originalInput == input &&
        (if take 7 input == "reverse" then result == "-" || not (null result) && even (length result) && all isHexDigit result
         else readInteger result /= Nothing)
      _ -> False) cases rows))
    (fail "native text row inventory differs")
  BS.writeFile (output </> "oracle.tsv") (commandStdout oracle)
  forM_ ["pre","post"] $ \stage -> do
    _ <- execute ("audit-" ++ stage) [] "python3" ["bin/audit-core.py","--entry","textMemchr","--entry","textMeasure","--entry","textReverse",
      "--output",output </> stage ++ "-audit.json",output </> stage ++ "-core/TextCbitsAudit.json"]
    pure ()
  inputs <- hashes root [source,nativeSource,"test/haskell-fixtures/TextCbitsFixtures.hs",
    "bin/core_original_foreign.py","bin/audit-core.py","bin/core-capabilities.json",
    "nih/pinned/text-2.1.3/cbits/utils.c","nih/pinned/text-2.1.3/cbits/measure_off.c","nih/pinned/text-2.1.3/cbits/reverse.c",
    "nih/pinned/text-2.1.3/LICENSE","nih/pinned/openbsd-memchr-1.8.c",
    "src/main/c/text-api.c","bin/build-cbits.py"]
  artifacts <- hashes root [directory </> file | file <- ["inputs.tsv","oracle.tsv",
    "pre-core/TextCbitsAudit.json","post-core/TextCbitsAudit.json","pre-audit.json","post-audit.json","exposed-text.conf",
    "logs/original-registration.stdout","logs/native-oracle.command.json","logs/native-build.command.json",
    "native/text-cbits-oracle"]]
  writeJson (output </> "manifest.json") (object ["schema" .= (1::Int),"nativeRows" .= length cases,
    "unit" .= installedUnit,"inputHashes" .= inputs,"artifactHashes" .= artifacts])
  putStrLn ("text-cbits: " ++ show (length cases) ++ " original installed text native rows and strict pre/post Core")
