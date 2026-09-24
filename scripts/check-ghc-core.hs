-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Run with: runghc -f "$GHC" --ghc-arg=-package --ghc-arg=ghc
--                  --ghc-arg=-package --ghc-arg=Cabal scripts/check-ghc-core.hs check
-- This reads the installed interface payload, rather than testing whether GHC
-- merely accepts -fwrite-if-simplified-core.
module Main (main) where

import Control.Exception (SomeException, displayException, try)
import Control.Monad (filterM, forM, unless, when)
import Control.Monad.IO.Class (liftIO)
import Data.List (isSuffixOf, nub, sort)
import qualified Data.ByteString.Char8 as ByteString
import qualified Data.Set as Set
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo)
import Distribution.Types.InstalledPackageInfo (importDirs)
import GHC (getSessionDynFlags, runGhc)
import GHC.Driver.Session (targetProfile)
import GHC.Iface.Binary (CheckHiWay (IgnoreHiWay), TraceBinIFace (QuietBinIFace), readBinIface)
import GHC.Types.Name.Cache (newNameCache)
import GHC.Unit.Module (moduleName, moduleUnitId)
import GHC.Unit.Module.ModIface (mi_module, mi_simplified_core)
import GHC.Unit.Types (unitIdString)
import Language.Haskell.Syntax.Module.Name (moduleNameString)
import System.Directory (canonicalizePath, doesDirectoryExist, doesFileExist, findExecutable, listDirectory)
import System.Environment (getArgs, lookupEnv)
import System.Exit (ExitCode (ExitSuccess), exitFailure)
import System.FilePath (dropExtension, makeRelative, takeDirectory, (</>))
import System.IO (hPutStrLn, stderr)
import System.Process (readProcessWithExitCode)

data Mode = Check | Advisory | Probe FilePath

main :: IO ()
main = do
  args <- getArgs
  mode <- case args of
    ["check"] -> pure Check
    ["advisory"] -> pure Advisory
    ["probe-interface", path] -> pure (Probe path)
    _ -> die "usage: check-ghc-core.hs (check | advisory | probe-interface PATH)"
  selected <- maybe "ghc" id <$> lookupEnv "GHC"
  ghc <- resolveExecutable selected
  libdir <- oneLine ghc ["--print-libdir"]
  case mode of
    Probe path -> do
      inspected <- inspect libdir [("probe", path)]
      case inspected of
        [Right ()] -> putStrLn "interface contains simplified Core"
        [Left reason] -> die reason
        _ -> die "internal probe result mismatch"
    _ -> do
      pkg <- selectPackageManager ghc
      ghcDb <- canonicalizePath =<< oneLine ghc ["--print-global-package-db"]
      pkgListing <- oneLine pkg ["--global", "--no-user-package-db", "list", "ghc-internal"]
      pkgDbName <- case lines pkgListing of
        firstLine : _ -> pure firstLine
        [] -> die "selected ghc-pkg did not report a global package database"
      pkgDb <- canonicalizePath pkgDbName
      unless (ghcDb == pkgDb) $
        die ("selected ghc and ghc-pkg use different global package databases: " ++ ghcDb ++ " / " ++ pkgDb)
      packageId <- oneLine pkg ["--global", "--no-user-package-db", "field", "ghc-internal", "id", "--simple-output"]
      unless (length (words packageId) == 1) $
        die "selected ghc-pkg does not identify exactly one installed ghc-internal"
      description <- oneLine pkg ["--global", "--no-user-package-db", "--expand-pkgroot", "describe", "ghc-internal"]
      packageInfo <- case parseInstalledPackageInfo (ByteString.pack description) of
        Left errors -> die ("cannot parse installed ghc-internal metadata: " ++ show errors)
        Right (_, info) -> pure info
      installedDirs <- mapM canonicalizePath (importDirs packageInfo)
      unless (not (null installedDirs) && length installedDirs == length (nub installedDirs)) $
        die "ghc-internal must have distinct nonempty import directories"
      paths <- concat <$> mapM interfacePaths installedDirs
      when (null paths) $ die "installed ghc-internal has no concrete .hi/.dyn_hi interfaces"
      inspected <- inspect libdir paths
      let failures = [path ++ ": " ++ reason | ((_, path), Left reason) <- zip paths inspected]
      case (mode, failures) of
        (Check, []) -> putStrLn ("ghc-internal complete Core present in " ++ show (length paths) ++ " installed interfaces")
        (Check, _) -> reportFailure failures
        (Advisory, []) -> putStrLn pkg
        (Advisory, _) -> do
          hPutStrLn stderr ("warning: ghc-internal complete Core is unavailable in " ++ show (length failures) ++
                            " of " ++ show (length paths) ++ " installed interfaces; see docs/ghc-core.md")
          mapM_ (hPutStrLn stderr . ("  " ++)) (take 3 failures)
          putStrLn pkg

reportFailure :: [String] -> IO a
reportFailure failures = do
  hPutStrLn stderr ("ghc-internal complete Core is missing or invalid in " ++ show (length failures) ++
                    " installed interfaces; see docs/ghc-core.md")
  mapM_ (hPutStrLn stderr . ("  " ++)) (take 8 failures)
  exitFailure

inspect :: FilePath -> [(String, FilePath)] -> IO [Either String ()]
inspect libdir paths = runGhc (Just libdir) $ do
  flags <- getSessionDynFlags
  liftIO $ do
    cache <- newNameCache
    forM paths $ \(expectedModule, path) -> do
      result <- try (readBinIface (targetProfile flags) cache IgnoreHiWay QuietBinIFace path)
      pure $ case result of
        Left (errorValue :: SomeException) -> Left (displayException errorValue)
        Right iface
          | unitIdString (moduleUnitId (mi_module iface)) /= "ghc-internal" && expectedModule /= "probe" ->
              Left "interface belongs to another unit"
          | expectedModule /= "probe" && moduleNameString (moduleName (mi_module iface)) /= expectedModule ->
              Left "interface module does not match its installed path"
          | otherwise -> case mi_simplified_core iface of
              Nothing -> Left "mi_simplified_core is absent"
              Just _ -> Right ()

interfacePaths :: FilePath -> IO [(String, FilePath)]
interfacePaths root = do
  exists <- doesDirectoryExist root
  unless exists $ die ("ghc-internal import directory does not exist: " ++ root)
  files <- walk Set.empty root
  let concrete = sort [path | path <- files, ".hi" `isSuffixOf` path || ".dyn_hi" `isSuffixOf` path]
  forM concrete $ \path -> do
    real <- canonicalizePath path
    let relative = makeRelative root path
    let modulePath = if ".dyn_hi" `isSuffixOf` relative
                     then take (length relative - length ".dyn_hi") relative
                     else dropExtension relative
        moduleId = map (\c -> if c == '/' || c == '\\' then '.' else c) modulePath
    pure (moduleId, real)

walk :: Set.Set FilePath -> FilePath -> IO [FilePath]
walk visited root = do
  real <- canonicalizePath root
  if Set.member real visited then pure [] else do
    children <- listDirectory root
    let seen = Set.insert real visited
    concat <$> forM children (\child -> do
      let path = root </> child
      directory <- doesDirectoryExist path
      if directory then walk seen path else pure [path])

selectPackageManager :: FilePath -> IO FilePath
selectPackageManager ghc = do
  explicit <- lookupEnv "GHC_PKG"
  case explicit of
    Just value | not (null value) -> resolveExecutable value
    _ -> do
      version <- oneLine ghc ["--numeric-version"]
      let directory = takeDirectory ghc
          candidates = [directory </> ("ghc-pkg-" ++ version), directory </> "ghc-pkg"]
      available <- filterM doesFileExist candidates
      case available of
        sibling : _ -> canonicalizePath sibling
        [] -> die ("no matching ghc-pkg beside selected GHC: " ++ ghc ++ "; set GHC_PKG explicitly")

resolveExecutable :: FilePath -> IO FilePath
resolveExecutable name = do
  found <- findExecutable name
  case found of
    Nothing -> die ("executable not found: " ++ name)
    Just path -> canonicalizePath path

oneLine :: FilePath -> [String] -> IO String
oneLine command args = do
  (status, output, err) <- readProcessWithExitCode command args ""
  unless (status == ExitSuccess) $
    die (command ++ " failed: " ++ take 300 err)
  let value = reverse (dropWhile (`elem` "\r\n") (reverse output))
  when (null value) $ die (command ++ " returned empty output")
  pure value

die :: String -> IO a
die message = hPutStrLn stderr ("check-ghc-core: " ++ message) >> exitFailure
