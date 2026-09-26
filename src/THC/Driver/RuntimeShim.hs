-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Exact native fallback declarations for built-in THC runtime services.
-- Native products remain recorded inputs; their code is not a guest provider.
module THC.Driver.RuntimeShim
  ( RuntimeShim, runtimeShimInputs, withRuntimeShim, validateRuntimeShimModules
  , validateRuntimeShimModule, validateRuntimeShimInventory
  ) where

import Control.Monad (forM, forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', object, toJSON, (.=), fromJSON, Result(..))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import Data.List (nub, sort)
import qualified Data.Text as Text
import Numeric (showHex)
import System.Directory (canonicalizePath)
import System.FilePath
import THC.Driver.NativeRecipe

data RuntimeShim = RuntimeShim
  { runtimeShimInputs :: Value
  , runtimeShimUnit :: String
  }

withRuntimeShim :: FilePath -> FilePath -> [FilePath] -> FilePath -> String -> Value -> (RuntimeShim -> IO a) -> IO a
withRuntimeShim native dist roots compiler unit component action = do
  selected <- componentRuntimeShim component
  check selected "runtime shim profile was not selected"
  root <- get component "src-dir" >>= canonicalizePath
  declarations <- componentNativeDeclarations component
  sources <- mapM (canonicalizePath . (root </>)) declarations
  objects <- componentNativeObjects native dist roots component
  recipes <- forM objects $ \path ->
    readNativeRecipe (native </> "cache/thc/native-recipes-v1") compiler path >>=
      maybe (fail ("runtime shim: missing or stale native compiler receipt: " ++ path)) pure
  let vanilla = [recipeSource recipe | recipe <- recipes, takeExtension (recipeObject recipe) == ".o"]
  check (not (null sources) && sort vanilla == sort sources && length vanilla == length (nub vanilla))
    "runtime shim requires one observed vanilla native object per declared C source"
  forM_ recipes $ \recipe -> do
    check (recipeDirectory recipe == root && recipeSource recipe `elem` sources &&
      takeExtension (recipeObject recipe) `elem` [".o", ".dyn_o"])
      "runtime shim native output does not belong to its declared C translation units"
  observed <- forM (sort (nub (sources ++ objects))) $ \path -> do
    digest <- hashFile path
    pure (path, digest)
  let proof = object
        [ "schema" .= (1 :: Int), "profile" .= ("thc-runtime-services-v1" :: String)
        , "unit" .= unit
        , "nativeRecipes" .= map toJSON recipes
        , "inputs" .= [object ["path" .= path, "sha256" .= digest] | (path, digest) <- observed]
        ]
  result <- action (RuntimeShim proof unit)
  forM_ observed $ \(path, expected) -> do
    actual <- hashFile path
    check (actual == expected) ("runtime shim input changed during acquisition: " ++ path)
  pure result

validateRuntimeShimModules :: RuntimeShim -> [(String, BS.ByteString)] -> IO [(String, BS.ByteString)]
validateRuntimeShimModules shim modules = do
  values <- forM modules $ \(_, bytes) -> either fail pure (eitherDecodeStrict' bytes)
  either fail pure (validateRuntimeShimInventory (runtimeShimUnit shim) values)
  pure modules

validateRuntimeShimInventory :: String -> [Value] -> Either String ()
validateRuntimeShimInventory unit values = do
  inventories <- mapM (validateRuntimeShimModule unit) values
  let (declarations, calls) = unzip inventories
      names = concat declarations
  require (not (null names)) "runtime shim has no verified reserved runtime imports"
  -- Inlining may move a verified call into another module of this same unit.
  require (all (`elem` names) (concat calls)) "runtime shim Core call lacks its verified import declaration"

-- All five names have target-defined semantics before any package C dispatch.
-- There is no prefix match and no admission based on a package/module name.
signatures :: [(String, ([String], String))]
signatures =
  [ ("thc_cpu_affinity_v1_support", ([], "Int32Rep"))
  , ("thc_cpu_affinity_v1_applied", ([], "Int32Rep"))
  , ("thc_runtime_v1_query", (["Int32Rep", "Int64Rep", "Int64Rep"], "Int64Rep"))
  , ("thc_runtime_v1_control", (["Int32Rep", "Int64Rep"], "Int64Rep"))
  , ("thc_runtime_v1_trace", (["Int32Rep", "Int64Rep", "AddrRep", "Int64Rep"], "Int64Rep"))
  ]

validateRuntimeShimModule :: String -> Value -> Either String ([String], [String])
validateRuntimeShimModule unit value = do
  require (member value "unit" == Just (String (Text.pack unit))) "runtime shim module owner mismatch"
  require (member value "foreign" == Nothing) "runtime shim has native export/stub obligations"
  -- The exporter intentionally omits Verified [] records for pure modules.
  -- Absence supplies no declarations; the whole-unit check must still match
  -- every actual (possibly inlined) call to a verified nonempty declaration.
  imports <- case member value "staticForeignImports" of
    Nothing -> Right []
    Just proof -> do
      require (member proof "schema" == Just (Number 1) && member proof "status" == Just (String "verified") &&
        member proof "unit" == Just (String (Text.pack unit))) "runtime shim typed import provenance is not verified"
      field proof "imports"
  names <- mapM validateImport imports
  -- Check actual Core call descriptors too: a declaration inventory must not
  -- conceal a generated, dynamic, foreign-label or differently typed call.
  actual <- mapM validateDescriptor (descriptors value)
  pure (names, actual)
  where
    validateImport entry = do
      call <- field entry "emitted"
      symbol <- field call "symbol"
      (arguments, result) <- maybe (Left ("non-runtime foreign import in runtime shim: " ++ symbol)) Right (lookup symbol signatures)
      require (member entry "isFunction" == Just (Bool True) && member entry "header" == Just Null &&
        member call "unit" == Just (String (Text.pack unit)) &&
        member call "convention" == Just (String "ccall") && member call "safety" == Just (String "unsafe") &&
        member call "arguments" == Just (toJSON (arguments ++ ["void"])) &&
        member call "result" == Just (toJSON ["void", result]))
        ("runtime shim import has the wrong exact ABI: " ++ symbol)
      pure symbol
    validateDescriptor descriptor = do
      target <- field descriptor "target"
      symbol <- field target "symbol"
      (arguments, result) <- maybe (Left ("non-runtime Core foreign call in runtime shim: " ++ symbol)) Right (lookup symbol signatures)
      inputs <- field descriptor "argumentReps" :: Either String [Value]
      output <- field descriptor "resultRep"
      components <- field output "components" :: Either String [Value]
      require (member descriptor "schema" == Just (Number 1) &&
        member target "kind" == Just (String "static") && member target "isFunction" == Just (Bool True) &&
        member target "unit" == Just (String (Text.pack unit)) &&
        member descriptor "convention" == Just (String "ccall") && member descriptor "safety" == Just (String "unsafe") &&
        member descriptor "arity" == Just (toJSON (length arguments + 1)) &&
        member descriptor "suppliedArity" == Just (toJSON (length arguments + 1)) &&
        map (`member` "primReps") inputs == map (Just . toJSON) (map (:[]) arguments ++ [[]]) &&
        member output "aggregate" == Just (String "unboxed-tuple") &&
        map (`member` "primReps") components == map (Just . toJSON) [[], [result]])
        ("runtime shim Core call has the wrong exact ABI: " ++ symbol)
      pure symbol

descriptors :: Value -> [Value]
descriptors value@(Object fields)
  | KM.member "target" fields && KM.member "convention" fields = [value]
  | otherwise = concatMap descriptors (KM.elems fields)
descriptors (Array values) = concatMap descriptors values
descriptors _ = []

require :: Bool -> String -> Either String ()
require condition message = if condition then Right () else Left message
check :: Bool -> String -> IO ()
check condition message = unless condition (fail message)
member :: Value -> String -> Maybe Value
member (Object fields) key = KM.lookup (Key.fromString key) fields
member _ _ = Nothing
field :: FromJSON a => Value -> String -> Either String a
field value key = case member value key of
  Nothing -> Left ("runtime shim: missing " ++ key)
  Just item -> case fromJSON item of Success result -> Right result; Error message -> Left message
get :: FromJSON a => Value -> String -> IO a
get value key = either fail pure (field value key)
hashFile :: FilePath -> IO String
hashFile path = concatMap byte . BS.unpack . SHA.hash <$> BS.readFile path
  where byte value = let text = showHex value "" in if length text == 1 then '0' : text else text
