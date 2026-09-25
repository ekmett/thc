-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module Main (main) where

import qualified Control.Exception as Exception
import Control.Monad (unless)
import Data.Aeson (Value, object, (.=), encode)
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (isPrefixOf)
import qualified Data.Map.Strict as Map
import GHC (getSessionDynFlags, getSession, parseDynamicFlags, setSessionDynFlags, runGhc, noLoc)
import GHC.Plugins (hsc_logger, mkModule, mkModuleName, stringToUnit, liftIO)
import System.Environment (getArgs)
import System.Exit (ExitCode(..), exitWith)
import System.IO (hSetEncoding, stdout, utf8)
import THC.Interface

data Options = Options
  { libdir :: FilePath, unit :: String, moduleName :: String, interface :: FilePath
  , way :: String, databases :: [FilePath], sourceNotes :: Bool
  }

usage :: String
usage = "thc-interface --libdir DIR --unit UNIT --module MODULE --interface FILE " ++
  "[--way vanilla|dynamic|profiling] [--package-db DIR ...] [--source-notes]"

parseOptions :: [String] -> Either String Options
parseOptions = go Map.empty [] False
  where
    go values dbs notes [] = do
      let required key = maybe (Left ("Missing " ++ key)) Right (Map.lookup key values)
      l <- required "--libdir"
      u <- required "--unit"
      m <- required "--module"
      i <- required "--interface"
      let w = Map.findWithDefault "vanilla" "--way" values
      unless (w `elem` ["vanilla", "dynamic", "profiling"]) (Left "Unsupported --way")
      pure (Options l u m i w (reverse dbs) notes)
    go values dbs False ("--source-notes":rest) = go values dbs True rest
    go values dbs notes ("--package-db":value:rest)
      | not (null value || "--" `isPrefixOf` value) = go values (value:dbs) notes rest
    go values dbs notes (key:value:rest)
      | key `elem` ["--libdir", "--unit", "--module", "--interface", "--way"]
      , not (null value || "--" `isPrefixOf` value)
      , Map.notMember key values = go (Map.insert key value values) dbs notes rest
    go _ _ _ (argument:_) = Left ("Invalid, duplicate, or incomplete option: " ++ argument)

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
    result <- Exception.tryJust synchronous (loadSelected options)
    case result of
      Left failure -> report 1 $ object
        ["schema" .= (1 :: Int), "status" .= ("error" :: String), "category" .= ("interface" :: String),
         "message" .= Exception.displayException failure]
      Right Nothing -> report 3 $ object
        ["schema" .= (1 :: Int), "status" .= ("unavailable" :: String),
         "capability" .= ("complete-interface-core" :: String), "unit" .= unit options,
         "module" .= moduleName options, "way" .= way options, "interface" .= interface options]
      Right (Just core) -> putStr ("{\"schema\":1,\"status\":\"loaded\",\"core\":" ++ core ++ "}\n")
  where
    synchronous failure = case Exception.fromException failure :: Maybe Exception.SomeAsyncException of
      Just _ -> Nothing
      Nothing -> Just (failure :: Exception.SomeException)

loadSelected :: Options -> IO (Maybe String)
loadSelected options = runGhc (Just (libdir options)) $ do
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
  liftIO $ do
    let expected = mkModule (stringToUnit (unit options)) (mkModuleName (moduleName options))
    loaded <- loadInterfaceCore environment expected (interface options)
    case loaded of
      Nothing -> pure Nothing
      Just core -> do
        rendered <- interfaceCoreJSON (["unit-qualified"] ++ ["source-notes" | sourceNotes options]) core
        _ <- Exception.evaluate (length rendered)
        pure (Just rendered)
