-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : OriginalDirectoryStreamsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for original directory streams.
module OriginalDirectoryStreamsFixtures (prepareOriginalDirectoryStreams) where
#if defined(mingw32_HOST_OS)
import System.Exit (die)
prepareOriginalDirectoryStreams :: FilePath -> IO ()
prepareOriginalDirectoryStreams _ = die "original-directory-streams requires Unix"
#else
import Control.Monad (forM, forM_, unless, void, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, withObject, (.:), (.=))
import Data.Aeson.Types (parseEither)
import qualified Data.Map.Strict as Map
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (nubBy)
import Data.Word (Word8)
import Foreign.C.Error (Errno(..), getErrno, resetErrno)
import Foreign.C.String (CString)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, nullPtr, plusPtr, castPtr)
import Foreign.Storable (peek, poke, peekByteOff, sizeOf)
import qualified System.Posix.Internals as P
import qualified System.Posix.Files.ByteString as PosixBytes
import qualified System.Posix.Directory.ByteString as PosixDirectoryBytes
import qualified System.Posix.IO as PosixIO
import System.Posix.Types (Fd(..))
import System.Directory (createDirectoryIfMissing, doesFileExist, doesDirectoryExist, removeFile, removePathForcibly, getCurrentDirectory, makeAbsolute)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative)
import FixtureSupport
import OriginalCurrentDirectoryFixtures (prepareOriginalCurrentDirectory)
import GHC hiding (entry, exprType)
import GHC.Plugins hiding ((<>))
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.Runtime.Interpreter (wormhole)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import qualified THC.Interface as Interface
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("directoryOpen", "opendir"), ("directoryFdOpen", "fdopendir"),
  ("directoryClose", "closedir"), ("directoryRead", "__hscore_readdir"),
  ("directoryName", "__hscore_d_name"), ("directoryFree", "__hscore_free_dirent")]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ symbolName (Just selected) True) convention F.PlayRisky))
    | isOriginalUnixUnit owner, unitString selected == owner ->
      let actual = unpackFS symbolName
          encoded = concatMap (\c -> case c of '-' -> "zm"; '.' -> "zi"; _ -> [c]) owner
          expected name = "ghczuwrapperZC0ZC" ++ encoded ++ "ZCSystemziPosixziDirectoryzi" ++
            (if name == "opendir" then "PosixPath" else "Common") ++ "ZC" ++ name
      in case [name | (_, name) <- operations,
          (convention == F.CApiConv && elem name ["opendir", "fdopendir"] && actual == expected name) ||
          (convention == F.CCallConv && notElem name ["opendir", "fdopendir"] && actual == name)] of
        [name] -> Just name
        _ -> Nothing
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

-- Reuse the pinned complete-Core Unix acquisition already required by CWD.
-- Cached exports do not pretend to restore its private source/database: a
-- standalone stream preparation regenerates that prerequisite when absent.
prepareOriginalDirectoryStreams :: FilePath -> IO ()
prepareOriginalDirectoryStreams root = do
  let directory = "build/original-directory-streams"
      source = "t/fixtures/compiler/OriginalDirectoryStreamsAudit.hs"
      sourceReceipt = "build/original-current-directory/unix-source.json"
      execute = runLogged 180 root (directory </> "logs")
      manifest = root </> directory </> "manifest.json"
      oneLine result = case BS.lines (commandStdout result) of
        [value] -> BS.unpack value
        _ -> error "Expected one selected compiler line"
      decodeReceipt = BS.readFile (root </> sourceReceipt) >>= either die pure . eitherDecodeStrict'
  ready <- doesFileExist (root </> sourceReceipt)
  unless ready (prepareOriginalCurrentDirectory root)
  prior <- decodeReceipt
  priorDb <- either die pure (parseEither (withObject "Unix source receipt" (.: "packageDatabase")) prior)
  databaseAvailable <- doesDirectoryExist priorDb
  unless databaseAvailable (prepareOriginalCurrentDirectory root)
  receipt <- decodeReceipt
  packageDb <- either die pure (parseEither (withObject "Unix source receipt" (.: "packageDatabase")) receipt)
  unit <- either die pure (parseEither (withObject "Unix source receipt" (.: "unixUnit")) receipt)
  archiveHash <- either die pure (parseEither (withObject "Unix source receipt" (.: "archiveSha256")) receipt)
  unless (isOriginalUnixUnit unit && (archiveHash :: String) == "a128dea3bfeb731a562f22d376fa606e902154d95321363f7ec1ea6b787a5a3e")
    (die "Directory-stream fixture requires the pinned original Unix source")
  sourceHashes <- either die pure (parseEither (withObject "Unix source receipt" (.: "sourceHashes")) receipt)
  actualSourceHashes <- hashes root (Map.keys sourceHashes)
  unless (actualSourceHashes == sourceHashes) (die "Original Unix sources changed after their recorded build")
  createDirectoryIfMissing True (root </> directory </> "ghc")
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  BS.readFile (root </> sourceReceipt) >>= BS.writeFile (root </> directory </> "unix-source.json")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Directory-stream fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  let packageArgs = ["--package-db", packageDb, "--no-user-package-db", "--ipid"]
  imports <- execute "imports" [] pkg (packageArgs ++ ["field", unit, "import-dirs", "--simple-output"])
  owner <- execute "unit" [] pkg (packageArgs ++ ["field", unit, "id", "--simple-output"])
  unless (oneLine owner == unit) (die "Directory-stream package owner differs from source receipt")
  ghcImports <- execute "ghc-imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = [oneLine imports </> "System/Posix/Directory" </> name ++ ".hi" | name <- ["PosixPath", "Common"]]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    let symbol = originalSymbol unit
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package-db", packageDb, "-package-id", unit, "-package", "ghc-internal", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == unit)
        (die "Wrong installed directory-stream interface owner")
      complete <- Interface.loadInterfaceCore env (mi_module raw) path >>= maybe
        (die "Rebuilt Unix interface lacks complete Core") pure
      pure [value | (_, body) <- flattenBinds (Interface.interfaceBindings complete),
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine directory-stream FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected directory-stream fixture module graph")
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
          _ -> error ("Missing directory-stream test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original directory-stream FCallId differs from typed consumer " ++ name)
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
            [] -> error "Empty directory-stream consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [openCall, fdCall, closeCall, readCall, nameCall, freeCall] -> observeDirectoryStreams
        (root </> directory </> "native-paths")
        (unsafeCoerce openCall :: CString -> IO (Ptr ()))
        (unsafeCoerce fdCall :: Int -> IO (Ptr ()))
        (unsafeCoerce closeCall :: Ptr () -> IO Int)
        (unsafeCoerce readCall :: Ptr () -> Ptr (Ptr ()) -> IO Int)
        (unsafeCoerce nameCall :: Ptr () -> IO CString)
        (unsafeCoerce freeCall :: Ptr () -> IO ())
      _ -> die "Expected six original directory-stream native functions"
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", "main:OriginalDirectoryStreamsAudit." ++ name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".cbd"]
  interfaceHashes <- hashes root interfaces
  registrationHash <- hashFile (packageDb </> unit ++ ".conf")
  let commands = [version, library, imports, owner, ghcImports] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/OriginalDirectoryStreamsFixtures.hs", "t/haskell-fixtures/OriginalCurrentDirectoryFixtures.hs",
    sourceReceipt, "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.cbd", "post.cbd", "oracle.json", "unix-source.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "unixUnit" .= unit,
     "consumerKind" .= ("typed consumers specialized with genuine source-built Unix directory FCallIds" :: String),
     "interfaces" .= interfaces, "interfaceHashes" .= interfaceHashes, "registrationSha256" .= registrationHash,
     "installedArtifactsHashed" .= False, "privateRebuiltUnix" .= True,
     "unixSourceReceipt" .= (directory </> "unix-source.json"), "unixArchiveSha256" .= archiveHash,
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-directory-streams: six genuine FCallIds and twelve strict audits"

pathCases :: [(String, BS.ByteString, Bool)]
pathCases = [("base", ".", False), ("sub", "sub", False), ("dot-parent", "sub/..", False),
  ("symlink", "link", False), ("absolute", "sub", True), ("raw", BS.pack [toEnum 255,'n'], False),
  ("missing", "missing", False), ("regular", "file", False), ("not-directory", "file/child", False),
  ("empty", "", False), ("dangling", "dangling", False), ("permission", "denied", False)]

observeDirectoryStreams :: FilePath -> (CString -> IO (Ptr ())) -> (Int -> IO (Ptr ())) ->
  (Ptr () -> IO Int) -> (Ptr () -> Ptr (Ptr ()) -> IO Int) -> (Ptr () -> IO CString) ->
  (Ptr () -> IO ()) -> IO Value
observeDirectoryStreams directory openCall fdCall closeCall readCall nameCall freeCall = do
  exists <- doesDirectoryExist directory
  when exists (removePathForcibly directory)
  createDirectoryIfMissing True directory
  originalCwd <- getCurrentDirectory
  absoluteDirectory <- makeAbsolute directory
  let asBytes value = map fromEnum (BS.unpack value)
      errnoValue = do Errno value <- getErrno; pure (fromIntegral value :: Int)
      seed value = if value == (0 :: Int) then resetErrno else void (P.c_write (-1) nullPtr 0)
      setup name = do
        let base = absoluteDirectory </> name
            raw = BS.pack base
        createDirectoryIfMissing True base
        PosixBytes.setFileMode raw 0o700
        BS.writeFile (base </> "file") "unchanged"
        forM_ ["sub", "denied", BS.pack [toEnum 255,'n']] $ \child ->
          PosixDirectoryBytes.createDirectory (raw <> "/" <> child) 0o700
        PosixBytes.createSymbolicLink "sub" (raw <> "/link")
        PosixBytes.createSymbolicLink "missing" (raw <> "/dangling")
        PosixBytes.setFileMode (raw <> "/denied") 0
        pure base
      drain pointer errorSeed = allocaBytes (8 + sizeOf (nullPtr :: Ptr ()) + 8) $ \image -> do
        let width = sizeOf (nullPtr :: Ptr ())
            output = castPtr (image `plusPtr` 8)
            loop remaining = do
              when (remaining == (0 :: Int)) (die "Directory oracle failed to reach EOF within its fixed fixture bound")
              fillBytes image 165 (8 + width + 8)
              poke output nullPtr
              seed errorSeed
              status <- readCall pointer output
              errorCode <- errnoValue
              entryPointer <- peek output
              guardBytes <- mapM (peekByteOff image) ([0..7] ++ [8+width..15+width]) :: IO [Word8]
              (nameBytes, freePreserved) <- if entryPointer == nullPtr then pure (Nothing, True) else do
                namePointer <- nameCall entryPointer
                before <- BS.packCString namePointer
                freeCall entryPointer
                after <- BS.packCString namePointer
                pure (Just (asBytes before), before == after)
              let row = object ["status" .= status, "errno" .= errorCode,
                    "null" .= (entryPointer == nullPtr), "name" .= nameBytes,
                    "guardsIntact" .= all (==165) guardBytes, "freePreserved" .= freePreserved]
              if entryPointer == nullPtr then pure [row] else (row :) <$> loop (remaining - 1)
        loop 64
      openedObservation pointer errorCode errorSeed = do
        if pointer == nullPtr then pure (object ["null" .= True, "errno" .= errorCode]) else do
          rows <- drain pointer errorSeed
          seed 9
          closed <- closeCall pointer
          closeError <- errnoValue
          pure $ object ["null" .= False, "errno" .= errorCode, "reads" .= rows,
            "closeStatus" .= closed, "closeErrno" .= closeError]
  pathRows <- forM pathCases $ \(name, suffix, absolute) -> do
    base <- setup name
    let prefix = if absolute then base else makeRelative originalCwd base
        nativePath = if BS.null suffix then BS.empty else BS.pack prefix <> "/" <> suffix
    seed 9
    pointer <- BS.useAsCString nativePath openCall
    errorCode <- errnoValue
    result <- openedObservation pointer errorCode 0
    PosixBytes.setFileMode (BS.pack base <> "/denied") 0o700
    pure $ object ["name" .= name, "path" .= asBytes suffix, "absolute" .= absolute, "result" .= result]
  fdRows <- forM ["directory", "alias", "regular", "closed", "invalid"] $ \name -> do
    base <- setup ("fd-" ++ name)
    fd <- if name == "invalid" then pure (Fd (-1)) else
      PosixIO.openFd (if name == "regular" then base </> "file" else base) PosixIO.ReadOnly PosixIO.defaultFileFlags
    alias <- if name == "alias" then Just <$> PosixIO.dup fd else pure Nothing
    when (name == "closed") (PosixIO.closeFd fd)
    let Fd descriptor = fd
    seed 9
    pointer <- fdCall (fromIntegral descriptor)
    errorCode <- errnoValue
    -- fdopendir owns its successful input, while each duplicate remains a
    -- separate close obligation sharing only the open file description.
    forM_ alias PosixIO.closeFd
    preserved <- if pointer == nullPtr && name == "regular"
      then (==0) <$> P.c_close descriptor else pure True
    result <- openedObservation pointer errorCode 0
    PosixBytes.setFileMode (BS.pack base <> "/denied") 0o700
    pure $ object ["name" .= name, "result" .= result, "failureFdPreserved" .= preserved]
  specialRows <- forM ["sticky-eof", "renamed", "deleted"] $ \name -> do
    base <- setup name
    let selected = if name == "deleted" then base </> "sub" else base
    pointer <- BS.useAsCString (BS.pack selected) openCall
    when (pointer == nullPtr) (die "Native special directory setup failed")
    when (name == "renamed") (PosixBytes.rename (BS.pack base) (BS.pack (base ++ "-renamed")))
    when (name == "deleted") (PosixDirectoryBytes.removeDirectory (BS.pack selected))
    rows <- drain pointer (if name == "sticky-eof" then 9 else 0)
    status <- closeCall pointer
    when (name == "renamed") (PosixBytes.rename (BS.pack (base ++ "-renamed")) (BS.pack base))
    PosixBytes.setFileMode (BS.pack base <> "/denied") 0o700
    pure $ object ["name" .= name, "reads" .= rows, "closeStatus" .= status]
  finalCwd <- getCurrentDirectory
  unless (originalCwd == finalCwd) (die "Directory stream oracle changed process CWD")
  pure $ object ["pathRows" .= pathRows, "fdRows" .= fdRows, "specialRows" .= specialRows,
    "pointerBytes" .= sizeOf (nullPtr :: Ptr ()), "processCwdUnchanged" .= True]
#endif
