-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (075 original-posix-stat)
-- Purpose: Check filesystem calls transport ABI data, paths, errors and resource
--   ownership.
-- Produces/consumed result: Per-operation CBDs/oracle.json for stat, mode, links, access,
--   directory and relative-path cases.
-- Cost and overlap: Ten native producer flows are disproportionate to package linkage.
--   Consolidate package-level filesystem behavior; retain only distinct THC
--   ABI/authority/lifetime cases, not libc conformance.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 075.
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalPathStatFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original path stat.
module OriginalPathStatFixtures (prepareOriginalPathStat) where

#if defined(mingw32_HOST_OS)
import System.Exit (die)

prepareOriginalPathStat :: FilePath -> IO ()
prepareOriginalPathStat _ = die "original-path-stat requires Unix"
#else

import Control.Monad (forM, unless, void)
import Data.Aeson (Value, object, (.=))
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CString)
import Foreign.Ptr (Ptr, castPtr, plusPtr)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Storable (peekByteOff)
import Data.Word (Word8)
import Data.Bits ((.&.))
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
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
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("pathStat","__hscore_stat"),("pathLstat","__hscore_lstat"),("unixPathLstat",unixLstat)]

unixLstat :: String
unixLstat = "ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat"

-- Only the validated unit suffix varies; wrapper index/module/function remain
-- exact. The returned operation key never replaces the original FCallId.
unixLstatFor :: String -> String
unixLstatFor owner = "ghczuwrapperZC2ZCunixzm2zi8zi8zi0zm" ++ drop (length ("unix-2.8.8.0-" :: String)) owner ++
  "ZCSystemziPosixziFilesziPosixStringZClstat"

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) convention F.PlayRisky))
    | unitString unit == "ghc-internal", convention == F.CCallConv,
      elem (unpackFS name) ["__hscore_stat","__hscore_lstat"] -> Just (unpackFS name)
    | unitString unit == owner, isOriginalUnixUnit owner, convention == F.CApiConv,
      unpackFS name == unixLstatFor (unitString unit) -> Just unixLstat
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

prepareOriginalPathStat :: FilePath -> IO ()
prepareOriginalPathStat root = do
  let directory = "build/original-path-stat"
      source = "t/fixtures/compiler/OriginalPathStatAudit.hs"
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
  unless (oneLine version == "9.14.1") (die "Unix libc FFI fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "unix", "import-dirs", "--simple-output"]
  owner <- execute "unit" [] pkg ["field", "unix", "id", "--simple-output"]
  unless (isOriginalUnixUnit (oneLine owner)) (die "Path-stat CAPI proof requires the pinned installed unix owner")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = [oneLine ghcImports </> "GHC/Internal/System/Posix/Internals.hi",
                    oneLine imports </> "System/Posix/Files/PosixString.hi"]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    let symbol = originalSymbol (oneLine owner)
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
      unless (elem (unitString (moduleUnit (mi_module raw))) ["ghc-internal", oneLine owner])
        (die "Wrong installed path stat interface owner")
      -- Ordinary installed interfaces retain these real foreign declarations in
      -- unfoldings; complete interface Core is not required by this fixture.
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original path stat fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure [value | declaration <- typeEnvIds (md_types details),
                    nameModule_maybe (varName declaration) == Just moduleOwner,
                    Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine path stat FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected path stat fixture module graph")
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
          _ -> error ("Missing path stat test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original path stat FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCoreCBD flags ["unit-qualified"] adapted >>= BS.writeFile (root </> directory </> "pre.cbd")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCoreCBD flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> directory </> "post.cbd")
    natives <- forM operations $ \(name, targetName) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty path stat consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ observePaths (root </> directory </> "native-paths")
      [(name, unsafeCoerce value :: CString -> Ptr () -> IO Int) | ((name,_),value) <- zip operations natives]
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", "main:OriginalPathStatAudit." ++ name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".cbd"]
  let commands = [version, library, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/OriginalPathStatFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.cbd", "post.cbd", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed GHC and Unix path-stat FCallIds" :: String),
     "interfaces" .= interfaces, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-path-stat: 3 original FCallIds, native path observations and 6 strict audits"

-- Each operation invokes its original natively compiled FCallId on the same
-- private paths. Normalize only inode identity and directory size across hosts.
observePaths :: FilePath -> [(String, CString -> Ptr () -> IO Int)] -> IO Value
observePaths directory operationsNative = do
  exists <- doesDirectoryExist directory
  if exists then removePathForcibly directory else pure ()
  createDirectoryIfMissing True (directory </> "sub")
  withCurrentDirectory directory $ do
    BS.writeFile "target" (BS.pack [toEnum n | n <- [0..31]])
    BS.useAsCString "target" $ \name -> void (P.c_chmod name 0o640)
    BS.useAsCString "sub" $ \name -> void (P.c_chmod name 0o750)
    PosixBytes.createSymbolicLink "target" "link"
    PosixBytes.createSymbolicLink "absent-target" "dangling"
    let rawName = BS.pack [toEnum 255,'n']
    PosixBytes.createSymbolicLink "target" rawName
    allocaBytes P.sizeof_stat $ \baseline -> do
      BS.useAsCString "target" $ \name -> do
        status <- P.c_stat name baseline
        unless (status == 0) (die "Native path-stat baseline failed")
      device <- P.st_dev baseline
      inode <- P.st_ino baseline
      let cases = [("file","target"),("directory","sub"),("link","link"),("dangling","dangling"),
                   ("missing","absent"),("empty",""),("not-directory","target/child"),
                   ("relative","sub/../target"),("raw-link",rawName)]
      rows <- fmap concat $ forM operationsNative $ \(entryName,call) -> forM cases $ \(label,path) ->
        BS.useAsCString path $ \name -> allocaBytes (P.sizeof_stat + 16) $ \storage -> do
          fillBytes storage 90 (P.sizeof_stat + 16)
          let destination = plusPtr storage 8
          void (P.c_close (-1))
          status <- call name destination
          Errno errno <- getErrno
          bytes <- mapM (peekByteOff storage) [0..P.sizeof_stat+15] :: IO [Word8]
          values <- if status == 0 then do
            let image = castPtr destination
            size <- P.st_size image; mode <- P.st_mode image
            actualDevice <- P.st_dev image; actualInode <- P.st_ino image
            pure [if P.c_s_isdir mode /= 0 then -1 else fromIntegral size,
              fromIntegral mode .&. 0o177777, fromEnum (actualDevice == device), fromEnum (actualInode == inode)]
            else pure []
          let unchanged = if status == 0 then all (== 90) (take 8 bytes ++ drop (P.sizeof_stat + 8) bytes)
                            else all (== 90) bytes
          unless unchanged (die "Native path-stat changed destination guards or failed image")
          pure (object ["entry" .= entryName, "name" .= (label :: String), "path" .= map fromEnum (BS.unpack path),
                        "status" .= status, "errno" .= toInteger errno, "values" .= (values :: [Int]),
                        "guardsIntact" .= unchanged])
      pure (object ["size" .= P.sizeof_stat, "rows" .= rows])

#endif
