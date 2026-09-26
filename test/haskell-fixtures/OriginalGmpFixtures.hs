-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalGmpFixtures (prepareOriginalGmp) where

import Prelude hiding (rem)
import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Distribution.InstalledPackageInfo as Package
import qualified Distribution.ModuleName as ModuleName
import FixtureSupport
import Foreign.C.Types (CLong)
import Foreign.Storable (sizeOf)
import System.Directory (createDirectoryIfMissing, doesDirectoryExist, doesFileExist, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (arch, os)
import Text.Read (readMaybe)

entries :: [(String, String)]
entries = [("originalAdd","__gmpn_add"),("originalAddWord","__gmpn_add_1"),
  ("originalCmp","__gmpn_cmp"),("originalDivWord","__gmpn_divrem_1"),
  ("originalModWord","__gmpn_mod_1"),("originalMul","__gmpn_mul"),
  ("originalMulWord","__gmpn_mul_1"),("originalSub","__gmpn_sub"),
  ("originalQuotRem","__gmpn_tdiv_qr"),("originalQuot","integer_gmp_mpn_tdiv_q"),
  ("originalRem","integer_gmp_mpn_tdiv_r"),
  ("originalRShift","integer_gmp_mpn_rshift"),("originalRShiftNegative","integer_gmp_mpn_rshift_2c"),
  ("originalGetDouble","integer_gmp_mpn_get_d"),("originalEncodeDouble","__int_encodeDouble")]

-- Native observations only. Kotlin owns arithmetic and raw-ABI expectations.
type Row = ((String,String,[Int],[Int],Int,Int,Int,Int,Int,Int,[Int],[Int]),
            (Int,[Int],[Int],[Int],[Int]))

rowJSON :: Row -> Value
rowJSON ((entry,alias,left,right,nl,nr,word,fractional,no,nrem,out,rem),
         (result,leftAfter,rightAfter,outAfter,remAfter)) = object
  ["entry" .= entry, "symbol" .= lookup entry entries, "alias" .= alias,
   "leftBefore" .= left, "rightBefore" .= right, "leftCount" .= nl, "rightCount" .= nr,
   "word" .= word, "fractional" .= fractional, "outputCount" .= no, "remainderCount" .= nrem,
   "outputBefore" .= out, "remainderBefore" .= rem, "result" .= result,
   "leftAfter" .= leftAfter, "rightAfter" .= rightAfter, "outputAfter" .= outAfter,
   "remainderAfter" .= remAfter]

prepareOriginalGmp :: FilePath -> Bool -> IO ()
prepareOriginalGmp root requireSupported = do
  unless (os == "linux" && arch == "x86_64" && sizeOf (0 :: Int) == 8 && sizeOf (0 :: CLong) == 8)
    (die "Original GMP fixture currently requires the verified Linux x86_64 LP64 host")
  let directory = "build/original-gmp"
      source = "compiler/test-fixtures/OriginalGmpAudit.hs"
      driver = "compiler/test-fixtures/OriginalGmpNative.hs"
      binary = directory </> "native/oracle"
      execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original GMP requires GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | Just host <- lookup "Host platform" fields,
                  "x86_64-" `isPrefixOf` host, lookup "Target platform" fields == Just host,
                  lookup "target word size" fields == Just "8" -> pure ()
    _ -> die "Original GMP rejects cross-compiling or non-64-bit GHC"
  -- The backend is hidden, although its declarations are exported. Shadow only
  -- its registration in a fresh test-local DB; retain the original unit, ABI,
  -- libraries, interfaces and dependencies. Never alter the installed DB.
  registration <- execute "original-registration" [] ghcPkg ["describe", "ghc-internal", "--expand-pkgroot"]
  original <- case Package.parseInstalledPackageInfo (commandStdout registration) of
    Left errors -> die (show errors)
    Right (_,package) -> pure package
  let exposedBackends = map ModuleName.fromString ["GHC.Internal.Bignum.Backend.GMP", "GHC.Internal.Bignum.Primitives"]
      exposed = Package.exposedModules original
      hidden = Package.hiddenModules original
      needExpose = filter (\backend -> all ((/= backend) . Package.exposedName) exposed) exposedBackends
  unless (all (\backend -> length (filter (== backend) hidden) == 1) needExpose)
    (die "Expected original GMP/primitive modules in installed registration")
  let visible = original { Package.exposedModules = exposed ++ map (\backend -> Package.ExposedModule backend Nothing) needExpose,
        Package.hiddenModules = filter (`notElem` needExpose) hidden }
      findDatabase index = do
        let candidate = directory </> "package-db-" ++ show index
        exists <- doesDirectoryExist (root </> candidate)
        if exists then findDatabase (index+1) else pure candidate
  database <- findDatabase (0 :: Int)
  let configuration = directory </> "exposed-ghc-internal.conf"
  writeFile (root </> configuration) (Package.showInstalledPackageInfo visible)
  initialized <- execute "package-init" [] ghcPkg ["init",root </> database]
  registered <- execute "package-register" [] ghcPkg ["--package-db",root </> database,"update",root </> configuration]
  let packageOptions = ["-package-db",root </> database,"-package","ghc-internal"]
  compiled <- execute "native-build" [] ghc (["--make", "-j2", "-O2", "-fforce-recomp",
    "-dcore-lint", "-dstg-lint", "-icompiler/test-fixtures",
    "-odir", root </> directory </> "native", "-hidir", root </> directory </> "native",
    driver, "-o", root </> binary] ++ packageOptions)
  observed <- execute "native-observations" [] (root </> binary) []
  rows <- maybe (die "Malformed original GMP observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe [Row])
  unless (length rows == 296 && sort (Map.keys (Map.fromList [(entry,()) |
      ((entry,_,_,_,_,_,_,_,_,_,_,_),_) <- rows])) == sort (map fst entries))
    (die "Unexpected original GMP observation inventory")
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON (map rowJSON rows))
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalGmpAudit.json",core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | (entry,_) <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (packageOptions ++ options ++ [source])
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine original GMP consumer: " ++ path))) modules
    audits <- forM entries $ \(entry,_) -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- runLoggedExpect (if requireSupported then 0 else 1) 180 root (directory </> "logs")
        (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py","--entry",entry,"--output",path] ++ modules)
      report <- either die pure . eitherDecodeStrict' =<< BS.readFile (root </> path)
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool requireSupported) -> pure ()
        _ -> die "Unexpected original GMP strict-audit status"
      pure (path,command)
    pure (stage,modules,exported,audits)
  compilerFiles <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let commands = [version,info,registration,initialized,registered,compiled,observed] ++ concat [exported:map snd audits | (_,_,exported,audits) <- stages]
      inputs = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/OriginalGmpFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json","compiler/build.sh","compiler/export.sh",
        "compiler/toolchain.sh","compiler/plugin.py","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["compiler/THC" </> path | path <- compilerFiles, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = [binary,oracle,configuration] ++ concat [modules ++ map fst audits | (_,modules,_,audits) <- stages] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"entries" .= map fst entries,
     "nativeRows" .= length rows,"limbEncoding" .= ("signed-64-bit-pattern" :: String),
     "stages" .= Map.fromList [(stage,object ["modules" .= modules]) | (stage,modules,_,_) <- stages],
     "strictAccepted" .= requireSupported,"runtimeVerified" .= False,"installedArtifactsHashed" .= False,
     "testPackageExposure" .= object ["modules" .= map ModuleName.toFilePath exposedBackends,"database" .= database,
       "registration" .= configuration,"sameUnitAndLibraries" .= True,"installedDatabaseModified" .= False],
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands]
  putStrLn "original-gmp: 296 native observations, fifteen original declarations, pre/post strict audit receipts"
