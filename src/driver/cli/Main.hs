-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Cabal API and host filesystem/process services
--
-- Command-line entry point for Cabal planning, Core acquisition and guest execution.
module Main (main) where

import Control.Monad (filterM, unless, void, when)
import Data.Either (fromRight)
import Data.List (isPrefixOf, nub, sort)
import Data.Maybe (catMaybes, fromMaybe, isNothing)
import Distribution.Simple.Utils (topHandler)
import Distribution.Types.Flag (mkFlagName)
import System.Console.GetOpt
import System.Directory (doesFileExist, executable, findExecutable, getCurrentDirectory, getPermissions, listDirectory, makeAbsolute)
import System.Environment (getArgs, lookupEnv)
import System.Exit (ExitCode(..), die, exitWith)
import System.FilePath ((</>), dropExtension, splitSearchPath, takeExtension)
import System.IO (hSetEncoding, stderr, stdout, utf8)
import System.IO.Error (tryIOError)
import qualified System.Info as Host
import System.Process (rawSystem, readProcessWithExitCode)
import Text.Read (readMaybe)
import THC.Driver.Cabal
import THC.Driver.GhcProxy (runGhcProxy)
import THC.Driver.Admission (observeBuildAdmission)
import THC.Driver.Json (renderJson)
import THC.Driver.Project (runProject, acquireProject, buildTargetsProject, exportInstalledUnit)
import THC.Driver.Installed (installedCompilerIdentity, installedContext)
import THC.Driver.Run

main :: IO ()
main = topHandler $ do
  hSetEncoding stdout utf8
  hSetEncoding stderr utf8
  args <- getArgs
  case args of
    ["--bash-completion-script"] -> putStr bashCompletionScript
    "--bash-completion" : index : words' -> bashCompletion index words'
    ["--bash-completion"] -> pure ()
    "ghc-proxy" : rest -> observeBuildAdmission rest >> runGhcProxy rest
    ["--help"] -> putStr usage
    ["plan-package", "--help"] -> putStr usage
    "plan-package" : rest -> case getOpt Permute options rest of
      (updates, targets, []) | length targets <= 1 -> do
        let opts = foldl (flip ($)) defaultPlanOptions updates
            target = case targets of [] -> "."; [file] -> file; _ -> error "checked above"
        planPackage opts target >>= putStrLn . renderJson
      (_, _, errors) -> die (concat errors ++ usage)
    "export-installed-unit" : rest -> case getOpt Permute (withHelp exportInstalledOptions) rest of
      (updates, _, []) | any isNothing updates -> putStr exportInstalledUsage
      (updates, [identifier], []) -> do
        let selected = foldl (flip ($)) (InstalledExportOptions "" "" "" [] "" "" "") (catMaybes updates)
            ghc = exportGhc selected
            pkg = exportPackageTool selected
        unless (all (not . null) [ghc, pkg, exportHelper selected, exportLayout selected,
                                  exportCache selected, exportOutput selected]) $
          die ("export-installed-unit requires explicit compiler, package tool, helper, layout, cache and output\n" ++ exportInstalledUsage)
        identity <- installedCompilerIdentity ghc
        selectedHelper <- makeAbsolute (exportHelper selected)
        context <- installedContext ghc pkg selectedHelper (exportDatabases selected) identity
        selectedLayout <- makeAbsolute (exportLayout selected)
        selectedOutput <- makeAbsolute (exportOutput selected)
        selectedCache <- makeAbsolute (exportCache selected)
        exportInstalledUnit context identifier selectedLayout selectedOutput selectedCache
      (_, _, errors) -> die (concat errors ++ exportInstalledUsage)
    command : rest | command `elem` ["run", "acquire", "build"] -> do
      let (driverArgs, suffix) = break (== "--") rest
          guestArgs = drop 1 suffix
          building = command == "build"
          executing = command == "run"
          commandUsage = if building then buildUsage else if executing then runUsage else acquireUsage
      when (not executing && not (null suffix)) $ die (command ++ " does not accept guest arguments")
      case getOpt Permute (withHelp (if building then buildOptions else if executing then runOptions else acquireOptions)) driverArgs of
        (updates, _, []) | any isNothing updates -> putStr commandUsage
        (updates, targets, []) | building || length targets <= 1 -> do
          let opts = foldl (flip ($)) (RunOptions defaultPlanOptions "" Nothing Nothing "" Nothing "pinned" Nothing False Nothing True True False guestArgs) (catMaybes updates)
          thcRoot <- resolveThcRoot (runThcRoot opts)
          let selected = opts {runTarget = case targets of [target] -> target; _ -> "",
                               runThcRoot = thcRoot}
          current <- getCurrentDirectory
          if building then buildTargetsProject selected targets current
            else if executing then runProject selected current else acquireProject selected current
        (_, _, errors) -> die (concat errors ++ commandUsage)
    command@(first : _) : rest | first /= '-' && '/' `notElem` command && '\\' `notElem` command -> do
      extension <- findExecutable ("thc-" ++ command)
      maybe (die usage) (\path -> rawSystem path rest >>= exitWith) extension
    _ -> die usage

-- | Bash passes the zero-based cursor and its word array, including argv[0].
-- Candidates are raw lines, never shell code. Extensions receive their own argv[0].
bashCompletion :: String -> [String] -> IO ()
bashCompletion cursor words' = case readMaybe cursor of
  Just index | index > 0 && index < length words' -> do
    let current = words' !! index
        before = take index words'
        previous = words' !! (index - 1)
    candidates <- if index == 1 then do
        extensions <- completionExtensions
        pure (map fst completionCommands ++ ["--help", "--bash-completion-script"] ++ extensions)
      else case lookup (words' !! 1) completionCommands of
        Just descriptors
          | "--" `elem` drop 2 before -> pure []
          | previous == "--installed-core" && any (takesValue previous) descriptors -> pure ["required", "pinned", "demand"]
          | "--installed-core=" `isPrefixOf` current && any (takesValue "--installed-core") descriptors -> pure ["--installed-core=required", "--installed-core=pinned", "--installed-core=demand"]
          | any (takesValue previous) descriptors -> pure []
          | otherwise -> pure (concatMap optionNames descriptors)
        Nothing -> do
          let command = "thc-" ++ words' !! 1
          extension <- findExecutable command
          case extension of
            Nothing -> pure []
            Just path -> do
              response <- tryIOError $ readProcessWithExitCode path
                ("--bash-completion" : show (index - 1) : command : drop 2 words') ""
              pure $ case response of
                Right (ExitSuccess, output, _) -> lines output
                _ -> []
    mapM_ putStrLn $ sort $ nub $ filter (current `isPrefixOf`) candidates
  _ -> pure ()
  where
    optionNames (Option shorts longs _ _) = map (\c -> ['-', c]) shorts ++ map ("--" ++) longs
    takesValue name option@(Option _ _ argument _) = name `elem` optionNames option && case argument of
      NoArg _ -> False
      _ -> True

-- Derive option completions from the same descriptors used to parse commands.
completionCommands :: [(String, [OptDescr ()])]
completionCommands =
  [("plan-package", map void (withHelp options)),
   ("run", map void (withHelp runOptions)),
   ("acquire", map void (withHelp acquireOptions)),
   ("build", map void (withHelp buildOptions)),
   ("export-installed-unit", map void (withHelp exportInstalledOptions))]

completionExtensions :: IO [String]
completionExtensions = do
  path <- fromMaybe "" <$> lookupEnv "PATH"
  names <- mapM entries (if null path then [""] else splitSearchPath path)
  pure (concat names)
  where
    entries directory = do
      let base = if null directory then "." else directory
      listed <- tryIOError (listDirectory base)
      let names = fromRight [] listed
      installed <- filterM (runnable base) [name | name <- names, "thc-" `isPrefixOf` name, length name > 4]
      pure [drop 4 (if Host.os == "mingw32" && takeExtension name == ".exe" then dropExtension name else name)
           | name <- installed, '\n' `notElem` name, '\r' `notElem` name]
    runnable directory name = do
      result <- tryIOError $ do
        let file = directory </> name
        exists <- doesFileExist file
        if exists then executable <$> getPermissions file else pure False
      pure (fromRight False result)

bashCompletionScript :: String
bashCompletionScript = unlines
  ["# Bash completion for thc. Source this file or install it as a bash-completion entry.",
   "_thc() {", "    local candidate", "    COMPREPLY=()",
   "    while IFS= read -r candidate; do", "        COMPREPLY+=(\"$candidate\")",
   "    done < <(\"${COMP_WORDS[0]}\" --bash-completion \"$COMP_CWORD\" \"${COMP_WORDS[@]}\" 2>/dev/null)",
   "}", "complete -o bashdefault -o default -F _thc thc"]

-- Parse help as an option, so an option value literally named --help is not
-- mistaken for a request. The guest suffix has already been split off.
withHelp :: [OptDescr a] -> [OptDescr (Maybe a)]
withHelp descriptors = Option ['h'] ["help"] (NoArg Nothing) "Show this help text" :
  map (fmap Just) descriptors

options :: [OptDescr (PlanOptions -> PlanOptions)]
options =
  [ Option [] ["dist-dir"] (ReqArg (\p o -> o {distDirectory = p}) "DIR") "Cabal/THC output directory (default: dist-thc)"
  , Option [] ["with-ghc"] (ReqArg (\p o -> o {ghcPath = Just p}) "PATH") "GHC compiler to configure"
  , Option [] ["with-ghc-pkg"] (ReqArg (\p o -> o {ghcPkgPath = Just p}) "PATH") "Matching ghc-pkg"
  , Option [] ["flag"] (ReqArg (\f o -> o {selectedFlags = selectedFlags o ++ [parseFlag f]}) "NAME|-NAME") "Enable/disable a Cabal package flag"
  , Option [] ["enable-tests"] (NoArg (\o -> o {enableTests = True})) "Configure exitcode-stdio test suites"
  , Option [] ["enable-benchmarks"] (NoArg (\o -> o {enableBenchmarks = True})) "Configure exitcode-stdio benchmarks"
  ]
  where
    parseFlag ('-':name) = (mkFlagName name, False)
    parseFlag name = (mkFlagName name, True)

usage :: String
usage = usageInfo "Usage: thc plan-package [PACKAGE.cabal|DIR] [OPTIONS]\n\nConfigure one Simple Cabal package against installed global dependencies.\nEmits JSON; does not solve cabal.project, compile, export THC Core or repl.\n\nAlso available: thc build [TARGETS...] [FLAGS]\n                thc run [TARGET] [FLAGS] [-- ARG...]\n                thc acquire [TARGET] [FLAGS]\n                thc export-installed-unit UNIT [FLAGS]\n" options

runOptions :: [OptDescr (RunOptions -> RunOptions)]
runOptions =
  [ Option [] ["project-dir"] (ReqArg (\path r -> r {runProjectDirectory = Just path}) "DIR") "Cabal project directory"
  , Option [] ["project-file"] (ReqArg (\path r -> r {runProjectFile = Just path}) "FILE") "Cabal project file"
  , Option [] ["thc-root"] (ReqArg (\path r -> r {runThcRoot = path}) "DIR") "THC source/build root (default: locate from the executable)"
  , Option [] ["runtime"] (ReqArg (\path r -> r {runRuntime = Just path}) "PATH") "Installed THC JVM launcher"
  , Option [] ["dap-port"] (ReqArg (\value r -> r {runDapPort = Just (parseDapPort value)}) "PORT")
      "Listen for Graal DAP on 127.0.0.1:PORT (1..65535); suspend and wait for attachment"
  , Option [] ["dap-suspend"] (NoArg (\r -> r {runDapSuspend = True}))
      "Suspend on the first guest statement (default with --dap-port)"
  , Option [] ["dap-no-suspend"] (NoArg (\r -> r {runDapSuspend = False}))
      "Do not suspend on the first guest statement (requires --dap-port)"
  , Option [] ["dap-wait-attached"] (NoArg (\r -> r {runDapWaitAttached = True}))
      "Wait for debugger attachment (default with --dap-port)"
  , Option [] ["dap-no-wait-attached"] (NoArg (\r -> r {runDapWaitAttached = False}))
      "Start guest execution before a debugger attaches (requires --dap-port)"
  , Option [] ["verify-artifacts"] (NoArg (\r -> r {runVerifyArtifacts = True}))
      "Audit reachable Core before launch and verify runtime artifacts (default: off)"
  , Option [] ["installed-core"] (ReqArg (\policy r -> r {runInstalledCore = policy}) "required|pinned|demand") "Project boot-library provider (default: pinned release sources); required never silently falls back"
  , Option [] ["ghc-source"] (ReqArg (\path r -> r {runGhcSource = Just path}) "DIR") "Matching configured GHC 9.14.1 source tree for missing installed foreign annotations (required or demand provider)"
  ] ++ map liftPlanOption options

-- Parse without Int overflow before enforcing the TCP port range.
parseDapPort :: String -> Int
parseDapPort value = case readMaybe value :: Maybe Integer of
  Just port | port >= 1 && port <= 65535 -> fromInteger port
  _ -> 0

liftPlanOption :: OptDescr (PlanOptions -> PlanOptions) -> OptDescr (RunOptions -> RunOptions)
liftPlanOption (Option shorts longs argument description) = Option shorts longs (case argument of
  NoArg update -> NoArg (liftUpdate update)
  ReqArg update name -> ReqArg (liftUpdate . update) name
  OptArg update name -> OptArg (liftUpdate . update) name) description
  where liftUpdate update run = run {runPlan = update (runPlan run)}

runUsage :: String
runUsage = usageInfo "Usage: thc run [TARGET] [FLAGS] [-- ARG...]\n\nResolve a Cabal runnable target, build and export its GHC main :: IO (), then execute it in THC.\nUse --verify-artifacts to request a pre-launch Core audit and runtime artifact verification.\nTARGET uses Cabal syntax, including PACKAGE:exe:NAME, PACKAGE:test:NAME and PACKAGE:bench:NAME.\nWith no target, select the current package's sole buildable executable, otherwise its sole buildable runnable component.\nCabal reports ambiguous or disabled targets. Tests must be exitcode-stdio-1.0, not detailed library tests.\nArguments after -- are passed unchanged to the guest, including empty strings and option-looking arguments.\nUse --project-dir/--project-file for project location. The built native runnable is never executed.\n" (withHelp runOptions)

acquireOptions :: [OptDescr (RunOptions -> RunOptions)]
acquireOptions = [option | option@(Option _ names _ _) <- runOptions,
  not (any (`elem` ["runtime", "verify-artifacts", "dap-port", "dap-suspend", "dap-no-suspend", "dap-wait-attached", "dap-no-wait-attached"]) names)]

buildOptions :: [OptDescr (RunOptions -> RunOptions)]
buildOptions = Option [] ["native-image"] (NoArg (\r -> r {runNativeImage = True}))
  "Build fresh THC Native Images for selected runnable components (default: off)" : acquireOptions

buildUsage :: String
buildUsage = usageInfo "Usage: thc build [TARGETS...] [FLAGS]\n\nUse Cabal to build selected components and acquire their dependency Core into DIST/packages.json.\nWith no target, select the current package. Use all for every enabled project component, or pass libraries, executables and multiple Cabal targets.\nModule and file targets acquire their complete owning component.\nBy default, stop after atomic manifest publication. --native-image then builds fresh THC Native Images for selected executables, stdio tests and benchmarks; libraries still acquire.\nNative Images require Linux x86_64, pinned GraalVM and AST execution. The driver defaults to resource-copy; THC_NATIVE_IMAGE_VECTOR_PROFILE overrides the profile.\nOutputs: DIST/native-images/<unit-id SHA256>/completion.json and its concrete artifact inventory. Images are not cached and do not guarantee static linking.\nNo guest/native application execution. Acquisition does not establish runtime support. Native Windows project acquisition is not yet supported.\n" (withHelp buildOptions)

acquireUsage :: String
acquireUsage = usageInfo "Usage: thc acquire [TARGET] [FLAGS]\n\nResolve the same Cabal runnable target as run and export its dependency closure to DIST/packages.json.\nStops after atomic manifest publication: no reachable-Core audit, THC guest execution or native runnable invocation.\nThe manifest is acquisition evidence, not a claim of runtime support. No runtime launcher or guest arguments are needed.\n" (withHelp acquireOptions)

-- Exact construction inputs; no implicit source build or user cache selection.
data InstalledExportOptions = InstalledExportOptions
  { exportGhc :: FilePath, exportPackageTool :: FilePath, exportHelper :: FilePath
  , exportDatabases :: [FilePath], exportLayout :: FilePath
  , exportCache :: FilePath, exportOutput :: FilePath
  }

exportInstalledOptions :: [OptDescr (InstalledExportOptions -> InstalledExportOptions)]
exportInstalledOptions =
  [ Option [] ["with-ghc"] (ReqArg (\value o -> o {exportGhc = value}) "PATH") "Selected GHC 9.14.1 (required)"
  , Option [] ["with-ghc-pkg"] (ReqArg (\value o -> o {exportPackageTool = value}) "PATH") "Matching ghc-pkg (required)"
  , Option [] ["interface-helper"] (ReqArg (\value o -> o {exportHelper = value}) "PATH") "Built thc-interface (required)"
  , Option [] ["package-db"] (ReqArg (\value o -> o {exportDatabases = exportDatabases o ++ [value]}) "DIR") "Additional genuine registration DB; repeat in stack order"
  , Option [] ["target-layout"] (ReqArg (\value o -> o {exportLayout = value}) "FILE") "THC target-layout.c source (required)"
  , Option [] ["cache-dir"] (ReqArg (\value o -> o {exportCache = value}) "DIR") "Caller-owned acquisition cache (required)"
  , Option [] ["dist-dir"] (ReqArg (\value o -> o {exportOutput = value}) "DIR") "Caller-owned manifest/CBD output (required)"
  ]

exportInstalledUsage :: String
exportInstalledUsage = usageInfo "Usage: thc export-installed-unit UNIT [FLAGS]\n\nExport one exact registered unit from retained simplified Core to DIST/packages.json and per-module CBD.\nDeclared dependencies remain references: this is not a recursive application closure.\nComplete retained Core is required; no Cabal rebuild or boot acquisition occurs.\nInputs remain read-only. Native tools use THC_CLANG/THC_LLVM_LINK/THC_LLVM_OPT/THC_LLVM_NM/THC_LLVM_OBJCOPY.\n" (withHelp exportInstalledOptions)
