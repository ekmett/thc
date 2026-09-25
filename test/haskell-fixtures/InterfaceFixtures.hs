-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InterfaceFixtures (prepareInterfaceCore) where

import Control.Monad (forM, forM_, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (isInfixOf, sort)
import Data.Maybe (isNothing)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import GHC hiding (exprType, entry)
import GHC.Plugins
import GHC.Core.TyCo.Compare (eqType)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.Iface.Syntax (IfaceBindingX(..))
import GHC.Types.TypeEnv (typeEnvIds)
import GHC.Unit.Module.ModDetails (md_types)
import System.Directory (copyFile, createDirectoryIfMissing, doesDirectoryExist, doesFileExist,
                         listDirectory, renameFile, withCurrentDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Interface

unitName :: String
unitName = "thc-interface-fixture-0.1"

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

prepareInterfaceCore :: FilePath -> IO ()
prepareInterfaceCore root = do
  let directory = "build/interface-core"
      generated = directory </> "source/InterfaceLibrary.hs"
      run label program args = runLogged 180 root (directory </> "logs") label [] program args
      expectedModule = mkModule (stringToUnit unitName) (mkModuleName "InterfaceLibrary")
      entries = ["opaqueEntry", "inlineEntry", "recursiveEntry"] :: [String]
  mapM_ (createDirectoryIfMissing True . (root </>))
    [directory </> name | name <- ["source", "full", "thin", "native", "no-source"]]
  copyFile (root </> "compiler/test-fixtures/InterfaceLibrary.hs") (root </> generated)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- run "ghc-version" ghc ["--numeric-version"]
  check (BS.words (commandStdout version) == ["9.14.1"]) "Interface fixture requires GHC 9.14.1"
  libdirResult <- run "libdir" ghc ["--print-libdir"]
  baseResult <- run "base-unit" ghcPkg ["field", "base", "id", "--simple-output"]
  baseUnit <- case BS.words (commandStdout baseResult) of
    [name] -> pure (BS.unpack name)
    _ -> die "Expected exactly one selected base unit"
  libdir <- case lines (BS.unpack (commandStdout libdirResult)) of
    [path] -> pure path
    _ -> die "Expected exactly one selected GHC libdir"
  builds <- forM ["full", "thin"] $ \mode -> do
    let output = directory </> mode
        database = root </> output </> "package.conf.d"
        conf = output </> "package.conf"
        complete = mode == "full"
    compiled <- run (mode ++ "-compile") ghc
      ["-c", "-O2", "-g", "-dynamic-too", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
       "-this-unit-id", unitName, if complete then "-fwrite-if-simplified-core" else "-fno-write-if-simplified-core",
       "-odir", output, "-hidir", output, generated]
    exists <- doesDirectoryExist database
    initialized <- if exists then pure [] else (:[]) <$> run (mode ++ "-init") ghcPkg ["init", database]
    writeFile (root </> conf) $ unlines
      ["name: thc-interface-fixture", "version: 0.1", "id: " ++ unitName,
       "key: " ++ unitName, "exposed: True", "exposed-modules: InterfaceLibrary",
       "import-dirs: " ++ show (root </> output), "depends: " ++ baseUnit]
    registered <- run (mode ++ "-register") ghcPkg ["--package-db", database, "update", root </> conf]
    pure (compiled : initialized ++ [registered])
  foreignBuild <- run "foreign-compile" ghc
    ["-c", "-O2", "-fforce-recomp", "-this-unit-id", unitName, "-fwrite-if-simplified-core",
     "-odir", directory </> "full", "-hidir", directory </> "full",
     "-stubdir", directory </> "full",
     "compiler/test-fixtures/InterfaceForeign.hs"]
  nativeBuild <- run "native-compile" ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-i",
     "-package-db", directory </> "full/package.conf.d", "-package-id", unitName,
     "-odir", directory </> "native", "-hidir", directory </> "native",
     "compiler/test-fixtures/InterfaceNative.hs", directory </> "full/InterfaceLibrary.o",
     "-o", directory </> "native/oracle"]
  oracle <- run "native-oracle" (root </> directory </> "native/oracle") []
  check (length (BS.lines (commandStdout oracle)) == 21) "Interface native oracle row count changed"
  -- Remove the compiled source target from its recorded path, preserving a
  -- copy as evidence. The loader gets only a package DB, .hi and expected ID.
  renameFile (root </> generated) (root </> directory </> "source/InterfaceLibrary.saved")
  sourcePresent <- doesFileExist (root </> generated)
  check (not sourcePresent) "Generated source is still present"
  withCurrentDirectory (root </> directory </> "no-source") $ do
    forM_ ["full", "thin"] $ \mode -> runGhc (Just libdir) $ do
      initial <- getSessionDynFlags
      initialEnv <- getSession
      (flags,leftovers,_) <- parseDynamicFlags (hsc_logger initialEnv) initial
        (map noLoc ["-package-db", root </> directory </> mode </> "package.conf.d", "-package-id", unitName])
      liftIO $ check (null leftovers) "Unexpected GHC flag leftovers"
      _ <- setSessionDynFlags (gopt_set flags Opt_IgnoreInterfacePragmas)
      environment <- getSession
      liftIO $ do
        let path = root </> directory </> mode </> "InterfaceLibrary.hi"
        loaded <- loadInterfaceCore environment expectedModule path
        check (gopt Opt_IgnoreInterfacePragmas (hsc_dflags environment)) "Caller flags were changed"
        check (debugLevel (hsc_dflags environment) == debugLevel flags) "Caller debug flags were changed"
        forM_ [mkModule (moduleUnit expectedModule) (mkModuleName "WrongModule"),
              mkModule (stringToUnit "wrong-unit") (moduleName expectedModule)] $ \wrong -> do
          result <- try (loadInterfaceCore environment wrong path)
          case result of
            Left (InterfaceModuleMismatch requested actual) ->
              check (requested == wrong && actual == expectedModule) "Identity diagnostic lost the units/modules"
            _ -> die "A mismatched interface identity was accepted"
        case loaded of
          Nothing -> check (mode == "thin") "Complete interface unexpectedly unavailable"
          Just core -> do
            check (mode == "full") "Thin interface unexpectedly yielded bodies"
            checkCore environment path core
            rendered <- interfaceCoreJSON ["source-notes", "unit-qualified"] core
            writeFile (root </> directory </> "InterfaceLibrary.json") rendered
            way <- try (loadInterfaceCore environment expectedModule
              (root </> directory </> "full/InterfaceLibrary.dyn_hi"))
            case way of
              Left (ProgramError message) -> check ("profile tag" `isInfixOf` message) "Wrong-way failure was unrelated"
              _ -> die "Wrong-way interface was accepted"
            foreignResult <- try (loadInterfaceCore environment
              (mkModule (moduleUnit expectedModule) (mkModuleName "InterfaceForeign"))
              (root </> directory </> "full/InterfaceForeign.hi"))
            case foreignResult of
              Left (UnsupportedInterfaceForeign _) -> pure ()
              _ -> die "Foreign stubs were silently accepted"
            again <- loadInterfaceCore environment expectedModule path
            case again of
              Just other -> do
                -- GHC allocates fresh uniques for interface-local binders;
                -- only external names are interned in the shared NameCache.
                let externalNames loadedCore = [varName v | (v,_) <- flattenBinds (interfaceBindings loadedCore),
                      isExternalName (varName v)]
                check (externalNames core == externalNames other) "External NameCache identities changed"
                checkCore environment path other
              Nothing -> die "Repeat interface load lost its complete payload"
    pure ()
  audits <- forM entries $ \entry -> run ("audit-" ++ entry) "python3"
    ["scripts/audit-core.py", "--entry", entry, "--output", directory </> entry ++ "-audit.json",
     directory </> "InterfaceLibrary.json"]
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ ["compiler/test-fixtures/InterfaceLibrary.hs", "compiler/test-fixtures/InterfaceNative.hs",
        "compiler/test-fixtures/InterfaceForeign.hs", "test/haskell-fixtures/InterfaceFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project",
        "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [version, libdirResult, baseResult] ++ concat builds ++ [foreignBuild, nativeBuild, oracle] ++ audits
      artifacts = concatMap commandArtifacts commands ++
        [directory </> name | name <- ["InterfaceLibrary.json", "full/InterfaceLibrary.hi", "thin/InterfaceLibrary.hi",
          "full/InterfaceLibrary.dyn_hi", "full/InterfaceForeign.hi", "native/oracle", "source/InterfaceLibrary.saved"]] ++
        [directory </> entry ++ "-audit.json" | entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "unit" .= unitName, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "controls" .= (["opaque-body", "private-worker", "recursive-groups", "thin-unavailable",
       "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-rejected", "private-flags", "repeat-load"] :: [String]),
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared complete interface Core: 21 native rows; full/thin/no-source/identity/way/foreign controls passed"

checkCore :: HscEnv -> FilePath -> InterfaceCore -> IO ()
checkCore environment path core = do
  let flat = flattenBinds (interfaceBindings core)
      named name = [(v,rhs) | (v,rhs) <- flat, occNameString (getOccName v) == name]
      ordinary = [v | v <- typeEnvIds (md_types (interfaceDetails core)), occNameString (getOccName v) == "opaqueEntry"]
      inlineIds = [v | v <- typeEnvIds (md_types (interfaceDetails core)), occNameString (getOccName v) == "inlineEntry"]
  check (length ordinary == 1 && all (isNothing . maybeUnfoldingTemplate . realIdUnfolding) ordinary)
    "OPAQUE control unexpectedly has an ordinary executable unfolding"
  -- GHC 9.14 disables emitting IfUseUnfoldingRhs (#22807), but the ordinary
  -- declaration must still retain its actual pragma in our private flags.
  check (length inlineIds == 1 && all (not . isNothing . maybeUnfoldingTemplate . realIdUnfolding) inlineIds)
    "Private hydration discarded interface pragmas"
  check (all (\(v,rhs) -> eqType (idType v) (exprType rhs)) flat) "Recovered Core type mismatch"
  case (named "opaqueEntry", named "privateWorker") of
    ([(_,rhs)],[(worker,_)]) -> check (refers worker rhs) "Original private worker reference was not preserved"
    _ -> die "Full Core lost the opaque entry or private worker"
  raw <- readBinIface (targetProfile (hsc_dflags environment)) (hsc_NC environment) CheckHiWay QuietBinIFace path
  case mi_simplified_core raw of
    Nothing -> die "Complete payload disappeared"
    Just simplified -> do
      let groups = mi_sc_extra_decls simplified
          rawRec IfaceRec{} = True
          rawRec _ = False
          coreRec Rec{} = True
          coreRec _ = False
      check (any rawRec groups && map rawRec groups == map coreRec (interfaceBindings core))
        "Original recursive groups were changed"
  where
    refers worker (Var v) = v == worker
    refers worker (App f x) = refers worker f || refers worker x
    refers worker (Lam _ x) = refers worker x
    refers worker (Let b x) = any (refers worker . snd) (flattenBinds [b]) || refers worker x
    refers worker (Case x _ _ as) = refers worker x || any (\(Alt _ _ rhs) -> refers worker rhs) as
    refers worker (Cast x _) = refers worker x
    refers worker (Tick _ x) = refers worker x
    refers _ _ = False
