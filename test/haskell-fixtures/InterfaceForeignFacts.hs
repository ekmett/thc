-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InterfaceForeignFacts (prepareForeignAssociation, prepareTypedForeignAssociation, inspectInstalledBound) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), object, (.=), eitherDecodeStrict')
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (isInfixOf, sortOn)
import qualified Data.Map.Strict as Map
import Data.Maybe (isJust, mapMaybe)
import qualified Data.Text as Text
import GHC hiding (exprType)
import GHC.Plugins
import GHC.Cmm.CLabel (mkClosureLabel, pprCLabel)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.Iface.Ext.Fields (getExtensibleFields)
import GHC.Tc.Utils.TcType (tcSplitPiTys, tcSplitIOType_maybe)
import GHC.Types.RepType (typePrimRep)
import GHC.Types.TypeEnv (typeEnvTyCons)
import GHC.Unit.Module.ModDetails (md_types, md_anns)
import qualified GHC.Unit.Module.WholeCoreBindings as ForeignCore
import System.Directory (copyFile, createDirectoryIfMissing, doesDirectoryExist, renameFile)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory)
import FixtureSupport (CommandResult, runLogged, writeJson)
import THC.Interface
import THC.Plugin (serializePostTidyCoreWithAnnotations)

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

-- Facts about ALL external Core binders, not a foreign-export classifier.
-- Neither a $fstable occurrence nor an external Core Name proves a C export.
bindingFacts :: DynFlags -> CoreProgram -> Value
bindingFacts flags bindings = toValue
  [v | (v, _) <- flattenBinds bindings, isExternalName (varName v)]
  where
    render = showSDoc flags
    rep ty = object ["type" .= render (ppr ty),
                     "primReps" .= map (render . ppr) (typePrimRep ty)]
    fact v = let (arguments, result) = tcSplitPiTys (idType v) in object
      ["name" .= getOccString v, "type" .= render (ppr (idType v)),
       "details" .= render (ppr (idDetails v)),
       "closureLabel" .= closureLabel flags v,
       "arguments" .= map rep (mapMaybe anonPiTyBinderType_maybe arguments),
       "result" .= rep result, "resultIsIO" .= isJust (tcSplitIOType_maybe result)]
    toValue vs = object ["binders" .= map fact (sortOn getOccString vs)]

closureLabel :: DynFlags -> Id -> String
closureLabel flags v = showSDoc flags
  (pprCode (pprCLabel (targetPlatform flags) (mkClosureLabel (varName v) (idCafInfo v))))

declaredIn :: DynFlags -> String -> Id -> Bool
declaredIn flags source v = ("extern StgClosure " ++ closureLabel flags v ++ ";") `isInfixOf` source

stubText :: InterfaceCore -> IO (String, String)
stubText core = case interfaceForeign core of
  ForeignCore.IfaceForeign (Just (ForeignCore.IfaceCStubs header source initializers finalizers)) files -> do
    check (length initializers == 1 && null finalizers && null files)
      "Association control changed its registration obligations"
    pure (header, source)
  _ -> die "Association control lost its real foreign stub"

-- A counterexample to reconstructing a C symbol from typed interface binders:
-- the typed declarations, exports, Core binder facts and GHC closure labels
-- agree while the two real C exports differ. No raw-C parser is implemented.
prepareForeignAssociation :: FilePath -> FilePath -> FilePath -> FilePath -> String -> IO [CommandResult]
prepareForeignAssociation root directory ghc libdir unitName = do
  let source = directory </> "source/InterfaceForeignAlias.hs"
      variants = [("a", "thc_interface_alias_a"), ("b", "thc_interface_alias_b")]
  copyFile (root </> "compiler/test-fixtures/InterfaceForeignAlias.hs") (root </> source)
  commands <- forM variants $ \(variant, symbol) -> do
    let output = directory </> "foreign-alias" </> variant
    createDirectoryIfMissing True (root </> output)
    runLogged 180 root (directory </> "logs") ("foreign-alias-" ++ variant) [] ghc
      ["-c", "-O2", "-fforce-recomp", "-dcore-lint", "-this-unit-id", unitName,
       "-fwrite-if-simplified-core", "-DTHC_FOREIGN_C_LABEL=\"" ++ symbol ++ "\"",
       "-odir", output, "-hidir", output, "-stubdir", output, source]
  renameFile (root </> source) (root </> source ++ ".saved")
  results <- forM variants $ \(variant, symbol) -> runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    initialEnv <- getSession
    (flags, leftovers, _) <- parseDynamicFlags (hsc_logger initialEnv) initial
      (map noLoc ["-package-db", root </> directory </> "full/package.conf.d", "-package-id", unitName])
    liftIO $ check (null leftovers) "Unexpected association control flags"
    _ <- setSessionDynFlags flags
    environment <- getSession
    liftIO $ do
      let path = root </> directory </> "foreign-alias" </> variant </> "InterfaceForeignAlias.hi"
          expected = mkModule (stringToUnit unitName) (mkModuleName "InterfaceForeignAlias")
      raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
      loaded <- loadInterfaceCore environment expected path
      core <- maybe (die "Association control lost complete Core") pure loaded
      (header, cSource) <- stubText core
      check (symbol `isInfixOf` header && symbol `isInfixOf` cSource)
        "Requested C export name missing from real stub"
      let binders = [v | (v, _) <- flattenBinds (interfaceBindings core), isExternalName (varName v)]
          -- Test observation only: GHC's own closure-label rendering appears
          -- in its emitted C. This is NOT an accepted executable association.
          referenced = filter (declaredIn flags cSource) binders
          facts = bindingFacts flags (interfaceBindings core)
          render = showSDoc flags
          declarations = map (render . ppr . snd) (mi_decls raw)
          exports = render (ppr (mi_exports raw))
      check (length referenced == 1) "Expected exactly one observed C closure reference"
      check (all (not . isFCallId) referenced) "Export binder unexpectedly has foreign-call IdDetails"
      check (Map.null (getExtensibleFields (mi_ext_fields raw))) "Unexpected typed interface extension"
      rendered <- interfaceCoreJSON ["unit-qualified"] core
      original <- either die pure (eitherDecodeStrict' (BSC.pack rendered))
      check (case original of Object fields -> not (KeyMap.member "staticForeignExports" fields); _ -> False)
        "Unannotated interface acquired a fabricated static-export inventory"
      writeFile (root </> directory </> "foreign-alias" </> variant ++ ".json") rendered
      pure (facts, declarations, exports, header, cSource, object
        ["variant" .= variant, "symbol" .= symbol, "facts" .= facts,
         "observedCClosure" .= bindingFacts flags (map (\v -> NonRec v (Var v)) referenced),
         "interfaceExtensionFields" .= ([] :: [String]), "executableAssociation" .= False])
  case results of
    [(factsA, declsA, exportsA, headerA, cA, a), (factsB, declsB, exportsB, headerB, cB, b)] -> do
      check (factsA == factsB && declsA == declsB && exportsA == exportsB)
        "C-name-only change unexpectedly changed typed interface facts"
      check (headerA /= headerB && cA /= cB) "C-name control did not distinguish foreign exports"
      let rename = Text.replace "thc_interface_alias_a" "thc_interface_alias_b" . Text.pack
      check (rename headerA == Text.pack headerB && rename cA == Text.pack cB)
        "Foreign artifacts changed by more than the external C symbol"
      writeJson (root </> directory </> "foreign-association.json") $ object
        ["schema" .= (1 :: Int), "typedFactsEqual" .= True, "typedDeclarationsEqual" .= True,
         "haskellExportsEqual" .= True, "foreignArtifactsDiffer" .= True,
         "sourceDeleted" .= True, "variants" .= [a, b]]
    _ -> die "Expected exactly two foreign association controls"
  pure commands

-- The same original aliases with the opt-in producer annotation. Compile in
-- separate processes, remove source targets, then recover through fresh GHC
-- interface sessions: no plugin-side registry can supply these associations.
prepareTypedForeignAssociation :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> String -> String -> FilePath -> String -> IO [CommandResult]
prepareTypedForeignAssociation root directory ghc ghcPkg libdir unitName baseUnit pluginDb pluginUnit = do
  let variants = [("a", "InterfaceForeignAlias", "thc_interface_alias_a"),
                  ("b", "InterfaceForeignAlias", "thc_interface_alias_b"),
                  ("signatures", "ForeignExportSignatures", "unused"),
                  ("static-signatures", "ForeignExportSignatures", "unused"),
                  ("foreign-file", "InterfaceForeign", "unused"),
                  ("instrumented", "InterfaceForeignAlias", "thc_interface_instrumented"),
                  ("managed", "ForeignExportManaged", "unused")]
      sourceFor name = directory </> "typed-export-source" </> name ++ ".hs"
  createDirectoryIfMissing True (root </> directory </> "typed-export-source")
  mapM_ (\name -> copyFile (root </> "compiler/test-fixtures" </> name ++ ".hs") (root </> sourceFor name))
    ["InterfaceForeignAlias", "ForeignExportSignatures", "InterfaceForeign", "ForeignExportManaged"]
  commands <- forM variants $ \(variant, name, symbol) -> do
    let output = directory </> "typed-foreign-exports" </> variant
    createDirectoryIfMissing True (root </> output)
    compiled <- runLogged 180 root (directory </> "logs") ("typed-foreign-export-" ++ variant) [] ghc $
      ["-c", "-O2", "-fforce-recomp", "-dcore-lint", "-this-unit-id", unitName,
       "-fwrite-if-simplified-core", "-DTHC_FOREIGN_C_LABEL=\"" ++ symbol ++ "\"",
       "-package-db", pluginDb, "-plugin-package-id", pluginUnit, "-fplugin=THC.Plugin",
       "-fplugin-opt=THC.Plugin:" ++ output </> "direct", "-fplugin-opt=THC.Plugin:post-tidy",
       "-fplugin-opt=THC.Plugin:foreign-export-associations",
       "-fplugin-opt=THC.Plugin:foreign-export-registration",
       "-odir", output, "-hidir", output, "-stubdir", output, sourceFor name] ++
      ["-DTHC_STATIC_EXPORTS_ONLY" | variant == "static-signatures"] ++
      ["-finfo-table-map" | variant == "instrumented"]
    let database = root </> output </> "package.conf.d"
        conf = root </> output </> "package.conf"
        run label = runLogged 180 root (directory </> "logs") ("typed-export-" ++ variant ++ "-" ++ label) [] ghcPkg
    exists <- doesDirectoryExist database
    initialized <- if exists then pure [] else (:[]) <$> run "init" ["init", database]
    writeFile conf $ unlines
      ["name: thc-interface-fixture", "version: 0.1", "id: " ++ unitName,
       "key: " ++ unitName, "exposed: True", "exposed-modules: " ++ name,
       "import-dirs: " ++ show (root </> output), "depends: " ++ baseUnit]
    registered <- run "register" ["--package-db", database, "update", conf]
    pure ([compiled] ++ initialized ++ [registered])
  let managed = directory </> "typed-foreign-exports/managed"
      nativeRun label = runLogged 180 root (directory </> "logs") ("managed-export-" ++ label) []
  nativeBuild <- nativeRun "native-build" ghc
    ["--make", "-O2", "-fforce-recomp", "-i", "-package-db", managed </> "package.conf.d", "-package-id", unitName,
     "-odir", managed, "-hidir", managed, "compiler/test-fixtures/ManagedExportNative.hs",
     managed </> "ForeignExportManaged.o", "-o", managed </> "oracle"]
  nativeResult <- nativeRun "native-oracle" (root </> managed </> "oracle") []
  mapM_ (\name -> renameFile (root </> sourceFor name) (root </> sourceFor name ++ ".saved"))
    ["InterfaceForeignAlias", "ForeignExportSignatures", "InterfaceForeign", "ForeignExportManaged"]
  records <- forM variants $ \(variant, name, _) -> runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    initialEnv <- getSession
    (flags, leftovers, _) <- parseDynamicFlags (hsc_logger initialEnv) initial
      (map noLoc ["-package-db", root </> directory </> "typed-foreign-exports" </> variant </> "package.conf.d",
                  "-package-id", unitName])
    liftIO $ check (null leftovers) "Unexpected typed association control flags"
    _ <- setSessionDynFlags flags
    environment <- getSession
    liftIO $ do
      let path = root </> directory </> "typed-foreign-exports" </> variant </> name ++ ".hi"
          expected = mkModule (stringToUnit unitName) (mkModuleName name)
      loaded <- loadInterfaceCore environment expected path
      core <- maybe (die "Typed association control lost complete Core") pure loaded
      rendered <- interfaceCoreJSON ["unit-qualified"] core
      value <- either die pure (eitherDecodeStrict' (BSC.pack rendered))
      let metadata = field "staticForeignExports" value
      check (field "schema" metadata == Number 1 && field "execution" metadata == String "not-linked" &&
        field "scope" metadata == String "static-export-associations") "Missing archival typed export schema"
      check (field "schema" value == Number 2 && field "execution" (field "foreign" value) == String "not-linked")
        "Typed association changed original foreign archive admission"
      let provenance = field "staticForeignExportRegistration" value
          (status, reason) = case variant of
            "signatures" -> ("unclassified", "foreign-import-wrapper-or-non-ccall-declaration")
            "foreign-file" -> ("rejected", "additional-foreign-files")
            "instrumented" -> ("unclassified", "unclassified-target-or-instrumentation")
            _ -> ("verified", "")
      check (field "schema" provenance == Number 2 && field "execution" provenance == String "not-linked" &&
        field "scope" provenance == String "retained-foreign-products" &&
        field "status" provenance == String status &&
        (Text.null reason || field "reason" provenance == String reason))
        ("Unexpected retained registration provenance for " ++ variant ++ ": " ++ show provenance)
      if status /= "verified" then pure () else check
        (field "expectedForeign" provenance == field "foreign" value && field "expectedExports" provenance == metadata &&
         field "wordBits" provenance == Number 64)
        "Verified registration lost its exact retained product or native word width"
      if variant /= "static-signatures" then pure () else do
        let constructorIds = case field "constructors" value of
              Array values -> map (field "id") (toList values)
              _ -> []
        check (String "ghc-internal:GHC.Internal.Int.I32#" `elem` constructorIds)
          "Erased newtype/identity export lost its original boxed scalar constructor"
      if variant /= "a" then pure () else case interfaceForeign core of
        ForeignCore.IfaceForeign (Just (ForeignCore.IfaceCStubs header cSource initializers finalizers)) [] -> do
          -- Mutate the archived product at the public serializer boundary;
          -- the preserved annotation must never certify a prefix or subset.
          let altered = [ForeignCore.IfaceCStubs (header ++ "\n/* extra */") cSource initializers finalizers,
                         ForeignCore.IfaceCStubs header (cSource ++ "\n/* extra */") initializers finalizers,
                         ForeignCore.IfaceCStubs header cSource [] finalizers,
                         ForeignCore.IfaceCStubs header cSource initializers initializers]
              details = interfaceDetails core
          mapM_ (\stubs -> do
            -- setSessionDynFlags initializes target platform constants in the
            -- session; the earlier parseDynamicFlags result does not have them.
            changedText <- serializePostTidyCoreWithAnnotations (hsc_dflags environment) ["unit-qualified"] expected
              (typeEnvTyCons (md_types details)) (interfaceBindings core)
              (ForeignCore.IfaceForeign (Just stubs) []) (md_anns details)
            changed <- either die pure (eitherDecodeStrict' (BSC.pack changedText))
            let proof = field "staticForeignExportRegistration" changed
            check (field "status" proof == String "rejected" &&
              field "reason" proof == String "retained-foreign-product-differs")
              "Changed retained stub product inherited registration-only provenance") altered
        _ -> die "Alias registration control lost its actual native products"
      writeJson (root </> directory </> "typed-foreign-exports" </> variant ++ ".json") value
      pure (metadata, provenance)
  case records of
    [(first, _), (second, _), (signatures, _), (staticSignatures, _), _, _, (managedSignatures, _)] -> do
      let a = singleExport first
          b = singleExport second
          allSignatures = entries signatures
          named symbol = case filter ((== String symbol) . field "symbol") allSignatures of
            [exportRecord] -> exportRecord
            _ -> Null
          countExport = named "thc_export_count"
      check (field "symbol" a == String "thc_interface_alias_a" && field "symbol" b == String "thc_interface_alias_b")
        "Typed association lost the distinct external C names"
      check (field "binder" a /= Null && field "binder" a == field "binder" b &&
        field "declaredType" a == field "declaredType" b && field "normalizedType" a == field "normalizedType" b)
        "C-name control unexpectedly changed the exact exported binder/signature"
      check (field "effect" a == String "io" && field "convention" a == String "ccall")
        "StablePtr export lost its IO calling convention"
      check (length allSignatures == 4 && field "effect" (named "thc_export_pure") == String "pure" &&
        field "effect" countExport == String "io" && field "declaredType" countExport /= field "normalizedType" countExport &&
        field "binder" (named "thc_export_alias_one") /= Null &&
        field "binder" (named "thc_export_alias_two") /= Null)
        "Typed export signatures lost normalization, aliases, or static/dynamic distinction"
      let pureArguments = case field "arguments" (named "thc_export_pure") of
            Array values -> map (field "occurrence" . field "name") (toList values)
            _ -> []
      check (pureArguments == map String ["Int8", "Word16", "Float", "Double"] &&
        field "occurrence" (field "name" (field "result" countExport)) == String "Int32")
        "Typed export metadata conflated primitive widths/floats or lost newtype normalization"
      check (entries staticSignatures == allSignatures)
        "Removing only the wrapper changed the exact static export associations"
      let unitExport = filter ((== String "thc_unit") . field "symbol") (entries managedSignatures)
      check (case unitExport of
        [unitRecord] -> field "occurrence" (field "name" (field "result" unitRecord)) == String "Unit" &&
          field "effect" unitRecord == String "io"
        _ -> False) "Managed IO unit export lost its exact normalized type"
      writeJson (root </> directory </> "typed-foreign-exports.json") (object
        ["schema" .= (1 :: Int), "execution" .= ("not-linked" :: String),
         "sourceDeleted" .= True, "aliasControls" .= [first, second], "signatureControls" .= signatures,
         "retainedRegistrationControls" .= map snd records,
         "changedProductControls" .= (["header", "body", "initializer", "finalizer"] :: [String])])
    _ -> die "Expected typed alias and signature controls"
  pure (concat commands ++ [nativeBuild, nativeResult])
  where
    field key (Object fields) = maybe Null id (KeyMap.lookup key fields)
    field _ _ = Null
    entries value = case field "exports" value of Array values -> toList values; _ -> []
    singleExport value = case entries value of [exportRecord] -> exportRecord; _ -> Null

-- The selected installed compiler may legitimately have thin boot interfaces.
-- Inspect Bound only when its real complete Core exists; never copy/hash it.
inspectInstalledBound :: HscEnv -> FilePath -> FilePath -> IO ()
inspectInstalledBound environment charPath output = do
  let flags = hsc_dflags environment
      path = takeDirectory charPath </> "Conc/Bound.hi"
  raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
  loaded <- loadInterfaceCore environment (mi_module raw) path
  value <- case loaded of
    Nothing -> pure (object ["completeCore" .= False])
    Just core -> do
      (_, cSource) <- stubText core
      let binders = [v | (v, _) <- flattenBinds (interfaceBindings core), isExternalName (varName v)]
          referenced = filter (declaredIn flags cSource) binders
      check (length referenced == 1 && "forkOS_entry" `isInfixOf` cSource &&
             "ghc_hs_iface->runIO_closure" `isInfixOf` cSource)
        ("Installed Bound foreign stub no longer matches the inspected control: " ++ show (map getOccString referenced))
      check (all (not . isFCallId) referenced) "Installed export acquired an FCallId"
      pure (object ["completeCore" .= True, "module" .= moduleNameString (moduleName (mi_module raw)),
        "unit" .= unitString (moduleUnit (mi_module raw)),
        "observedCClosure" .= bindingFacts flags (map (\v -> NonRec v (Var v)) referenced),
        "interfaceExtensionFields" .= Map.keys (getExtensibleFields (mi_ext_fields raw)),
        "executableAssociation" .= False])
  writeJson output value
