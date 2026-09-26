-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module GraphFixtures (prepareGraph) where

import Control.Exception (try)
import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import Data.Time.Clock (getCurrentTime)
import Data.Time.Format (defaultTimeLocale, formatTime)
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath
import qualified THC.Driver.Installed as Installed

entries :: [String]
entries = ["graphChecksum", "graphReachable", "graphDistanceTotal", "graphDistanceAt", "graphControl"]

requests :: [(String, Integer)]
requests = [(entry, n) | entry <- take 3 entries,
  n <- [-2^(63::Int),-17,-1] ++ [0..20] ++ [31,32,63,64,127,128,255,256,257,511,512,513,2^(63::Int)-1]] ++
  [("graphDistanceAt", n*1024 + i) | n <- [0..12] ++ [31,64,127,256,512],
    i <- if n <= 12 then [0..n+1] else [0,1,n `div` 2,n-n `div` 4-1,n-n `div` 4,n-1,n,n+1]] ++
  [("graphControl", test*32 + column) | test <- [0..9], column <- [0..19]]

archivePath, archiveHash, archiveUrl :: String
archivePath = "vendor/archives/containers-0.8.tar.gz"
archiveHash = "b1c1127ff57b6f844d0b30cea54a62c01ca146a49ed4953485be1af389a94bd8"
archiveUrl = "https://hackage.haskell.org/package/containers-0.8/containers-0.8.tar.gz"

filesUnder :: FilePath -> FilePath -> IO [FilePath]
filesUnder root relative = do
  children <- sort <$> listDirectory (root </> relative)
  fmap concat $ forM children $ \name -> do
    let path = relative </> name
    symbolic <- pathIsSymbolicLink (root </> path)
    when symbolic (die ("Unexpected symbolic link in graph source/archive: " ++ path))
    directory <- doesDirectoryExist (root </> path)
    if directory then filesUnder root path else pure [path]

prepareGraph :: FilePath -> IO ()
prepareGraph root = do
  let directory = "build/graph-bfs"
      manifest = root </> directory </> "manifest.json"
      source = "examples/THC/GraphWorkload.hs"
      logs = directory </> "logs"
      execute = runLogged 600 root logs
      commandFiles label = [logs </> label <.> suffix | suffix <- ["stdout","stderr","command.json"]]
  createDirectoryIfMissing True (root </> directory)
  exists <- doesFileExist manifest
  when exists (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Graph proof requires GHC 9.14.1")
  containers <- execute "containers-version" [] ghcPkg ["field","containers","version","--simple-output"]
  unless (commandStdout containers == "0.8\n") (die "Graph proof requires containers-0.8")
  createDirectoryIfMissing True (root </> takeDirectory archivePath)
  cached <- doesFileExist (root </> archivePath)
  unless cached $ do
    _ <- execute "containers-download" [] "curl" ["--fail","--location","--retry","2",archiveUrl,"-o",archivePath]
    pure ()
  digest <- hashFile (root </> archivePath)
  unless (digest == archiveHash) (die "Pinned containers archive SHA256 mismatch")
  -- Extract only the already hash-verified upstream archive into a fresh owned
  -- directory. Both native and Core builds use these same unmodified sources.
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let runDir = directory </> "run-" ++ stamp
      vendor = runDir </> "containers-0.8"
      native = runDir </> "native"
      binary = native </> "library-oracle"
      include = ["-i" ++ root </> vendor </> "src", "-I" ++ root </> vendor </> "include"]
  createDirectory (root </> runDir)
  _ <- execute "containers-extract" [] "tar" ["-xzf",archivePath,"-C",runDir]
  containerFiles <- filesUnder root vendor
  containerHashes <- hashes root containerFiles
  unless (vendor </> "LICENSE" `elem` containerFiles && vendor </> "src/Data/Sequence/Internal.hs" `elem` containerFiles)
    (die "Incomplete containers source inventory")
  let inputs = directory </> "inputs.tsv"
  writeFile (root </> inputs) (unlines [entry ++ "\t" ++ show n | (entry,n) <- requests])
  createDirectoryIfMissing True (root </> native)
  _ <- execute "native-build" [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-iexamples","-odir",native,"-hidir",native,"examples/LibraryOracle.hs","-o",binary] ++ include)
  observed <- runLoggedWithInput inputs 120 root logs "native-oracle" [] (root </> binary) ["--batch"]
  let parse row = case splitTab row of
        [entry,n,result] -> do argument <- readInteger n; value <- readInteger result; pure (entry,argument,value)
        _ -> Nothing
  rows <- maybe (die "Malformed graph native oracle") pure (traverse parse (lines (BSC.unpack (commandStdout observed))))
  unless ([(entry,n) | (entry,n,_) <- rows] == requests && BS.null (commandStderr observed))
    (die "Graph oracle changed its requested input domain")
  BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
  _ <- execute "boot-export" [("THC_SOURCE_NOTES","false")] "python3" ["compiler/export-boot.py","--build-dir",directory </> "boot"]
  boot <- readJson (root </> directory </> "boot/boot-provenance.json")
  bootSources <- field boot "sources" :: IO [Value]
  bootSourcePaths <- mapM (\value -> field value "path") bootSources
  let bootModules = [directory </> "boot/core" </> name <.> "json" |
        name <- ["GHC.Internal.CString","GHC.Internal.Err","GHC.InterfaceClosure"]]
  -- The ordinary thin unfolding of reverse references its private reverse1;
  -- lookup has no executable unfolding. Acquire both complete original modules,
  -- including the real Eq dictionary, rather than replacing library functions
  -- or combining overlapping complete modules and thin fragments.
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  let selection = ["exe:thc-interface","--offline","--with-compiler=" ++ ghc,"--with-hc-pkg=" ++ ghcPkg]
  _ <- execute "interface-helper-build" [] cabal ("build" : "-j4" : selection)
  located <- execute "interface-helper-location" [] cabal ("list-bin" : selection)
  helper <- case lines (BSC.unpack (commandStdout located)) of
    [path] -> pure path
    _ -> die "Expected one original-interface reader"
  registered <- execute "internal-unit" [] ghcPkg ["--global","--no-user-package-db","field","ghc-internal","id","--simple-output"]
  unitId <- case BSC.words (commandStdout registered) of
    [name] -> pure (BSC.unpack name)
    _ -> die "Expected one original ghc-internal registration"
  context <- Installed.installedContext ghc ghcPkg helper [] (object
    ["id" .= ("ghc-9.14.1"::String),"way" .= ("dynamic"::String)])
  unit <- Installed.discoverInstalled context unitId
  before <- Installed.probeInstalled context unit
  let interfaceProvenance = runDir </> "interfaces/provenance.json"
  createDirectoryIfMissing True (root </> runDir </> "interfaces")
  writeJson (root </> interfaceProvenance) before
  originals <- forM ["GHC.Internal.Classes","GHC.Internal.List"] $ \name -> do
    path <- maybe (die ("Missing original installed interface: " ++ name)) pure
      (lookup name (Installed.installedInterfaces unit))
    let label = "interface-" ++ name
        destination = runDir </> "interfaces" </> name <.> "json"
        interfaceCopy = runDir </> "interfaces" </> name <.> "dyn_hi"
    _ <- execute label [] helper (Installed.helperCommand context unit (name,path))
    response <- readJson (root </> logs </> label <.> "stdout")
    status <- field response "status" :: IO String
    original <- field response "core" :: IO Value
    schema <- field original "schema" :: IO Int
    owner <- field original "unit" :: IO String
    originalName <- field original "module" :: IO String
    boundary <- field original "boundary" :: IO String
    unless (status == "loaded" && schema == 1 && owner == "ghc-internal" && originalName == name &&
      boundary == "optimized-Core-after-Tidy-before-CorePrep")
      (die ("Not complete original executable Core: " ++ name))
    writeJson (root </> destination) original
    interfaceHash <- hashFile path
    copyFile path (root </> interfaceCopy)
    copiedHash <- hashFile (root </> interfaceCopy)
    unless (copiedHash == interfaceHash) (die "Original graph interface changed while copying")
    pure (destination,interfaceCopy,commandFiles label,object
      ["module" .= name,"interface" .= path,"copy" .= interfaceCopy,"sha256" .= interfaceHash])
  after <- Installed.probeInstalled context unit
  unless (before == after) (die "Installed interface inventory changed during graph acquisition")
  stages <- forM ["post"] $ \stage -> do
    let core = runDir </> stage </> "core"
        exportLabel = stage ++ "-export"
        closurePath = core </> "THC.InterfaceClosure.json"
        moduleList = directory </> stage ++ "-modules.txt"
    _ <- execute exportLabel [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> runDir </> stage </> "ghc"),
      ("THC_SOURCE_NOTES","false")]
      "compiler/export.sh" (include ++ ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ [source])
    closure <- readJson (root </> closurePath)
    modules <- field closure "sourceModules" :: IO [String]
    unless (all ("main:" `isPrefixOf`) modules && "main:THC.GraphWorkload" `elem` modules)
      (die "Graph export changed its source-module unit policy")
    fragments <- field closure "bindings" :: IO [Value]
    fragmentIds <- mapM (\value -> field value "id") fragments :: IO [String]
    unless (all (\name -> any (`isPrefixOf` name) ["ghc-internal:GHC.Internal.Classes.","ghc-internal:GHC.Internal.List."]) fragmentIds)
      (die "Graph requires another actual interface provider; inspect the retained closure")
    let paths = [core </> drop 5 name <.> "json" | name <- modules] ++
          [path | (path,_,_,_) <- originals] ++ bootModules
    writeFile (root </> moduleList) (unlines [makeRelative directory path | path <- paths])
    audits <- forM entries $ \entry -> do
      let label = stage ++ "-" ++ entry ++ "-audit"
          reportPath = directory </> label <.> "json"
      attempted <- try (execute label [] "python3" ["scripts/audit-core.py","--module-list",moduleList,
        "--entry",entry,"--output",reportPath]) :: IO (Either ExitCode CommandResult)
      -- Preserve real strict-rejection reports without converting their exit
      -- status to success. A failed producer still leaves the complete frontier.
      command <- readJson (root </> logs </> label <.> "command.json")
      status <- field command "exit" :: IO Int
      unless (status `elem` [0,1]) (die ("Graph auditor execution failed: " ++ label))
      report <- readJson (root </> reportPath)
      accepted <- field report "accepted" :: IO Bool
      roots <- field report "roots" :: IO [String]
      unless (accepted == (status == 0) && roots == ["main:THC.GraphWorkload." ++ entry])
        (die ("Contradictory graph audit: " ++ label))
      case attempted of
        Left ExitSuccess -> die "Unexpected successful ExitCode exception"
        Left (ExitFailure _) -> unless (status == 1) (die "Graph auditor process failed before rejection report")
        Right _ -> unless accepted (die "Graph auditor accepted a rejected report")
      pure (entry,reportPath,accepted,commandFiles label)
    let reports = Map.fromList [(entry,path) | (entry,path,_,_) <- audits]
        accepted = all (\(_,_,ok,_) -> ok) audits
    pure (object ["stage" .= stage,"modules" .= paths,"moduleList" .= moduleList,"audits" .= reports,
                  "strictAccepted" .= accepted],accepted,
      paths ++ [moduleList,closurePath] ++ commandFiles exportLabel ++ concat [[path] ++ commands | (_,path,_,commands) <- audits])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = [source,"examples/LibraryOracle.hs","thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/GraphFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/InstalledCoreFixtures.hs","compiler/export.sh","compiler/build.sh",
        "compiler/toolchain.sh","compiler/plugin.py","compiler/export-boot.py","compiler/package-roots/InterfaceRoots.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "src/THC/Driver/Installed.hs","compiler/interface/Main.hs"] ++
        ["examples/THC" </> name <.> "hs" | name <- ["SetWorkload","IntMapWorkload","IntMapPrimops","IntSetWorkload","IntSetPrimops","SequenceWorkload"]] ++
        ["compiler/THC" </> name | name <- plugin,takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts,"core_" `isPrefixOf` name,takeExtension name == ".py"] ++ bootSourcePaths
      labels = ["ghc-version","containers-version","containers-extract","native-build","native-oracle","boot-export",
        "interface-helper-build","interface-helper-location","internal-unit"] ++
        ["containers-download" | not cached]
      accepted = all (\(_,ok,_) -> ok) stages
  inputHashes <- hashes root (sort sources)
  artifactHashes <- hashes root (sort $ [archivePath,inputs,directory </> "oracle.tsv",binary,directory </> "boot/boot-provenance.json"] ++
    concatMap commandFiles labels ++ concat [paths | (_,_,paths) <- stages] ++ containerFiles ++
    [interfaceProvenance,makeRelative root helper] ++ concat [[path,interfaceCopy] ++ commands | (path,interfaceCopy,commands,_) <- originals])
  writeJson manifest $ object ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),
    "entries" .= entries,"nativeRows" .= length rows,"strictAccepted" .= accepted,
    "stages" .= [record | (record,_,_) <- stages],"inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
    "originalInterfaces" .= object ["provenance" .= interfaceProvenance,
      "modules" .= [path | (path,_,_,_) <- originals],"sources" .= [record | (_,_,_,record) <- originals],
      "sourcePatches" .= ([]::[String])],
    "containers" .= object ["url" .= archiveUrl,"sha256" .= archiveHash,"sourcePatches" .= ([]::[String]),
      "root" .= vendor,"sourceNotes" .= False,"sourceHashes" .= containerHashes],"bootProvenance" .= boot]
  unless accepted (die "Graph BFS retains a strict closure frontier: inspect build/graph-bfs/*-audit.json; no runtime support claim")
  putStrLn ("graph-bfs: " ++ show (length rows) ++ " native rows; five strict post-Tidy entry audits")
