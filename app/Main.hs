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

import Control.Monad (foldM, when)
import Data.Maybe (catMaybes, isNothing)
import Distribution.Simple.Utils (topHandler)
import Distribution.Types.Flag (mkFlagName)
import System.Console.GetOpt
import System.Directory (getCurrentDirectory)
import System.Environment (getArgs)
import System.Exit (die)
import System.IO (hSetEncoding, stderr, stdout, utf8)
import THC.Driver.Cabal
import THC.Driver.GhcProxy (runGhcProxy)
import THC.Driver.Json (renderJson)
import THC.Driver.Project (runProject, acquireProject)
import THC.Driver.Run

main :: IO ()
main = topHandler $ do
  hSetEncoding stdout utf8
  hSetEncoding stderr utf8
  args <- getArgs
  case args of
    "ghc-proxy" : rest -> runGhcProxy rest
    ["--help"] -> putStr usage
    ["plan-package", "--help"] -> putStr usage
    "plan-package" : rest -> case getOpt Permute options rest of
      (updates, targets, []) | length targets <= 1 -> do
        let opts = foldl (flip ($)) defaultPlanOptions updates
            target = case targets of [] -> "."; [file] -> file; _ -> error "checked above"
        planPackage opts target >>= putStrLn . renderJson
      (_, _, errors) -> die (concat errors ++ usage)
    command : rest | command `elem` ["run", "acquire"] -> do
      let (driverArgs, suffix) = break (== "--") rest
          guestArgs = drop 1 suffix
          acquire = command == "acquire"
          commandUsage = if acquire then acquireUsage else runUsage
      when (acquire && not (null suffix)) $ die "acquire does not accept guest arguments"
      case getOpt Permute (withHelp (if acquire then acquireOptions else runOptions)) driverArgs of
        (updates, _, []) | any isNothing updates -> putStr commandUsage
        (updates, targets, []) | length targets <= 1 -> do
          opts <- either (die . (++ "\n" ++ commandUsage)) pure $
            foldM (flip ($)) (RunOptions defaultPlanOptions "" Nothing Nothing "" Nothing "pinned" Nothing Nothing guestArgs) (catMaybes updates)
          let selected = opts {runTarget = case targets of [] -> ""; [target] -> target; _ -> error "checked above"}
          current <- getCurrentDirectory
          if acquire then acquireProject selected current else runProject selected current
        (_, _, errors) -> die (concat errors ++ commandUsage)
    _ -> die usage

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
usage = usageInfo "Usage: thc plan-package [PACKAGE.cabal|DIR] [OPTIONS]\n\nConfigure one Simple Cabal package against installed global dependencies.\nEmits JSON; does not solve cabal.project, compile, export THC Core or repl.\n\nAlso available: thc run [TARGET] --thc-root DIR [FLAGS] [-- ARG...]\n                thc acquire [TARGET] --thc-root DIR [FLAGS]\n" options

runOptions :: [OptDescr (RunOptions -> Either String RunOptions)]
runOptions =
  [ Option [] ["project-dir"] (ReqArg (\path r -> Right r {runProjectDirectory = Just path}) "DIR") "Cabal project directory"
  , Option [] ["project-file"] (ReqArg (\path r -> Right r {runProjectFile = Just path}) "FILE") "Cabal project file"
  , Option [] ["thc-root"] (ReqArg (\path r -> Right r {runThcRoot = path}) "DIR") "THC source/build root"
  , Option [] ["runtime"] (ReqArg (\path r -> Right r {runRuntime = Just path}) "PATH") "Installed THC JVM launcher"
  , Option [] ["ffi"] (ReqArg (\value r -> (\mode -> r {runFfiMode = Just mode}) <$> parseFfiMode value)
      "native|managed") "Runtime FFI mode (default: native); unavailable managed execution fails explicitly"
  , Option [] ["installed-core"] (ReqArg (\policy r -> Right r {runInstalledCore = policy}) "required|pinned") "Project boot-library provider (default: limited pinned sources); required never silently falls back"
  , Option [] ["ghc-source"] (ReqArg (\path r -> Right r {runGhcSource = Just path}) "DIR") "Matching configured GHC 9.14.1 source tree for missing installed foreign annotations (required provider only)"
  ] ++ map liftPlanOption options

liftPlanOption :: OptDescr (PlanOptions -> PlanOptions) -> OptDescr (RunOptions -> Either String RunOptions)
liftPlanOption (Option shorts longs argument description) = Option shorts longs (case argument of
  NoArg update -> NoArg (liftUpdate update)
  ReqArg update name -> ReqArg (\value -> liftUpdate (update value)) name
  OptArg update name -> OptArg (\value -> liftUpdate (update value)) name) description
  where liftUpdate update run = Right run {runPlan = update (runPlan run)}

runUsage :: String
runUsage = usageInfo "Usage: thc run [TARGET] [FLAGS] [-- ARG...]\n\nResolve a Cabal runnable target, build and export its GHC main :: IO (), then audit and execute it in THC.\nTARGET uses Cabal syntax, including PACKAGE:exe:NAME, PACKAGE:test:NAME and PACKAGE:bench:NAME.\nWith no target, select the current package's sole buildable executable, otherwise its sole buildable runnable component.\nCabal reports ambiguous or disabled targets. Tests must be exitcode-stdio-1.0, not detailed library tests.\nArguments after -- are passed unchanged to the guest, including empty strings and option-looking arguments.\nUse --project-dir/--project-file for project location. The built native runnable is never executed.\n" (withHelp runOptions)

acquireOptions :: [OptDescr (RunOptions -> Either String RunOptions)]
acquireOptions = [option | option@(Option _ names _ _) <- runOptions,
  not (any (`elem` ["runtime", "ffi"]) names)]

acquireUsage :: String
acquireUsage = usageInfo "Usage: thc acquire [TARGET] [FLAGS]\n\nResolve the same Cabal runnable target as run and export its dependency closure to DIST/packages.json.\nStops after atomic manifest publication: no reachable-Core audit, THC guest execution or native runnable invocation.\nThe manifest is acquisition evidence, not a claim of runtime support. No runtime launcher or guest arguments are needed.\n" (withHelp acquireOptions)
