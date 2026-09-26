-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- GHC-independent discovery and subprocess boundary. Only thc-interface links
-- the selected GHC API. Never substitute ordinary unfoldings for a full payload.
module THC.Driver.Installed
  ( InstalledContext(..), InstalledUnit(..), InstalledCore(..), MissingCore(..)
  , installedContext, discoverInstalled, validateReexports, acquireInstalled
  , installedProvenance, installedLayoutHeaders, helperCommand, probeInstalled
  , emptyRegistration
  ) where

import Control.Monad (filterM, foldM, forM, forM_, unless)
import Data.Aeson (Value(..), FromJSON, eitherDecodeStrict', encode, fromJSON, Result(..), object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (isAlphaNum, isHexDigit)
import Data.List (nub, sort)
import qualified Data.Map.Strict as Map
import Data.Maybe (isNothing)
import qualified Data.Set as Set
import Distribution.Backpack (OpenModule(..), OpenUnitId(..))
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo)
import Distribution.Pretty (prettyShow)
import Distribution.Types.ExposedModule (ExposedModule(..))
import qualified Distribution.Types.InstalledPackageInfo as Package
import System.Directory (canonicalizePath, doesDirectoryExist, doesFileExist)
import System.Environment (getEnvironment)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), pathSeparator)
import System.Process (proc, CreateProcess(..), readCreateProcessWithExitCode)
import System.Timeout (timeout)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text

data InstalledContext = InstalledContext
  { installedHelper :: FilePath, installedLibdir :: FilePath
  , installedPackageTool :: FilePath, installedGlobalDb :: FilePath
  , installedDatabases :: [FilePath], installedCompiler :: Value
  } deriving (Eq, Show)

data InstalledUnit = InstalledUnit
  { registeredId :: String, registration :: String, installedDepends :: [String]
  , installedInterfaces :: [(String, FilePath)]
  , installedReexports :: [(String, String, String)]
  } deriving (Eq, Show)

data InstalledCore = InstalledCore
  { coreOwner :: String, coreModules :: [(String, BS.ByteString)] }
  deriving (Eq, Show)

data MissingCore = MissingCore
  { missingUnit :: String, missingModule :: String, missingInterface :: FilePath }
  deriving (Eq, Show)

-- Compatibility packages such as nats have no library modules on modern GHC.
-- A missing capture alone does not establish that: retain the registration
-- produced by the same successful build, and check it again on cache reads.
emptyRegistration :: String -> [String] -> BS.ByteString -> Bool
emptyRegistration identifier dependencies bytes = case parseInstalledPackageInfo bytes of
  Left _ -> False
  Right (_, info) -> prettyShow (Package.installedUnitId info) == identifier &&
    sort (map prettyShow (Package.depends info)) == sort dependencies &&
    null (Package.exposedModules info) && null (Package.hiddenModules info) &&
    null (Package.hsLibraries info)

installedContext :: FilePath -> FilePath -> FilePath -> [FilePath] -> Value -> IO InstalledContext
installedContext ghc pkg helper databases compiler = do
  version <- command ghc ["--numeric-version"]
  unless (version == "9.14.1") (fail "installed Core requires selected GHC 9.14.1")
  libdir <- canonicalizePath =<< command ghc ["--print-libdir"]
  global <- canonicalizePath =<< command ghc ["--print-global-package-db"]
  listing <- command pkg ["--global", "--no-user-package-db", "list"]
  selected <- case lines listing of
    path : _ -> canonicalizePath path
    _ -> fail "selected ghc-pkg did not report its global database"
  unless (global == selected) (fail "selected ghc and ghc-pkg global databases differ")
  dbs <- mapM canonicalizePath databases
  unless (length dbs == length (nub dbs) && global `notElem` dbs)
    (fail "duplicate selected installed-Core package database")
  pure (InstalledContext helper libdir pkg global dbs compiler)

-- Cabal's parsed registration is authoritative, including hidden modules and
-- exact reexport providers. Do not invent bodies for native-only/facade units.
discoverInstalled :: InstalledContext -> String -> IO InstalledUnit
discoverInstalled context identifier = do
  description <- command (installedPackageTool context)
    (["--global", "--no-user-package-db", "--expand-pkgroot"] ++
     concatMap (\db -> ["--package-db", db]) (installedDatabases context) ++
     ["--ipid", "describe", identifier])
  info <- case parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack description)) of
    Left errors -> fail ("invalid registration for " ++ identifier ++ ": " ++ show errors)
    Right (_, value) -> pure value
  unless (prettyShow (Package.installedUnitId info) == identifier)
    (fail ("registration differs from requested unit " ++ identifier))
  let names = sort (map prettyShow (Package.hiddenModules info) ++
        [prettyShow (exposedName entry) | entry <- Package.exposedModules info,
                                           isNothing (exposedReexport entry)])
  unless (length names == length (nub names)) (fail "duplicate installed module declaration")
  directories <- mapM canonicalizePath (Package.importDirs info)
  paths <- forM names $ \name -> do
    unless (not (null name) && all (\c -> isAlphaNum c || c `elem` ("._'" :: String)) name &&
            all (not . null) (wordsByDot name)) (fail "invalid installed module name")
    let relative = map (\c -> if c == '.' then pathSeparator else c) name ++ ".dyn_hi"
    matches <- filterM doesFileExist [directory </> relative | directory <- directories]
    path <- case matches of
      [found] -> canonicalizePath found
      _ -> fail ("expected exactly one dynamic interface for " ++ identifier ++ ":" ++ name)
    pure (name, path)
  reexports <- fmap concat $ forM (Package.exposedModules info) $ \entry ->
    case exposedReexport entry of
      Nothing -> pure []
      Just (OpenModule (DefiniteUnitId owner) name) ->
        pure [(prettyShow (exposedName entry), prettyShow owner, prettyShow name)]
      Just _ -> fail ("indefinite installed reexport in " ++ identifier)
  pure (InstalledUnit identifier description (map prettyShow (Package.depends info)) paths reexports)
  where
    wordsByDot name = case break (== '.') name of
      (part, []) -> [part]
      (part, _:rest) -> part : wordsByDot rest

validateReexports :: [InstalledUnit] -> IO ()
validateReexports units = do
  let byId = Map.fromList [(registeredId unit, unit) | unit <- units]
      concrete = Set.fromList [(registeredId unit, name) | unit <- units,
                                 (name, _) <- installedInterfaces unit]
      closure seen identifier
        | Set.member identifier seen = seen
        | otherwise = case Map.lookup identifier byId of
            Nothing -> seen
            Just unit -> foldl closure (Set.insert identifier seen) (installedDepends unit)
  unless (Map.size byId == length units) (fail "duplicate installed registrations")
  forM_ units $ \unit -> forM_ (installedReexports unit) $ \(_, owner, name) ->
    unless (Set.member owner (closure Set.empty (registeredId unit)) &&
            Set.member (owner, name) concrete)
      (fail ("missing concrete installed reexport provider " ++ owner ++ ":" ++ name))

installedProvenance :: InstalledContext -> InstalledUnit -> Value
installedProvenance context unit = object
  ["registeredUnit" .= registeredId unit, "registration" .= registration unit,
   "compiler" .= installedCompiler context, "libdir" .= installedLibdir context,
   "packageDatabases" .= (installedGlobalDb context : installedDatabases context),
   "way" .= ("dynamic" :: String), "coverage" .= ("registered-owned-modules" :: String),
   "interfaces" .= [object ["module" .= name, "path" .= path] | (name, path) <- installedInterfaces unit],
   "reexports" .= installedReexports unit, "depends" .= installedDepends unit]

-- Use the selected package database's RTS headers, rather than host headers
-- or paths inferred from the GHC executable. The registration text is part of
-- the bundle's build identity so a changed RTS layout cannot reuse a receipt.
installedLayoutHeaders :: InstalledContext -> InstalledUnit -> IO (String, [FilePath])
installedLayoutHeaders context unit = do
  let pkg = installedPackageTool context
      global = ["--global", "--no-user-package-db", "--expand-pkgroot"]
      selected = global ++
        concatMap (\db -> ["--package-db", db]) (installedDatabases context)
  ids <- words <$> command pkg (global ++ ["field", "rts", "id", "--simple-output"])
  rtsId <- case ids of
    [identifier] -> pure identifier
    _ -> fail "selected GHC has no unique RTS registration"
  description <- command pkg (global ++ ["--ipid", "describe", rtsId])
  info <- case parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack description)) of
    Left errors -> fail ("invalid selected RTS registration: " ++ show errors)
    Right (_, value) -> pure value
  unless (prettyShow (Package.installedUnitId info) == rtsId)
    (fail "selected RTS registration changed")
  directories <- mapM canonicalizePath (Package.includeDirs info)
  internal <- mapM canonicalizePath =<< do
    internalDescription <- command pkg (selected ++ ["--ipid", "describe", registeredId unit])
    case parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack internalDescription)) of
      Left errors -> fail ("invalid selected ghc-internal registration: " ++ show errors)
      Right (_, value) -> do
        unless (prettyShow (Package.installedUnitId value) == registeredId unit &&
                internalDescription == registration unit)
          (fail "selected ghc-internal registration changed")
        pure (Package.includeDirs value)
  let includes = nub (directories ++ internal)
  unless (not (null includes)) (fail "selected RTS include directories are unavailable")
  found <- mapM doesDirectoryExist includes
  unless (and found) (fail "selected RTS include directory is missing")
  pure (description, includes)

helperCommand :: InstalledContext -> InstalledUnit -> (String, FilePath) -> [String]
helperCommand context unit (name, path) =
  ["--libdir", installedLibdir context, "--unit", registeredId unit,
   "--module", name, "--interface", path, "--way", "dynamic", "--source-notes"] ++
  concatMap (\db -> ["--package-db", db]) (installedDatabases context)

-- Exact registered dependency closure, including mutable boot/-inplace units.
-- This is an acceleration hint, never a substitute for ordinary acquisition:
-- callers discard it on any discovery, protocol, identity or read failure.
-- Thin dependency interfaces are valid hydration inputs; the requested unit
-- itself must retain complete Core in every owned module.
probeInstalled :: InstalledContext -> InstalledUnit -> IO Value
probeInstalled context requested = do
  units <- Map.elems <$> visit Set.empty Map.empty (registeredId requested)
  unless (lookup (registeredId requested) [(registeredId unit, unit) | unit <- units] == Just requested)
    (fail "installed registration changed before interface probe")
  validateReexports units
  let entries = [(registeredId unit, name, path) | unit <- units,
                   (name, path) <- installedInterfaces unit]
      request = object ["units" .= map registeredId units,
        "interfaces" .= [object ["unit" .= identifier, "module" .= name, "interface" .= path]
                         | (identifier, name, path) <- entries]]
      arguments = ["--libdir", installedLibdir context, "--unit", registeredId requested,
                   "--way", "dynamic", "--probe-inventory"] ++
        concatMap (\db -> ["--package-db", db]) (installedDatabases context)
  (status, output, diagnostic) <- boundedProcessInput (installedHelper context) arguments
    (Text.unpack (Text.decodeUtf8 (BL.toStrict (encode request))))
  response <- either (fail . ("invalid interface probe: " ++)) pure
    (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack output)))
  unless (status == ExitSuccess && valueAt response "schema" == Just (1 :: Int) &&
          valueAt response "status" == Just ("probed" :: String))
    (fail ("interface probe unavailable: " ++ take 2000 output ++ take 2000 diagnostic))
  rows <- required response "interfaces" :: IO [Value]
  unless (length rows == length entries) (fail "incomplete interface probe inventory")
  forM_ (zip entries rows) $ \((identifier, name, path), row) -> do
    owner <- required row "owner" :: IO String
    digest <- required row "fingerprint" :: IO String
    complete <- required row "completeCore" :: IO Bool
    unless (valueAt row "unit" == Just identifier && valueAt row "module" == Just name &&
            valueAt row "interface" == Just path && not (null owner) &&
            length digest == 32 && all isHexDigit digest &&
            (identifier /= registeredId requested || complete))
      (fail "inconsistent interface probe identity/payload")
  current <- mapM (discoverInstalled context . registeredId) units
  unless (current == units) (fail "installed registration changed during interface probe")
  pure (object ["registrations" .= map (installedProvenance context) units, "interfaces" .= rows])
  where
    visit active found identifier
      | identifier `Set.member` active = fail "installed dependency cycle"
      | Map.member identifier found = pure found
      | otherwise = do
          unit <- discoverInstalled context identifier
          foldM (visit (Set.insert identifier active)) (Map.insert identifier unit found)
            (sort (installedDepends unit))

acquireInstalled :: InstalledContext -> InstalledUnit -> IO (Either MissingCore InstalledCore)
acquireInstalled context unit = go [] (installedInterfaces unit)
  where
    go modules [] = do
      -- A changed registration aborts the transaction before publication.
      current <- discoverInstalled context (registeredId unit)
      unless (current == unit) (fail "installed registration changed during Core acquisition")
      let owners = nub [owner | (owner, _, _) <- modules]
      owner <- case owners of
        [] -> pure (registeredId unit)
        [value] -> pure value
        _ -> fail "installed interfaces disagree on their original Core owner"
      pure (Right (InstalledCore owner [(name, bytes) | (_, name, bytes) <- reverse modules]))
    go modules (item@(name, path):rest) = do
      (status, output, diagnostic) <- boundedProcess (installedHelper context) (helperCommand context unit item)
      response <- either (fail . ("invalid thc-interface JSON: " ++)) pure
        (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack output)))
      unless (valueAt response "schema" == Just (1 :: Int)) (fail "unsupported thc-interface protocol")
      case (status, valueAt response "status" :: Maybe String) of
        (ExitSuccess, Just "loaded") -> do
          core <- required response "core"
          owner <- required core "unit"
          let schema = valueAt core "schema" :: Maybe Int
              foreignArtifacts = valueAt core "foreign" :: Maybe Value
              archival = schema == Just 2 && maybe False (\value ->
                valueAt value "schema" == Just (1 :: Int) &&
                valueAt value "execution" == Just ("not-linked" :: String)) foreignArtifacts
          unless (not (null owner) && valueAt core "module" == Just name &&
                  ((schema == Just 1 && isNothing foreignArtifacts) || archival) &&
                  valueAt core "ghc" == Just ("9.14.1" :: String) &&
                  valueAt core "boundary" == Just ("optimized-Core-after-Tidy-before-CorePrep" :: String))
            (fail "thc-interface returned inconsistent Core identity/boundary")
          go ((owner, name, BL.toStrict (encode core)) : modules) rest
        (ExitFailure 3, Just "unavailable") -> do
          unless (valueAt response "capability" == Just ("complete-interface-core" :: String) &&
                  valueAt response "unit" == Just (registeredId unit) &&
                  valueAt response "module" == Just name && valueAt response "interface" == Just path &&
                  valueAt response "way" == Just ("dynamic" :: String) &&
                  valueAt response "core" == (Nothing :: Maybe Value))
            (fail "inconsistent missing-Core response")
          pure (Left (MissingCore (registeredId unit) name path))
        _ -> fail ("thc-interface failed for " ++ registeredId unit ++ ":" ++ name ++
                    " (" ++ show status ++ "): " ++ take 2000 output ++ take 2000 diagnostic)

valueAt :: FromJSON a => Value -> String -> Maybe a
valueAt (Object fields) name = do
  value <- KeyMap.lookup (Key.fromString name) fields
  case fromJSON value of Success result -> Just result; Error _ -> Nothing
valueAt _ _ = Nothing

required :: FromJSON a => Value -> String -> IO a
required value name = maybe (fail ("missing/invalid helper field " ++ name)) pure (valueAt value name)

-- readCreateProcessWithExitCode cleans up its child on exceptions, including
-- timeout/cancellation. Neither is classified as a missing capability.
boundedProcess :: FilePath -> [String] -> IO (ExitCode, String, String)
boundedProcess executable arguments = boundedProcessInput executable arguments ""

boundedProcessInput :: FilePath -> [String] -> String -> IO (ExitCode, String, String)
boundedProcessInput executable arguments input = do
  inherited <- getEnvironment
  let clean = filter (\(key, _) -> key `notElem` ["GHC_PACKAGE_PATH", "GHC_ENVIRONMENT"]) inherited
  result <- timeout (180 * 1000000) (readCreateProcessWithExitCode
    (proc executable arguments) {env = Just clean} input)
  maybe (fail ("installed-Core subprocess timed out: " ++ executable)) pure result

command :: FilePath -> [String] -> IO String
command executable arguments = do
  (status, output, diagnostic) <- boundedProcess executable arguments
  unless (status == ExitSuccess) (fail (executable ++ ": " ++ take 2000 diagnostic))
  pure (reverse (dropWhile (`elem` ("\r\n" :: String)) (reverse output)))
