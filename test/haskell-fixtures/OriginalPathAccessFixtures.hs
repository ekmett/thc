-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}
module OriginalPathAccessFixtures (prepareOriginalPathAccess) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalPathAccess :: FilePath -> IO ()
prepareOriginalPathAccess _ = die "original-path-access requires Unix"
#else

import Control.Monad (forM, unless, void)
import Data.Aeson (Value, object, (.=))
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Control.Exception (finally)
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified System.Posix.User as PosixUser
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (nubBy)
import Data.IORef (newIORef, writeIORef)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Runtime.Interpreter (wormhole)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing, doesFileExist, doesDirectoryExist, removeFile, removePathForcibly, withCurrentDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("pathAccess","access")]

symbol :: Id -> Maybe String
symbol value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlayRisky))
    | unitString unit == "ghc-internal", unpackFS name == "access" -> Just "access"
  _ -> Nothing

variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var value | isId value -> [value]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let bindings body -> concatMap (variables . snd) (flattenBinds [bindings]) ++ variables body
  Case value _ _ alternatives -> variables value ++ concat [variables body | Alt _ _ body <- alternatives]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

prepareOriginalPathAccess :: FilePath -> IO ()
prepareOriginalPathAccess root = do
  let directory = "build/original-path-access"
      source = "compiler/test-fixtures/OriginalPathAccessAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      manifest = root </> directory </> "manifest.json"
      oneLine result = case BS.lines (commandStdout result) of
        [value] -> BS.unpack value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  stale <- doesFileExist manifest
  if stale then removeFile manifest else pure ()
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Original access fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "unix", "import-dirs", "--simple-output"]
  owner <- execute "unit" [] pkg ["field", "unix", "id", "--simple-output"]
  unless (isOriginalUnixUnit (oneLine owner)) (die "Path-access proof requires the pinned installed unix owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = [oneLine ghcImports </> "GHC/Internal/System/Posix/Internals.hi"]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "unix", "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == "ghc-internal")
        (die "Wrong installed pathname access interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original pathname access fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine pathname access FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected pathname access fixture module graph")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var value | Just body <- lookup value bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        select name = case [(value, body) | (value, body) <- bindings,
                            getOccString value == name, isExternalName (varName value)] of
          [pair] -> pair
          _ -> error ("Missing pathname access test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original pathname access FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    natives <- forM operations $ \(name, targetName) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty pathname access consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ observeAccess (root </> directory </> "native-paths")
      [(name, unsafeCoerce value :: CString -> Int -> IO Int) | ((name,_),value) <- zip operations natives]
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["scripts/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalPathAccessFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs", "scripts/core_original_foreign.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumer specialized with the genuine installed GHC access FCallId" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-path-access: 1 original FCallId, 68 native pathname access observations and 2 strict audits"

-- Record libc's real-ID decision. Root and ordinary users intentionally differ.
observeAccess :: FilePath -> [(String, CString -> Int -> IO Int)] -> IO Value
observeAccess directory operationsNative = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True (directory </> "sub")
  createDirectoryIfMissing True (directory </> "locked")
  native <- case lookup "pathAccess" operationsNative of
    Just call -> pure call
    Nothing -> die "Missing original access"
  realUid <- PosixUser.getRealUserID
  effectiveUid <- PosixUser.getEffectiveUserID
  realGid <- PosixUser.getRealGroupID
  effectiveGid <- PosixUser.getEffectiveGroupID
  withCurrentDirectory directory $ do
    let rawName = BS.pack [toEnum 255,'n']
        chmod path mode = BS.useAsCString path $ \name -> do
          status <- P.c_chmod name mode
          unless (status == 0) (die "Native access setup chmod failed")
        restore = chmod "locked" 0o700
        paths = [("file","target"),("executable","executable"),("zero","zero"),
          ("directory","sub"),("locked-child","locked/target"),("link","link"),
          ("dangling","dangling"),("missing","absent"),("empty",""),
          ("not-directory","target/child"),("raw",rawName),("relative","sub/../target")]
        cases = [(label,path,mode) | (label,path) <- paths, mode <- [0,1,2,4,7]] ++
          [("file","target",mode) | mode <- [3,5,6,8,-1,-2147483648,2147483647,4294967296]]
    void $ forM ["target","executable","zero","locked/target","raw-file"] $ \path ->
      BS.writeFile path "unchanged"
    PosixBytes.rename "raw-file" rawName
    chmod "target" 0o640
    chmod "locked/target" 0o640
    chmod "executable" 0o751
    chmod "zero" 0
    chmod rawName 0o640
    chmod "sub" 0o750
    PosixBytes.createSymbolicLink "target" "link"
    PosixBytes.createSymbolicLink "absent-target" "dangling"
    chmod "locked" 0
    rows <- (forM cases $ \(label,path,mode) -> do
      void (P.c_close (-1))
      status <- BS.useAsCString path (\name -> native name mode)
      Errno errno <- getErrno
      pure (object ["name" .= (label :: String), "path" .= map fromEnum (BS.unpack path),
        "mode" .= toInteger mode, "status" .= status, "errno" .= toInteger errno]))
      `finally` restore
    contents <- BS.readFile "target"
    unless (contents == "unchanged") (die "Native access changed file contents")
    pure (object ["rows" .= rows, "identity" .= object
      ["realUid" .= toInteger realUid, "effectiveUid" .= toInteger effectiveUid,
       "realGid" .= toInteger realGid, "effectiveGid" .= toInteger effectiveGid]])

#endif
