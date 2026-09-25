-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InstalledCacheFixtures (checkInstalledCache) where

import Control.Exception (finally, IOException)
import qualified Control.Exception as Exception
import Control.Monad (forM, forM_, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.IORef (newIORef, modifyIORef', readIORef)
import Data.List (isPrefixOf, isSuffixOf)
import GHC hiding (entry)
import GHC.Plugins hiding ((<>), count)
import GHC.Iface.Binary (readBinIface, writeBinIface, CheckHiWay(..), TraceBinIFace(..), CompressionIFace(..))
import GHC.Iface.Syntax (IfaceBindingX(..), IfaceMaybeRhs(..), IfaceExpr(..))
import GHC.Unit.Module.Deps
import qualified GHC.Unit.Module.WholeCoreBindings as Foreign
import FixtureSupport (CommandResult(..), runLogged, writeJson)
import System.Directory (copyFile, createDirectoryIfMissing, doesDirectoryExist, getPermissions,
                         listDirectory, removeFile, removePathForcibly, setPermissions, executable, withCurrentDirectory)
import System.FilePath ((</>))
import qualified System.Info as Info
import qualified THC.Driver.Installed as Installed
import qualified THC.Driver.Project as Project

-- Two tiny genuine packages, with mutable -inplace IDs and a real imported
-- dependency. The counting wrapper observes production helper calls; no export
-- or probe is mocked. All destructive controls operate on these private files.
checkInstalledCache :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> String ->
                       IO ([CommandResult], [FilePath])
checkInstalledCache root directory ghc ghcPkg helper libdir baseUnit = withCurrentDirectory root $ do
  let relative = directory </> "cache-controls"
      work = root </> relative
      database = work </> "package.conf.d"
      dependency = "thc-cache-dependency-0.1-inplace"
      target = "thc-cache-root-0.1-inplace"
      trace = work </> "helper-calls"
      wrapper = work </> "helper"
      mutateAfterProbe = work </> "mutate-after-probe"
      replacement = work </> "replacement.dyn_hi"
      cache = work </> "cache"
      output name = work </> name
      source name = output name </> "source" </> name ++ ".hs"
      hi name = output name </> name ++ ".dyn_hi"
      run label program arguments = runLogged 180 root (relative </> "logs") label [] program arguments
      platform = Info.arch ++ "-" ++ if Info.os == "darwin" then "osx" else Info.os
      compiler = object ["id" .= ("ghc-9.14.1" :: String), "abi" .= ("cache-fixture" :: String),
        "platform" .= platform, "way" .= ("dynamic-nonprofiling" :: String)]
      check condition message = unless condition (fail message)
      count = length . filter (== "load") . BS.lines <$> BS.readFile trace
      shellQuote value = "'" ++ concatMap (\c -> if c == '\'' then "'\\''" else [c]) value ++ "'"
  createDirectoryIfMissing True work
  cacheExists <- doesDirectoryExist cache
  if cacheExists then removePathForcibly cache else pure ()
  exists <- doesDirectoryExist database
  initialized <- if exists then pure [] else (:[]) <$> run "init" ghcPkg ["init", database]
  builds <- forM [(dependency, "InterfaceCacheDependency", baseUnit),
                  (target, "InterfaceCacheRoot", baseUnit ++ " " ++ dependency)] $ \(identifier, name, dependencies) -> do
    createDirectoryIfMissing True (output name </> "source")
    copyFile (root </> "compiler/test-fixtures" </> name ++ ".hs") (source name)
    compiled <- run (name ++ "-compile") ghc
      (["-c", "-O0", "-g", "-dynamic-too", "-fforce-recomp", "-fwrite-if-simplified-core",
        "-i", "-this-unit-id", identifier, "-package-db", database, "-odir", output name, "-hidir", output name] ++
       concatMap (\identifier' -> ["-package-id", identifier']) (words dependencies) ++ [source name])
    let conf = work </> name ++ ".conf"
    let packageName = if identifier == dependency then "thc-cache-dependency" else "thc-cache-root"
    writeFile conf $ unlines ["name: " ++ packageName, "version: 0.1", "id: " ++ identifier,
      "key: " ++ identifier, "exposed: True", "exposed-modules: " ++ name,
      "import-dirs: " ++ show (output name), "depends: " ++ dependencies]
    registered <- run (name ++ "-register") ghcPkg ["--package-db", database, "update", conf]
    pure [compiled, registered]
  writeFile trace ""
  writeFile wrapper $ unlines ["#!/bin/sh", "case \" $* \" in",
    "*' --probe-inventory '*) printf 'probe\\n' >> " ++ shellQuote trace,
    shellQuote helper ++ " \"$@\"", "status=$?",
    "if test -f " ++ shellQuote mutateAfterProbe ++ "; then",
    "mv " ++ shellQuote replacement ++ " " ++ shellQuote (hi "InterfaceCacheRoot"),
    "rm " ++ shellQuote mutateAfterProbe, "fi", "exit $status;;",
    "*) printf 'load\\n' >> " ++ shellQuote trace ++ ";;", "esac",
    "exec " ++ shellQuote helper ++ " \"$@\""]
  permissions <- getPermissions wrapper
  setPermissions wrapper permissions {executable = True}
  context <- Installed.installedContext ghc ghcPkg wrapper [database] compiler
  unit <- Installed.discoverInstalled context target
  extraCommands <- newIORef []
  let acquireUnit selected = Project.prepareInstalledBundle cache (work </> "staging") (root </> "compiler/target-layout.c")
        "cache-fixture-driver" context selected
      acquire = acquireUnit unit
      loaded = do
        result <- acquire
        either (fail . show) pure result
      cold label = do
        before <- count
        result <- loaded
        after <- count
        check (after == before + 1) (label ++ " did not hydrate exactly the owning module")
        pure result
      warm label = do
        before <- count
        _ <- loaded
        after <- count
        check (after == before) (label ++ " unexpectedly hydrated/rendered Core")
      invalid label = do
        before <- count
        result <- Exception.try acquire :: IO (Either IOException (Either Installed.MissingCore Project.InstalledBundle))
        after <- count
        check (after == before + 1) (label ++ " reused an indexed bundle")
        check (case result of Left _ -> True; Right (Left _) -> True; _ -> False)
          (label ++ " was accepted")
  first <- cold "cold load"
  warm "unchanged warm load"
  let bundlePath = Project.bundlePath (Project.installedBundle first)
  bundleBytes <- BS.readFile bundlePath
  BS.writeFile bundlePath "corrupt ZIP"
  repaired <- cold "corrupt bundle"
  check (Project.bundleHash (Project.installedBundle repaired) == Project.bundleHash (Project.installedBundle first))
    "Corrupt bundle was not rebuilt exactly"
  indexFiles <- filter (isSuffixOf ".json") <$> listDirectory (cache </> "installed-probes/v1")
  indexFile <- case indexFiles of
    [found] -> pure found
    _ -> fail "Expected one successful probe index"
  BS.writeFile (cache </> "installed-probes/v1" </> indexFile) "corrupt index"
  _ <- cold "corrupt index"
  originalSource <- BS.readFile (source "InterfaceCacheRoot")
  (do BS.appendFile (source "InterfaceCacheRoot") "\n-- changed source text only\n"
      changed <- cold "source text"
      check (Project.bundleHash (Project.installedBundle changed) /= Project.bundleHash (Project.installedBundle first))
        "Source-note content was not refreshed") `finally` BS.writeFile (source "InterfaceCacheRoot") originalSource
  _ <- cold "restored source text"
  (removeFile (source "InterfaceCacheRoot") >> cold "missing source" >> pure ())
    `finally` BS.writeFile (source "InterfaceCacheRoot") originalSource
  _ <- cold "restored source availability"
  rawBytes <- BS.readFile (hi "InterfaceCacheRoot")
  (removeFile (hi "InterfaceCacheRoot") >> invalid "missing interface")
    `finally` BS.writeFile (hi "InterfaceCacheRoot") rawBytes
  (BS.writeFile (hi "InterfaceCacheRoot") "corrupt interface" >> invalid "corrupt interface")
    `finally` BS.writeFile (hi "InterfaceCacheRoot") rawBytes
  warm "restored interface"
  runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    initialEnv <- getSession
    (flags, _, _) <- parseDynamicFlags (hsc_logger initialEnv) initial (map noLoc ["-dynamic"])
    _ <- setSessionDynFlags flags
    environment <- getSession
    liftIO $ do
      let readRaw path = readBinIface (targetProfile (hsc_dflags environment)) (hsc_NC environment)
            CheckHiWay QuietBinIFace path
          writeRaw path = writeBinIface (targetProfile (hsc_dflags environment)) QuietBinIFace NormalCompression path
      original <- readRaw (hi "InterfaceCacheRoot")
      simplified <- maybe (fail "Cache fixture has no complete Core") pure (mi_simplified_core original)
      let altered = map changeBinding (mi_sc_extra_decls simplified)
          bodyChange = set_mi_simplified_core (Just simplified {mi_sc_extra_decls = map fst altered}) original
          foreignChange = set_mi_simplified_core (Just simplified {mi_sc_foreign =
            Foreign.IfaceForeign Nothing [Foreign.IfaceForeignFile LangC "int cache_probe_extra;\n" ".c"]}) original
          annotationChange = set_mi_anns [] original
      check (sum (map snd altered) > 0 && not (null (mi_anns original))) "Cache mutation controls are vacuous"
      (do writeRaw replacement bodyChange
          BS.writeFile mutateAfterProbe ""
          changed <- cold "input mutation after initial hit probe"
          check (Project.bundleHash (Project.installedBundle changed) /= Project.bundleHash (Project.installedBundle first))
            "Hit accepted payload bytes from before the input mutation")
        `finally` BS.writeFile (hi "InterfaceCacheRoot") rawBytes
      warm "restored after hit mutation"
      forM_ [("retained Core", bodyChange), ("foreign payload", foreignChange), ("annotations", annotationChange)] $ \(label, changed) -> do
        check (mi_iface_hash changed == mi_iface_hash original) "Control changed GHC's ordinary interface hash"
        (writeRaw (hi "InterfaceCacheRoot") changed >> cold label >> warm (label ++ " warm"))
          `finally` BS.writeFile (hi "InterfaceCacheRoot") rawBytes
        _ <- cold (label ++ " restored")
        pure ()
      dependencyBytes <- BS.readFile (hi "InterfaceCacheDependency")
      dependencyRaw <- readRaw (hi "InterfaceCacheDependency")
      dependencyCore <- maybe (fail "Dependency control lacks full Core") pure (mi_simplified_core dependencyRaw)
      let changedDependency = set_mi_simplified_core (Just dependencyCore {mi_sc_foreign =
            Foreign.IfaceForeign Nothing [Foreign.IfaceForeignFile LangC "int dependency_probe_extra;\n" ".c"]}) dependencyRaw
      (writeRaw (hi "InterfaceCacheDependency") changedDependency >> cold "mutable dependency" >> warm "dependency warm")
        `finally` BS.writeFile (hi "InterfaceCacheDependency") dependencyBytes
      _ <- cold "restored dependency"
      let staleRegistration label name alter = do
            let conf = work </> name ++ ".conf"
                update suffix bytes = do
                  BS.writeFile conf bytes
                  command <- run (label ++ suffix) ghcPkg ["--package-db", database, "update", conf]
                  modifyIORef' extraCommands (++ [command])
                ordinary suffix = do
                  selected <- Installed.discoverInstalled context target
                  before <- count
                  result <- acquireUnit selected
                  _ <- either (fail . show) pure result
                  after <- count
                  check (after == before + 1) (label ++ suffix ++ " reused incomplete dependency evidence")
            originalConf <- BS.readFile conf
            (do update "-register" (BS.pack (unlines (map alter (lines (BS.unpack originalConf)))))
                ordinary "-first"
                writeRaw (hi "InterfaceCacheDependency") changedDependency
                ordinary "-changed-dependency")
              `finally` (BS.writeFile (hi "InterfaceCacheDependency") dependencyBytes >> update "-restore" originalConf)
      staleRegistration "omitted-unit" "InterfaceCacheRoot" $ \configLine ->
        if "depends:" `isPrefixOf` configLine then "depends: " ++ baseUnit else configLine
      -- Retained Core still refers to the genuine dependency, but source-use
      -- metadata does not. Just usages is not a whole-Core provider inventory.
      selfRecomp <- maybe (fail "Provider control lacks recompilation usages") pure (mi_self_recomp_info original)
      let retainedProvider = set_mi_self_recomp (Just selfRecomp {mi_sr_usages = []}) $
            set_mi_deps noDependencies original
      check (mi_usages retainedProvider == Just []) "Provider control did not retain Just usages"
      (do writeRaw (hi "InterfaceCacheRoot") retainedProvider
          staleRegistration "omitted-module" "InterfaceCacheDependency" $ \configLine ->
            if "exposed-modules:" `isPrefixOf` configLine then "exposed-modules:" else configLine)
        `finally` BS.writeFile (hi "InterfaceCacheRoot") rawBytes
      (writeRaw (hi "InterfaceCacheRoot") (set_mi_simplified_core Nothing original) >> invalid "thin interface")
        `finally` BS.writeFile (hi "InterfaceCacheRoot") rawBytes
  warm "final unchanged load"
  finalBytes <- BS.readFile bundlePath
  check (finalBytes == bundleBytes) "Controls changed the original bundle"
  writeJson (work </> "facts.json") (object ["schema" .= (1 :: Int), "warmSkipsHydration" .= True,
    "sameInterfaceHashPayloadControls" .= (["core", "foreign", "annotations"] :: [String]),
    "mutableDependency" .= True, "sourceContentAndAvailability" .= True,
    "hitInputMutation" .= True, "incompleteRegistrationFallback" .= True,
    "retainedProviderWithPresentUsages" .= True,
    "corruptBundleAndIndex" .= True, "missingCorruptThinInterfaces" .= True,
    "compilerBinariesHashed" .= False])
  controls <- readIORef extraCommands
  let commands = initialized ++ concat builds ++ controls
  pure (commands, [relative </> "facts.json", relative </> "helper-calls"])

-- Change only a retained Int# literal, preserving all declarations and ordinary
-- GHC fingerprints. This directly exercises the bytes mi_iface_hash omits.
changeBinding :: IfaceBindingX IfaceMaybeRhs b -> (IfaceBindingX IfaceMaybeRhs b, Int)
changeBinding (IfaceNonRec binder rhs) = let (rhs', count) = changeRhs rhs in (IfaceNonRec binder rhs', count)
changeBinding (IfaceRec pairs) =
  let changed = [(binder, changeRhs rhs) | (binder, rhs) <- pairs]
  in (IfaceRec [(binder, rhs) | (binder, (rhs, _)) <- changed], sum [count | (_, (_, count)) <- changed])

changeRhs :: IfaceMaybeRhs -> (IfaceMaybeRhs, Int)
changeRhs (IfRhs expression) = let (expression', count) = changeExpr expression in (IfRhs expression', count)
changeRhs other = (other, 0)

changeExpr :: IfaceExpr -> (IfaceExpr, Int)
changeExpr (IfaceLit (LitNumber LitNumInt 7)) = (IfaceLit (LitNumber LitNumInt 8), 1)
changeExpr (IfaceApp fun argument) =
  let (fun', a) = changeExpr fun; (argument', b) = changeExpr argument
  in (IfaceApp fun' argument', a + b)
changeExpr (IfaceLam binder body) = let (body', count) = changeExpr body in (IfaceLam binder body', count)
changeExpr (IfaceTick tick body) = let (body', count) = changeExpr body in (IfaceTick tick body', count)
changeExpr other = (other, 0)
