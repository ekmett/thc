-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalIconvFixtures (prepareOriginalIconv) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecode, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Data.List (nubBy)
import qualified Data.Map.Strict as Map
import FixtureSupport
import GHC hiding (entry, load)
import GHC.Plugins hiding (line)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Iface.Binary
import GHC.Driver.Main (hscSimplify)
import GHC.Types.TypeEnv (typeEnvTyCons)
import GHC.Unit.Module.ModDetails (md_types)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Interface
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Text.Read (readMaybe)

directory :: FilePath
directory = "build/original-iconv"
symbols, entries :: [String]
symbols = ["localeEncoding", "hs_iconv_open", "hs_iconv_close", "hs_iconv"]
entries = ["originalLocale", "originalIconvOpen", "originalIconvClose", "originalIconv"]
target :: Id -> Maybe String
target v = case isFCallId_maybe v of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ symbol _ True) F.CCallConv F.PlayRisky))
    | unpackFS symbol `elem` symbols -> Just (unpackFS symbol)
  _ -> Nothing
variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var v | isId v -> [v]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let bindings body -> concatMap (variables . snd) (flattenBinds [bindings]) ++ variables body
  Case scrutinee _ _ alternatives -> variables scrutinee ++ concat [variables body | Alt _ _ body <- alternatives]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []
mapBind :: (CoreExpr -> CoreExpr) -> CoreBind -> CoreBind
mapBind f (NonRec v rhs) = NonRec v (f rhs)
mapBind f (Rec pairs) = Rec [(v, f rhs) | (v, rhs) <- pairs]
replace :: [Id] -> CoreExpr -> CoreExpr
replace originals expression = case expression of
  Var v | Just symbol <- target v -> case [o | o <- originals, target o == Just symbol] of
    [o] | eqType (idType v) (idType o) -> Var o
    _ -> error "Original iconv FCallId/type missing or incompatible"
  App f x -> App (replace originals f) (replace originals x)
  Lam v body -> Lam v (replace originals body)
  Let bindings body -> Let (mapBind (replace originals) bindings) (replace originals body)
  Case scrutinee v ty alternatives -> Case (replace originals scrutinee) v ty
    [Alt con binders (replace originals body) | Alt con binders body <- alternatives]
  Cast body coercion -> Cast (replace originals body) coercion
  Tick tick body -> Tick tick (replace originals body)
  _ -> expression
foreignApps :: Value -> [Value]
foreignApps value = case value of
  Array xs -> (case toList xs of
    [String "app", _, _, _, _, _, Object meta] | KM.member "foreignCall" meta -> [value]
    _ -> []) ++ concatMap foreignApps (toList xs)
  Object xs -> concatMap foreignApps (KM.elems xs)
  _ -> []

type Observation = (Int, Int, Int, Int, [Int])
type Row = (String, String, String, [Int], Int, Observation, [Int], Observation, Observation, Observation, Int)
observation :: Observation -> Value
observation (result, err, consumed, produced, bytes) = object
  ["result" .= result, "errno" .= err, "consumed" .= consumed, "produced" .= produced, "bytes" .= bytes]

change :: Key.Key -> (Value -> Value) -> Value -> Value
change key f (Object fields) = case KM.lookup key fields of
  Just value -> Object (KM.insert key (f value) fields)
  Nothing -> error "Missing foreign descriptor field"
change _ _ _ = error "Expected foreign descriptor object"
rewrite :: (Value -> Value) -> Value -> Value
rewrite f (Object fields) = Object $ KM.mapWithKey (\key value ->
  if key == "foreignCall" then f value else rewrite f value) fields
rewrite f (Array values) = Array (fmap (rewrite f) values)
rewrite _ value = value
badFlags :: Value -> Value
badFlags (Array values) = case map badFlags (toList values) of
  [String "app", fn, args, Array flags, x, y, meta@(Object fields)] | KM.member "foreignCall" fields ->
    toJSON [String "app", fn, args, Array (fmap (const (Bool True)) flags), x, y, meta]
  children -> toJSON children
badFlags (Object fields) = Object (fmap badFlags fields)
badFlags value = value
negativeCases :: [(String, Value -> Value)]
negativeCases = [("wrong-unit", rewrite (change "target" (change "unit" (const (String "main")))))
  ,("dynamic", rewrite (change "target" (change "kind" (const (String "dynamic")))))
  ,("non-function", rewrite (change "target" (change "isFunction" (const (Bool False)))))
  ,("wrong-convention", rewrite (change "convention" (const (String "capi"))))
  ,("wrong-safety", rewrite (change "safety" (const (String "safe"))))
  ,("wrong-arity", rewrite (change "arity" (const (Number 0))))
  ,("wrong-saturation", rewrite (change "suppliedArity" (const (Number 0))))
  ,("boolean-schema", rewrite (change "schema" (const (Bool True))))
  ,("wrong-result", rewrite (change "resultRep" (change "primReps" (const (toJSON ["IntRep" :: String])))))
  ,("declared-evaluated", rewrite (change "argumentReps" (\v -> case v of
      Array xs -> Array (fmap (change "evaluated" (const (Bool True))) xs)
      _ -> error "Expected representations")))
  ,("wrong-cbv", badFlags)]

prepareOriginalIconv :: FilePath -> IO ()
prepareOriginalIconv root = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  createDirectoryIfMissing True (root </> directory </> "native")
  let execute label program args = runLogged 300 root (directory </> "logs") label [] program args
      oneLine result = case lines (BS.unpack (commandStdout result)) of [line] -> line; _ -> error "Expected one line"
      source = "compiler/test-fixtures/OriginalIconvAudit.hs"
      driver = "compiler/test-fixtures/OriginalIconvAuditNative.hs"
      binary = directory </> "native/oracle"
      adaptedPath = directory </> "OriginalIconvAudit.json"
      prePath = directory </> "PreIconvAudit.json"
  version <- execute "version" ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Original iconv fixture requires GHC9.14.1")
  libdir <- execute "libdir" ghc ["--print-libdir"]
  imports <- execute "imports" ghcPkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  compiled <- execute "native-build" ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-fwrite-if-simplified-core", "-package", "ghc-internal", "-i" ++ root </> "compiler/test-fixtures",
    "-odir", root </> directory </> "native", "-hidir", root </> directory </> "native", driver, "-o", root </> binary]
  observed <- execute "native-oracle" (root </> binary) []
  (locale, missing, missingErrno, rows) <- maybe (die "Malformed native iconv oracle") pure
    (readMaybe (BS.unpack (commandStdout observed)) :: Maybe (String, Int, Int, [Row]))
  unless (length rows == 10 && missing == -1 && missingErrno > 0) (die "Incomplete native iconv controls")
  let oraclePath = directory </> "oracle.json"
  writeJson (root </> oraclePath) $ object ["locale" .= locale, "missing" .= missing, "missingErrno" .= missingErrno,
    "rows" .= [object ["label" .= label, "to" .= to, "from" .= from, "input" .= input, "capacity" .= capacity,
      "first" .= observation first, "continuationInput" .= continuationInput, "continued" .= observation continued,
      "smallReset" .= observation smallReset, "reset" .= observation reset, "closed" .= closed]
      | (label, to, from, input, capacity, first, continuationInput, continued, smallReset, reset, closed) <- rows]]
  runGhc (Just (oneLine libdir)) $ do
    initial <- getSessionDynFlags
    initialEnvironment <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger initialEnvironment) initial (map noLoc ["-O2", "-package", "ghc-internal",
      "-odir", root </> directory </> "pre-ghc", "-hidir", root </> directory </> "pre-ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    environment <- getSession
    originalIds <- liftIO $ do
      let installed = oneLine imports </> "GHC/Internal/IO/Encoding/Iconv.hi"
          templatePath = root </> directory </> "native/OriginalIconvAudit.hi"
          load path = do
            raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
            maybe (die ("Missing full Core in selected GHC9.14.1 interface: " ++ path)) pure =<<
              loadInterfaceCore environment (mi_module raw) path
      original <- load installed
      unless (moduleNameString (moduleName (interfaceModule original)) == "GHC.Internal.IO.Encoding.Iconv" &&
        unitString (moduleUnit (interfaceModule original)) == "ghc-internal") (die "Wrong original iconv owner")
      template <- load templatePath
      let originals = nubBy (\a b -> target a == target b)
            [v | (_, body) <- flattenBinds (interfaceBindings original), v <- variables body, target v /= Nothing]
          adapted = map (mapBind (replace originals)) (interfaceBindings template)
          calls = [v | (_, body) <- flattenBinds adapted, v <- variables body, target v /= Nothing]
      unless (length originals == 4 && length calls == 4 && all (`elem` originals) calls &&
        all (not . isExternalName . varName) originals) (die "Original iconv private Id inventory mismatch")
      originalText <- interfaceCoreJSON ["unit-qualified"] original
      originalJSON <- either die pure (eitherDecode (BL.fromStrict (BS.pack originalText)))
      writeJson (root </> directory </> "declarations.json") $ object
        ["completeModule" .= False, "originalInterface" .= installed, "calls" .= foreignApps originalJSON,
         "installedArtifactsHashed" .= False, "typeEqualityChecked" .= True, "originalIdentityChecked" .= True]
      adaptedText <- serializePostTidyCore flags ["unit-qualified"] (interfaceModule template)
        (typeEnvTyCons (md_types (interfaceDetails template))) adapted (interfaceForeign template)
      writeFile (root </> adaptedPath) adaptedText
      pure originals
    -- The source goes through GHC's actual parser/typechecker/desugarer and
    -- optimizer, stopping before Tidy. Substitution still uses Id/type equality,
    -- never JSON edits, invented symbols or reconstructed Core.
    sourceTarget <- guessTarget (root </> source) Nothing Nothing
    setTargets [sourceTarget]
    graph <- depanal [] False
    summary <- case [ms | ms <- mgModSummaries graph, moduleNameString (moduleName (ms_mod ms)) == "OriginalIconvAudit"] of
      [ms] -> pure ms
      _ -> liftIO (die "Missing unique original iconv source module summary")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    sourceEnvironment <- getSession
    optimized <- liftIO $ hscSimplify sourceEnvironment [] (coreModule desugared)
    let adaptedGuts = optimized { mg_binds = map (mapBind (replace originalIds)) (mg_binds optimized) }
        calls = [v | (_, body) <- flattenBinds (mg_binds adaptedGuts), v <- variables body, target v /= Nothing]
    liftIO $ do
      unless (all (`elem` originalIds) calls && all (\symbol -> any ((== Just symbol) . target) calls) symbols)
        (die "Pre-Tidy consumer lost original iconv identities")
      serializeOptimizedCore flags ["unit-qualified"] adaptedGuts >>= writeFile (root </> prePath)
  audits <- forM entries $ \entry -> do
    let path = directory </> entry ++ ".audit.json"
    command <- execute ("audit-" ++ entry) "python3" ["scripts/audit-core.py", adaptedPath, "--entry", entry, "--output", path]
    pure (entry, path, command)
  preAudits <- forM entries $ \entry -> do
    let path = directory </> "pre-" ++ entry ++ ".audit.json"
    command <- execute ("pre-audit-" ++ entry) "python3" ["scripts/audit-core.py", prePath, "--entry", entry, "--output", path]
    pure ("pre-" ++ entry, path, command)
  adaptedJSON <- either die pure . eitherDecode =<< BL.readFile (root </> adaptedPath)
  createDirectoryIfMissing True (root </> directory </> "negative")
  negatives <- fmap concat $ forM negativeCases $ \(label, mutate) -> do
    let path = directory </> "negative" </> label ++ ".json"
        malformed = mutate adaptedJSON
    unless (malformed /= adaptedJSON) (die "Iconv negative fixture did not mutate")
    writeJson (root </> path) malformed
    forM entries $ \entry -> do
      let output = directory </> "negative" </> label ++ "-" ++ entry ++ ".audit.json"
      command <- runLoggedExpect 1 180 root (directory </> "logs") (label ++ "-" ++ entry) [] "python3"
        ["scripts/audit-core.py", path, "--entry", entry, "--output", output]
      report <- either die pure . eitherDecode =<< BL.readFile (root </> output)
      case report of
        Object fields | KM.lookup "accepted" fields == Just (Bool False),
          Just (Array issues) <- KM.lookup "issues" fields,
          any (\issue -> case issue of Object xs -> KM.lookup "code" xs == Just (String "foreign-call"); _ -> False) issues -> pure ()
        _ -> die "Iconv negative audit did not reject the foreign contract"
      pure (path, output, command)
  let inputs = [source, driver, "test/haskell-fixtures/OriginalIconvFixtures.hs", "compiler/THC/Interface.hs",
        "compiler/THC/Plugin.hs", "scripts/core_original_foreign.py", "scripts/audit-core.py", "scripts/core-capabilities.json"]
      artifacts = [oraclePath, adaptedPath, prePath, directory </> "declarations.json"] ++ [p | (_,p,_) <- audits ++ preAudits] ++
        concat [[input, output] | (input, output, _) <- negatives]
      commands = [version, libdir, imports, compiled, observed] ++ [c | (_,_,c) <- audits ++ preAudits] ++ [c | (_,_,c) <- negatives]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object ["schema" .= (1 :: Int), "entries" .= entries,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "nativeRows" .= length rows,
    "negativeAudits" .= length negatives,
    "audits" .= Map.fromList [(entry,path) | (entry,path,_) <- audits ++ preAudits], "commands" .= map commandRecord commands]
  putStrLn "original-iconv: four installed FCallIds, ten native cases, eight strict pre/post entries,44 rejected ABI controls"
