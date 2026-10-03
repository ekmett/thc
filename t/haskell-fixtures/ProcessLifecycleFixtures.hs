-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (054 process-lifecycle)
-- Purpose: Check process launch/wait/status and signal policy across the THC host
--   boundary.
-- Produces/consumed result: Core fixtures and native process-oracle/sigchld-policy
--   executables.
-- Cost and overlap: Real child-process lifecycle needs integration coverage. Preparing
--   these controls for every unrelated Gradle Test is unjustified; depend on them only for
--   their consumers.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 054.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ProcessLifecycleFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for process lifecycle.
module ProcessLifecycleFixtures (prepareProcessLifecycle, main) where

import Control.Monad (forM, unless, void, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (nubBy, stripPrefix, sort)
import Data.Time.Clock (getCurrentTime)
import Data.Time.Format (defaultTimeLocale, formatTime)
import FixtureSupport
import GHC hiding (exprType)
import GHC.Plugins hiding ((<>))
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy)
import GHC.Iface.Binary
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import System.Directory (createDirectoryIfMissing, createDirectory, getCurrentDirectory,
  doesFileExist, doesDirectoryExist, listDirectory, pathIsSymbolicLink)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import qualified THC.Interface as Interface
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)

operations :: [(String, String, F.Safety)]
operations = [("processCreate", "runInteractiveProcess", F.PlayRisky),
  ("processPoll", "getProcessExitCode", F.PlayRisky),
  ("processWait", "waitForProcess", F.PlayInterruptible),
  ("processTerminate", "terminateProcess", F.PlayRisky)]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ symbolName (Just unit) True) F.CCallConv safety))
    | unitString unit == owner,
      any (\(_, symbol, expected) -> unpackFS symbolName == symbol && safety == expected) operations ->
        Just (unpackFS symbolName)
  _ -> Nothing

variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var value | isId value -> [value]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let bindings body -> concatMap (variables . snd) (flattenBinds [bindings]) ++ variables body
  Case value _ _ alternatives -> variables value ++ concat [variables body | Alt _ _ body <- alternatives]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

sourceFilesUnder :: FilePath -> FilePath -> IO [FilePath]
sourceFilesUnder root relative = do
  names <- sort <$> listDirectory (root </> relative)
  fmap concat $ forM names $ \name -> do
    let path = relative </> name
    symbolic <- pathIsSymbolicLink (root </> path)
    when symbolic (die ("Unexpected link in original process source: " ++ path))
    directory <- doesDirectoryExist (root </> path)
    if directory then sourceFilesUnder root path else pure [path]

prepareProcessLifecycle :: FilePath -> IO ()
prepareProcessLifecycle root = do
  let directory = "build/process-lifecycle/core"
      source = "t/fixtures/compiler/ProcessLifecycleAudit.hs"
      execute = runLogged 120 root (directory </> "logs")
      oneLine result = case BS.lines (commandStdout result) of
        [value] -> BS.unpack value
        _ -> error "Expected one compiler configuration line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Process fixtures require GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  -- Stock interfaces omit these internal workers. Rebuild only the pinned,
  -- unmodified original package in a private database, as the Unix fixtures do.
  let acquisition = "build/process-lifecycle/acquisition"
      archive = acquisition </> "process-1.6.26.1.tar.gz"
      archiveHash = "b431d2ba77607986fa84b42ff3021505b8637b8d638ff664be3292dd44aba8f0"
      project = acquisition </> "process.project"
      dist = acquisition </> "dist"
      packageDb = root </> dist </> "packagedb/ghc-9.14.1"
  createDirectoryIfMissing True (root </> acquisition)
  cached <- doesFileExist (root </> archive)
  unless cached $ void $ execute "source-download" [] "curl"
    ["--fail", "--location", "--retry", "2", "https://hackage.haskell.org/package/process-1.6.26.1/process-1.6.26.1.tar.gz", "-o", archive]
  digest <- hashFile (root </> archive)
  unless (digest == archiveHash) (die "Pinned process archive SHA256 mismatch")
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let sourceRun = acquisition </> "source-" ++ stamp
      packageSource = sourceRun </> "process-1.6.26.1"
  createDirectory (root </> sourceRun)
  extracted <- execute "source-extract" [] "tar" ["-xzf", archive, "-C", sourceRun]
  sourceFiles <- sourceFilesUnder root packageSource
  unless (all (\path -> packageSource </> path `elem` sourceFiles)
    ["process.cabal", "LICENSE", "System/Process.hs", "System/Process/Posix.hs", "cbits/posix/runProcess.c"])
    (die "Incomplete original process source inventory")
  sourceHashes <- hashes root sourceFiles
  writeFile (root </> project) $ unlines
    ["packages: " ++ show (root </> packageSource), "package process", "  shared: True",
     "  ghc-options: -fwrite-if-simplified-core -fexpose-all-unfoldings"]
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  built <- runLogged 600 root (directory </> "logs") "source-build" [] cabal
    ["build", "lib:process", "--offline", "-j2", "--allow-newer=process:base", "--project-file=" ++ project,
     "--builddir=" ++ root </> dist, "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ pkg]
  afterHashes <- hashes root sourceFiles
  unless (sourceHashes == afterHashes) (die "Process build changed original source files")
  let packageArgs = ["--package-db", packageDb, "--no-user-package-db"]
  owner <- execute "unit" [] pkg (packageArgs ++ ["field", "process", "id", "--simple-output"])
  case stripPrefix "process-1.6.26.1-" (oneLine owner) of
    Just suffix | suffix == "inplace" || not (null suffix) && all (`elem` ("0123456789abcdef" :: String)) suffix -> pure ()
    _ -> die "Wrong process package owner"
  imports <- execute "imports" [] pkg (packageArgs ++ ["field", "process", "import-dirs", "--simple-output"])
  let interfaces = [oneLine imports </> name | name <- ["System/Process.hi", "System/Process/Posix.hi"]]
      entries = [name | (name, _, _) <- operations]
  runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package-db", packageDb, "-package-id", oneLine owner, "-package", "ghc-internal", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    let symbol = originalSymbol (oneLine owner)
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == oneLine owner) (die "Wrong process interface owner")
      complete <- Interface.loadInterfaceCore env (mi_module raw) path >>= maybe
        (die "Original process fixture requires genuine retained Core") pure
      pure [value | (_, body) <- flattenBinds (Interface.interfaceBindings complete),
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing original process FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected process consumer module graph")
    desugared <- parseModule summary >>= typecheckModule >>= desugarModule
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var value | Just body <- lookup value bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        specialize name targetName = case ([(value, body) | (value, body) <- bindings,
            getOccString value == name, isExternalName (varName value)], [value | value <- originals, symbol value == Just targetName]) of
          ([(value, body)], [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                       eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original process FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName, _) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCoreCBD flags ["unit-qualified"] adapted >>= BS.writeFile (root </> directory </> "pre.cbd")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCoreCBD flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> directory </> "post.cbd")
  audits <- forM ["pre", "post"] $ \stage -> execute (stage ++ "-audit") [] "python3"
    (["bin/audit-core.py", directory </> stage ++ ".cbd", "--output", directory </> stage ++ ".audit.json"] ++
      concat [["--entry", "main:ProcessLifecycleAudit." ++ entryName] | entryName <- entries])
  inputHashes <- hashes root [source, "t/haskell-fixtures/ProcessLifecycleFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/audit-core.py", "bin/core_original_foreign.py", "bin/core-capabilities.json"]
  interfaceHashes <- hashes root interfaces
  writeJson (root </> directory </> "source.json") $ object
    ["archiveSha256" .= archiveHash, "sourceHashes" .= sourceHashes, "processUnit" .= oneLine owner]
  let commands = [version, library, extracted, built, owner, imports] ++ audits
  artifactHashes <- hashes root ((directory </> "source.json") :
    [directory </> stage ++ suffix | stage <- ["pre", "post"], suffix <- [".cbd", ".audit.json"]] ++ concatMap commandArtifacts commands)
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "processUnit" .= oneLine owner,
     "entries" .= entries, "consumerKind" .= ("typed consumers of genuine installed process FCallIds" :: String),
     "inputHashes" .= inputHashes, "interfaceHashes" .= interfaceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]

-- Also permits focused standalone preparation before shared dispatcher registration.
main :: IO ()
main = getCurrentDirectory >>= prepareProcessLifecycle
