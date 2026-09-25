-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module Main (main) where

import qualified Control.Exception as Exception
import Control.DeepSeq (force)
import Control.Monad (forM, unless)
import Data.Aeson (Value, FromJSON(..), object, (.=), (.:), encode, eitherDecode, withObject)
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (isPrefixOf)
import Data.Maybe (fromMaybe)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import GHC (getSessionDynFlags, getSession, parseDynamicFlags, setSessionDynFlags, runGhc, noLoc)
import GHC.Plugins (HscEnv, Module, Unit, hsc_logger, mkModule, mkModuleName, moduleUnit,
                    unitString, stringToUnit, stringToUnitId, liftIO)
import GHC.Driver.Env (hsc_units)
import GHC.Types.Unique.Map (lookupUniqMap)
import GHC.Unit.Info (mkUnit)
import GHC.Unit.State (wireMap, lookupUnitId, unwireUnit)
import System.Environment (getArgs)
import System.Exit (ExitCode(..), exitWith)
import System.IO (hSetEncoding, stdout, utf8)
import THC.Interface

data Options = Options
  { libdir :: FilePath, unit :: String, moduleName :: String, interface :: FilePath
  , way :: String, databases :: [FilePath], sourceNotes :: Bool, inventoryProbe :: Bool
  }

usage :: String
usage = "thc-interface --libdir DIR --unit UNIT --module MODULE --interface FILE " ++
  "[--way vanilla|dynamic|profiling] [--package-db DIR ...] [--source-notes]"

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
      pure (Options l u m i w (reverse dbs) notes probe)
    go values dbs False probe ("--source-notes":rest) = go values dbs True probe rest
    go values dbs notes False ("--probe-inventory":rest) = go values dbs notes True rest
    go values dbs notes probe ("--package-db":value:rest)
      | not (null value || "--" `isPrefixOf` value) = go values (value:dbs) notes probe rest
    go values dbs notes probe (key:value:rest)
      | key `elem` ["--libdir", "--unit", "--module", "--interface", "--way"]
      , not (null value || "--" `isPrefixOf` value)
      , Map.notMember key values = go (Map.insert key value values) dbs notes probe rest
    go _ _ _ _ (argument:_) = Left ("Invalid, duplicate, or incomplete option: " ++ argument)

report :: Int -> Value -> IO a
report code value = BL.putStrLn (encode value) >> exitWith (ExitFailure code)

main :: IO ()
main = do
  hSetEncoding stdout utf8
  arguments <- getArgs
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
      else fmap (\core -> "{\"schema\":1,\"status\":\"loaded\",\"core\":" ++ core ++ "}") <$> loadSelected options
    case result of
      Left failure -> report 1 $ object
        ["schema" .= (1 :: Int), "status" .= ("error" :: String), "category" .= ("interface" :: String),
         "message" .= Exception.displayException failure]
      Right Nothing -> report 3 $ object
        ["schema" .= (1 :: Int), "status" .= ("unavailable" :: String),
         "capability" .= ("complete-interface-core" :: String), "unit" .= unit options,
         "module" .= moduleName options, "way" .= way options, "interface" .= interface options]
      Right (Just output) -> putStrLn output
  where
    synchronous failure = case Exception.fromException failure :: Maybe Exception.SomeAsyncException of
      Just _ -> Nothing
      Nothing -> Just (failure :: Exception.SomeException)

loadSelected :: Options -> IO (Maybe String)
loadSelected options = withSelected options $ \environment -> do
  expected <- resolveModule environment (unit options) (moduleName options)
  loaded <- loadInterfaceCore environment expected (interface options)
  case loaded of
    Nothing -> pure Nothing
    Just core -> do
      rendered <- interfaceCoreJSON (["unit-qualified"] ++ ["source-notes" | sourceNotes options]) core
      _ <- Exception.evaluate (force rendered)
      pure (Just rendered)

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

probeSelected :: Options -> IO String
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
    let output = BL.unpack (encode (object ["schema" .= (1 :: Int),
          "status" .= ("probed" :: String), "interfaces" .= rows]))
    Exception.evaluate (force output)

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
        ["-package-id", unit options] ++ wayFlags
  (selected,leftovers,_) <- parseDynamicFlags (hsc_logger initial) original (map noLoc flags)
  unless (null leftovers) (liftIO (ioError (userError "Unconsumed interface helper flags")))
  _ <- setSessionDynFlags selected
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
