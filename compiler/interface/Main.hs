-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Command-line access to complete Core in exact installed GHC interfaces.
module Main (main) where

import qualified Control.Exception as Exception
import Control.Monad (forM, unless)
import Data.Aeson (Value, FromJSON(..), object, (.=), (.:), encode, eitherDecode, withObject)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (isPrefixOf)
import Data.Maybe (fromMaybe)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import GHC (getSessionDynFlags, getSession, parseDynamicFlags, setSessionDynFlags, runGhc, noLoc,
            guessTarget, setTargets, depanal)
import GHC.Plugins (HscEnv, Module, Unit, hsc_logger, mkModule, mkModuleName, moduleUnit,
                    unitString, stringToUnit, stringToUnitId, liftIO, moduleNameString)
import GHC.Data.Graph.Directed (SCC(..))
import GHC.Driver.Env (hsc_units)
import GHC.Driver.Make (topSortModuleGraph)
import GHC.Driver.DynFlags (DynFlags(ghcMode), GhcMode(OneShot))
import GHC.Types.SourceFile (HscSource(HsBootFile))
import GHC.Types.Unique.Map (lookupUniqMap)
import GHC.Unit.Info (mkUnit)
import GHC.Unit.Module.Graph (ModuleGraphNode(..), ModuleNodeInfo(..))
import GHC.Unit.Module.ModSummary (ms_mod_name, ms_hsc_src)
import GHC.Unit.State (wireMap, lookupUnitId, unwireUnit)
import System.Environment (getArgs)
import System.Exit (ExitCode(..), exitWith)
import System.IO (hSetEncoding, stdout, utf8)
import Numeric (showHex)
import System.FilePath ((</>))
import THC.Interface
import qualified THC.CoreStore.Binary as Store
import qualified THC.CoreStore.Inspect as Inspect

data Options = Options
  { libdir :: FilePath, unit :: String, moduleName :: String, interface :: FilePath
  , way :: String, databases :: [FilePath], sourceNotes :: Bool, inventoryProbe :: Bool
  , homeInterfaces :: Maybe FilePath
  , coreStore :: Maybe FilePath, inspectStore :: Maybe FilePath
  }

usage :: String
usage = "thc-interface --libdir DIR --unit UNIT --module MODULE --interface FILE " ++
  "[--way vanilla|dynamic|profiling] [--package-db DIR ...] [--source-notes] [--home-interfaces DIR] " ++
  "[--core-store FILE [--inspect-core-store JSONFILE]]"

parseOptions :: [String] -> Either String Options
parseOptions = go Map.empty [] False False
  where
    go values dbs notes probe [] = do
      let required key = maybe (Left ("Missing " ++ key)) Right (Map.lookup key values)
      l <- required "--libdir"
      u <- required "--unit"
      m <- if probe then pure "" else required "--module"
      i <- if probe then pure "" else required "--interface"
      unless (not probe || all (`Map.notMember` values) ["--module", "--interface"] && not notes)
        (Left "Inventory probe does not accept a single interface or source notes")
      let w = Map.findWithDefault "vanilla" "--way" values
      unless (w `elem` ["vanilla", "dynamic", "profiling"]) (Left "Unsupported --way")
      let home = Map.lookup "--home-interfaces" values
      unless (not probe || home == Nothing) (Left "Inventory probe requires registered packages")
      let store = Map.lookup "--core-store" values
          inspection = Map.lookup "--inspect-core-store" values
      unless (not probe || store == Nothing && inspection == Nothing)
        (Left "Inventory probe does not produce Core storage")
      unless (inspection == Nothing || store /= Nothing)
        (Left "--inspect-core-store requires --core-store")
      pure (Options l u m i w (reverse dbs) notes probe home store inspection)
    go values dbs False probe ("--source-notes":rest) = go values dbs True probe rest
    go values dbs notes False ("--probe-inventory":rest) = go values dbs notes True rest
    go values dbs notes probe ("--package-db":value:rest)
      | not (null value || "--" `isPrefixOf` value) = go values (value:dbs) notes probe rest
    go values dbs notes probe (key:value:rest)
      | key `elem` ["--libdir", "--unit", "--module", "--interface", "--way", "--home-interfaces", "--core-store", "--inspect-core-store"]
      , not (null value || "--" `isPrefixOf` value)
      , Map.notMember key values = go (Map.insert key value values) dbs notes probe rest
    go _ _ _ _ (argument:_) = Left ("Invalid, duplicate, or incomplete option: " ++ argument)

report :: Int -> Value -> IO a
report code value = BL.putStrLn (encode value) >> exitWith (ExitFailure code)

main :: IO ()
main = do
  hSetEncoding stdout utf8
  arguments <- getArgs
  case arguments of
    "--windows-ghc-source-graph":lib:source:objects:includes@(_:_) -> do
      graph <- windowsSourceGraph lib source objects includes
      BL.putStrLn (encode graph)
      exitWith ExitSuccess
    _ -> pure ()
  if arguments == ["--help"] then putStrLn usage else do
    options <- case parseOptions arguments of
      Left message -> report 2 $ object
        ["schema" .= (1 :: Int), "status" .= ("error" :: String), "category" .= ("usage" :: String),
         "message" .= message, "usage" .= usage]
      Right selected -> pure selected
    -- Do not turn interruption/cancellation into a reusable missing-capability
    -- result. Evaluate the complete payload before emitting any stdout bytes.
    result <- Exception.tryJust synchronous $ if inventoryProbe options
      then Just <$> probeSelected options
      else loadSelected options
    case result of
      Left failure -> report 1 $ object
        ["schema" .= (1 :: Int), "status" .= ("error" :: String), "category" .= ("interface" :: String),
         "message" .= Exception.displayException failure]
      Right Nothing -> report 3 $ object
        ["schema" .= (1 :: Int), "status" .= ("unavailable" :: String),
         "capability" .= ("complete-interface-core" :: String), "unit" .= unit options,
         "module" .= moduleName options, "way" .= way options, "interface" .= interface options]
      Right (Just output) -> BS.hPut stdout output
  where
    synchronous failure = case Exception.fromException failure :: Maybe Exception.SomeAsyncException of
      Just _ -> Nothing
      Nothing -> Just (failure :: Exception.SomeException)

-- Private protocol for the genuine vanilla ghc-internal build. GHC's own
-- dependency analysis preserves SOURCE imports and hs-boot ordering. Prim is
-- compiler-provided: its generated Haddock source has dummy bodies and must
-- never enter the executable source graph.
windowsSourceGraph :: FilePath -> FilePath -> FilePath -> [FilePath] -> IO Value
windowsSourceGraph lib source objects includes = runGhc (Just lib) $ do
  initial <- getSessionDynFlags
  environment <- getSession
  let arguments = ["-hide-all-packages", "-package", "rts", "-this-unit-id", "ghc-internal",
        "-XNoImplicitPrelude", "-XNoPolyKinds", "-DBIGNUM_GMP", "-D_WIN32_WINNT=0x06010000",
        "-i", "-i" ++ source, "-outputdir", objects] ++ map ("-I" ++) includes
  (flags, leftovers, _) <- parseDynamicFlags (hsc_logger environment) initial (map noLoc arguments)
  unless (null leftovers) (liftIO (fail "Unconsumed source graph options"))
  _ <- setSessionDynFlags flags
  targets <- mapM (\path -> guessTarget (source </> "GHC/Internal" </> path) Nothing Nothing)
    ["Data/Typeable/Internal.hs", "IO/Encoding/CodePage/API.hs", "IO/Encoding/CodePage.hs",
     "Exception.hs", "IO.hs", "Stack.hs", "Bignum/BigNat.hs"]
  setTargets targets
  graph <- depanal [mkModuleName "GHC.Internal.Prim"] False
  nodes <- forM (topSortModuleGraph False graph Nothing) $ \component -> case component of
    AcyclicSCC (ModuleNode _ (ModuleNodeCompile summary)) -> pure $ object
      ["module" .= moduleNameString (ms_mod_name summary), "boot" .= (ms_hsc_src summary == HsBootFile)]
    _ -> liftIO (fail "Unbroken cycle or unexpected node in ghc-internal source graph")
  pure $ object ["schema" .= (1 :: Int), "unit" .= ("ghc-internal" :: String), "nodes" .= nodes]

loadSelected :: Options -> IO (Maybe BS.ByteString)
loadSelected options = withSelected options $ \environment -> do
  expected <- case homeInterfaces options of
    Nothing -> resolveModule environment (unit options) (moduleName options)
    -- Cabal has not registered a library while its --make invocation is still
    -- running. Its explicit home unit and emitted .hi files supply ownership;
    -- loadInterfaceCore still checks the binary interface's exact Module.
    Just _ -> pure (mkModule (stringToUnit (unit options)) (mkModuleName (moduleName options)))
  loaded <- loadInterfaceCore environment expected (interface options)
  case loaded of
    Nothing -> pure Nothing
    Just core -> case coreStore options of
      Nothing -> do
        rendered <- interfaceCoreJSONBytes selected core
        output <- Exception.evaluate (BS.concat ["{\"schema\":1,\"status\":\"loaded\",\"core\":", rendered, "}\n"])
        pure (Just output)
      Just path -> do
        arena <- interfaceCoreStore selected core
        encoded <- either fail pure (Store.encodeStore arena)
        Store.writeEncoded path encoded
        case inspectStore options of
          Nothing -> pure ()
          Just outputPath -> either fail (BS.writeFile outputPath) (Inspect.inspectBytes Nothing arena)
        pure $ Just $ BL.toStrict $ encode $ object
          ["schema" .= (1::Int),"status" .= ("loaded"::String),"storage" .= object
            ["format" .= ("thc-core-store"::String),"schema" .= (1::Int),"path" .= path,
             "bytes" .= Store.encodedLength encoded,
             "indexBytes" .= BS.length (Store.encodedHeaderIndex encoded),
             "indexSha256" .= concatMap hex (BS.unpack (Store.encodedIndexSHA256 encoded))]]
  where
    selected = ["unit-qualified"] ++ ["source-notes" | sourceNotes options]
    hex byte = let value = showHex byte "" in replicate (2 - length value) '0' ++ value

-- Private, versioned batch protocol used by the installed-bundle cache. Each
-- entry is checked using the same selected package state as ordinary loading.
data ProbeEntry = ProbeEntry String String FilePath
data ProbeRequest = ProbeRequest [String] [ProbeEntry]

instance FromJSON ProbeEntry where
  parseJSON = withObject "interface probe entry" $ \fields ->
    ProbeEntry <$> fields .: "unit" <*> fields .: "module" <*> fields .: "interface"

instance FromJSON ProbeRequest where
  parseJSON = withObject "interface probe inventory" $ \fields ->
    ProbeRequest <$> fields .: "units" <*> fields .: "interfaces"

probeSelected :: Options -> IO BS.ByteString
probeSelected options = do
  ProbeRequest identifiers entries <- either fail pure . eitherDecode =<< BL.getContents
  withSelected options $ \environment -> do
    resolvedUnits <- mapM (resolveUnit environment) identifiers
    resolvedModules <- mapM (\(ProbeEntry identifier name _) -> resolveModule environment identifier name) entries
    let units = Set.fromList (identifiers ++ map unitString resolvedUnits)
        modules = Set.fromList ([(identifier, name) | ProbeEntry identifier name _ <- entries] ++
          [(unitString (moduleUnit m), name) | (ProbeEntry _ name _, m) <- zip entries resolvedModules])
    rows <- forM (zip entries resolvedModules) $ \(ProbeEntry identifier name path, expected) -> do
      (digest, complete) <- probeInterface environment units modules expected path
      pure $ object ["unit" .= identifier, "module" .= name, "interface" .= path,
        "owner" .= unitString (moduleUnit expected), "fingerprint" .= show digest, "completeCore" .= complete]
    -- Aeson already emits UTF-8. Char8.unpack followed by putStrLn would
    -- encode those bytes a second time, corrupting non-ASCII interface paths
    -- and making the driver's exact inventory check reject every cache hit.
    let output = BS.snoc (BL.toStrict (encode (object ["schema" .= (1 :: Int),
          "status" .= ("probed" :: String), "interfaces" .= rows]))) 10
    Exception.evaluate output

withSelected :: Options -> (HscEnv -> IO a) -> IO a
withSelected options action = runGhc (Just (libdir options)) $ do
  original <- getSessionDynFlags
  initial <- getSession
  let wayFlags = case way options of
        "dynamic" -> ["-dynamic"]
        "profiling" -> ["-prof"]
        _ -> []
      flags = ["-clear-package-db", "-global-package-db", "-package-env", "-", "-fno-ignore-interface-pragmas"] ++
        concatMap (\database -> ["-package-db", database]) (databases options) ++
        (case homeInterfaces options of
          Nothing -> ["-package-id", unit options]
          Just directory -> ["-this-unit-id", unit options, "-i", "-i" ++ directory]) ++ wayFlags
  (selected,leftovers,_) <- parseDynamicFlags (hsc_logger initial) original (map noLoc flags)
  unless (null leftovers) (liftIO (ioError (userError "Unconsumed interface helper flags")))
  _ <- setSessionDynFlags (case homeInterfaces options of Nothing -> selected; Just _ -> selected {ghcMode=OneShot})
  environment <- getSession
  liftIO (action environment)

resolveModule :: HscEnv -> String -> String -> IO Module
resolveModule environment identifier name = (\selected -> mkModule selected (mkModuleName name)) <$> resolveUnit environment identifier

resolveUnit :: HscEnv -> String -> IO Unit
resolveUnit environment identifier = do
    -- Package flags take the exact registration key, whereas interface owners
    -- use GHC's wired identity. Resolve through this session, never by stripping
    -- a version/hash or by treating any similarly named package as equivalent.
    let units = hsc_units environment
        registered = stringToUnitId identifier
        selectedId = fromMaybe registered (lookupUniqMap (wireMap units) registered)
    info <- maybe (ioError (userError "Requested registration is not in the selected GHC unit state"))
      pure (lookupUnitId units selectedId)
    let selectedUnit = mkUnit info
    unless (unwireUnit units selectedUnit == stringToUnit identifier)
      (ioError (userError "Selected GHC unit does not map back to the exact requested registration"))
    pure selectedUnit
