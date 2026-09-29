-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : WindowsDirectoryFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for windows directory.
module WindowsDirectoryFixtures (prepareWindowsDirectory) where

#if !defined(mingw32_HOST_OS)
import System.Exit (die)
prepareWindowsDirectory :: FilePath -> IO ()
prepareWindowsDirectory _ = die "windows-directory requires native Windows GHC 9.14.1"
#else
import Control.Exception (finally)
import Control.Monad (forM, unless, void, when)
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString as Bytes
import qualified Data.ByteString.Char8 as BS
import qualified Data.Map.Strict as Map
import Data.Char (toUpper, ord)
import Data.List (nubBy, sort)
import Data.Time.Clock (getCurrentTime)
import Data.Time.Format (defaultTimeLocale, formatTime)
import Data.Word (Word8, Word16)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Array (withArray0)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, intPtrToPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import FixtureSupport
import GHC hiding (exprType)
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
import System.Directory (createDirectoryIfMissing, createDirectory, doesFileExist,
  doesDirectoryExist, listDirectory, pathIsSymbolicLink, removeFile, getCurrentDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory)
import qualified THC.Interface as Interface
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("directoryFirst","FindFirstFileW"), ("directoryNext","FindNextFileW"),
              ("directoryClose","FindClose"), ("directoryError","GetLastError")]

originalSymbol :: String -> Id -> Maybe String
originalSymbol owner value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ symbolName (Just unit) True) F.CCallConv F.PlayRisky))
    | unitString unit == owner, elem (unpackFS symbolName) (map snd operations) -> Just (unpackFS symbolName)
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

sourceFilesUnder :: FilePath -> FilePath -> IO [FilePath]
sourceFilesUnder root relative = do
  names <- sort <$> listDirectory (root </> relative)
  fmap concat $ forM names $ \name -> do
    let path = relative </> name
    symbolic <- pathIsSymbolicLink (root </> path)
    when symbolic (die ("Unexpected symbolic link in pinned Win32 source: " ++ path))
    directory <- doesDirectoryExist (root </> path)
    if directory then sourceFilesUnder root path else pure [path]

prepareWindowsDirectory :: FilePath -> IO ()
prepareWindowsDirectory root = do
  let directory = "build/windows-directory"
      source = "t/fixtures/compiler/WindowsDirectoryAudit.hsc"
      execute = runLogged 600 root (directory </> "logs")
      manifest = root </> directory </> "manifest.json"
      oneLine = BS.unpack . BS.takeWhile (/= '\r') . BS.takeWhile (/= '\n') . commandStdout
      archive = "vendor/archives/Win32-2.14.2.1.tar.gz"
      archiveHash = "69d15a9fb4ef718353aaf8700a64c5885743d4f34a94f7da273fa12584df0315"
      archiveUrl = "https://hackage.haskell.org/package/Win32-2.14.2.1/Win32-2.14.2.1.tar.gz"
      unit = "Win32-2.14.2.1-inplace"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  python <- maybe "python" id <$> lookupEnv "THC_PYTHON"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Windows directory fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  createDirectoryIfMissing True (root </> takeDirectory archive)
  cached <- doesFileExist (root </> archive)
  unless cached $ void $ execute "download" [] "curl.exe" ["--fail","--location",archiveUrl,"-o",archive]
  digest <- hashFile (root </> archive)
  unless (digest == archiveHash) (die "Pinned Win32 archive SHA256 mismatch")
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let sourceRun = directory </> "source-" ++ stamp
      packageSource = sourceRun </> "Win32-2.14.2.1"
      dist = root </> directory </> "original-win32"
      packageDb = dist </> "package.conf.inplace"
      setup = directory </> "Setup.hs"
      setupExe = root </> directory </> "setup.exe"
  createDirectory (root </> sourceRun)
  extracted <- execute "extract" [] "tar" ["-xzf",archive,"-C",sourceRun]
  sourceFiles <- sourceFilesUnder root packageSource
  sourceHashes <- hashes root sourceFiles
  writeFile (root </> setup) "import Distribution.Simple\nmain :: IO ()\nmain = defaultMain\n"
  setupBuilt <- execute "setup-compile" [] ghc ["--make",setup,"-package","Cabal",
    "-outputdir",directory </> "setup-objects","-o",setupExe]
  let setupArgs = ["--working-dir=" ++ root </> packageSource]
      buildArgs = ["--builddir=" ++ dist]
  configured <- execute "configure" [] setupExe (setupArgs ++ ["configure"] ++ buildArgs ++
    ["--flags=os-string","--disable-shared","--enable-library-vanilla","--ipid=" ++ unit,
     "--with-ghc=" ++ ghc, "--with-ghc-pkg=" ++ pkg,
     "--ghc-options=-O2 -fwrite-if-simplified-core -fexpose-all-unfoldings"])
  built <- execute "build" [] setupExe (setupArgs ++ ["build"] ++ buildArgs ++ ["-j4"])
  afterHashes <- hashes root sourceFiles
  unless (sourceHashes == afterHashes) (die "Building Win32 modified original package sources")
  owner <- execute "unit" [] pkg ["--package-db",packageDb,"--no-user-package-db","--ipid","field",unit,"id","--simple-output"]
  unless (oneLine owner == unit) (die "Wrong private Win32 unit")
  generated <- execute "consumer-hsc2hs" [] "hsc2hs" [source,"-o",directory </> "WindowsDirectoryAudit.hs"]
  let interfaces = [dist </> "build/System/Win32" </> name ++ ".hi" | name <- ["File/Internal","Types"]]
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configuredFlags, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2","-package-db",packageDb,"-package-id",unit,"-package","ghc-internal",
       "-fno-external-interpreter","-dcore-lint","-odir",root </> directory </> "ghc",
       "-hidir",root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configuredFlags Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    let symbol = originalSymbol unit
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == unit) (die "Wrong Win32 interface owner")
      complete <- Interface.loadInterfaceCore env (mi_module raw) path >>= maybe
        (die "Original Win32 interface lacks complete Core") pure
      pure [value | (_,body) <- flattenBinds (Interface.interfaceBindings complete),
                    value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == 4) (die "Missing original Win32 directory FCallIds")
    target <- guessTarget (root </> directory </> "WindowsDirectoryAudit.hs") Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected Windows directory consumer module graph")
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
        select name = case [(value,body) | (value,body) <- bindings,
                            getOccString value == name, isExternalName (varName value)] of
          [pair] -> pair
          _ -> error ("Missing Windows directory typed consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value,body),[original]) | Just (_,_,formal,_) <- splitFunTy_maybe (idType value),
                                    eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied),applied)
          _ -> error ("Original Win32 FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name,targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value,body) <- guests],
          mg_exports = filter (\available -> elem (availName available) (map (varName . fst) guests)) (mg_exports optimized) }
        compileNative body = do
          (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
          wormhole (hscInterp current) value
    liftIO $ do
      serializeOptimizedCoreCBD flags ["unit-qualified"] adapted >>= BS.writeFile (root </> directory </> "pre.cbd")
      (tidied,_) <- hscTidy current adapted
      serializePostTidyCoreCBD flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> directory </> "post.cbd")
    natives <- forM operations $ \(name,targetName) -> liftIO $ do
      let nativeName = case name of first:rest -> "native" ++ toUpper first:rest; [] -> error "Empty name"
      compileNative (snd (specialize nativeName targetName))
    layout <- liftIO $ compileNative (snd (select "nativeLayout"))
    liftIO $ case natives of
      [first,next,close,lastError] -> observe (root </> directory </> "native-" ++ stamp)
        (unsafeCoerce layout :: (Int,Int,Int,Int))
        (unsafeCoerce first :: Ptr Word16 -> Ptr () -> IO (Ptr ()))
        (unsafeCoerce next :: Ptr () -> Ptr () -> IO Int)
        (unsafeCoerce close :: Ptr () -> IO Int)
        (unsafeCoerce lastError :: IO Word)
      _ -> die "Expected four original Win32 native functions"
  writeJson (root </> directory </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre","post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] python ["bin/audit-core.py","--entry","main:WindowsDirectoryAudit." ++ name,
      "--output",directory </> stage ++ "-" ++ name ++ ".audit.json",directory </> stage ++ ".cbd"]
  interfaceHashes <- hashes root interfaces
  registrationHash <- hashFile (packageDb </> unit ++ ".conf")
  writeJson (root </> directory </> "win32-source.json") $ object
    ["archive" .= archive, "archiveSha256" .= archiveHash, "archiveUrl" .= archiveUrl,
     "sourceHashes" .= sourceHashes, "sourcesUnchangedAfterBuild" .= True, "win32Unit" .= unit,
     "interfaceHashes" .= interfaceHashes, "registrationSha256" .= registrationHash]
  let commands = [version,library,extracted,setupBuilt,configured,built,owner,generated] ++ audits
  inputHashes <- hashes root [source,"thc.cabal","t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/WindowsDirectoryFixtures.hs",
    "src/compiler/THC/Plugin.hs","src/compiler/THC/Interface.hs","bin/core_original_foreign.py",
    "bin/audit-core.py","bin/core-capabilities.json","src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java"]
  rawArtifactHashes <- hashes root ([directory </> file | file <- ["pre.cbd","post.cbd","oracle.json","win32-source.json","WindowsDirectoryAudit.hs"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"],name <- entries] ++ concatMap commandArtifacts commands)
  let artifactHashes = Map.mapKeys (map (\c -> if c == '\\' then '/' else c)) rawArtifactHashes
  writeJson manifest $ object
    ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"entries" .= entries,"win32Unit" .= unit,
     "privateRebuiltWin32" .= True,"originalFCallIds" .= True,
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn "windows-directory: four genuine Win32 FCallIds, native scan oracle and eight strict audits"

utf16 :: String -> [Word16]
utf16 = concatMap $ \character -> let n = ord character in if n < 0x10000 then [fromIntegral n]
  else [fromIntegral (0xd800 + div (n - 0x10000) 1024), fromIntegral (0xdc00 + mod (n - 0x10000) 1024)]

observe :: FilePath -> (Int,Int,Int,Int) -> (Ptr Word16 -> Ptr () -> IO (Ptr ())) ->
  (Ptr () -> Ptr () -> IO Int) -> (Ptr () -> IO Int) -> IO Word -> IO Value
observe base (size,nameOffset,nameUnits,noMore) first next close lastError = do
  cwd <- getCurrentDirectory
  createDirectory base
  let names = ["ordinary.txt","snow-\x96ea","face-\x1f642"]
      unicodeDirectory = "\x76ee\x5f55"
  mapM_ (\name -> Bytes.writeFile (base </> name) "contents") names
  createDirectory (base </> "empty")
  createDirectory (base </> unicodeDirectory)
  highLevel <- sort <$> listDirectory base
  let cases = [("populated","*"),("empty","empty\\*"),("missing","missing\\*"),
               ("regular","ordinary.txt\\*"),("unicode-directory",unicodeDirectory ++ "\\*"),
               ("filter","*.txt"),("no-match","absent-*")]
  rows <- forM cases $ \(label,suffix) -> scan label (base </> suffix)
  emptyQuery <- scan "empty-query" ""
  -- Construct the namespace prefix separately: (</>) would normalize the
  -- intentional forward slash and erase the native negative control.
  let extended = "\\\\?\\" ++ map (\c -> if c == '/' then '\\' else c) base
  extendedQuery <- scan "extended" (extended ++ "\\*")
  extendedSlash <- scan "extended-forward-slash" (extended ++ "/*")
  finalCwd <- getCurrentDirectory
  unless (cwd == finalCwd) (die "Windows scan oracle changed process CWD")
  pure $ object ["layout" .= [size,nameOffset,nameUnits,noMore],"names" .= names,
    "unicodeDirectory" .= unicodeDirectory,"listDirectory" .= highLevel,
    "rows" .= (rows ++ [emptyQuery,extendedQuery,extendedSlash]),
    "processCwdUnchanged" .= True]
  where
    scan :: String -> FilePath -> IO Value
    scan label query = allocaBytes (size+16) $ \storage -> do
      fillBytes storage 165 (size+16)
      let buffer = plusPtr storage 8
          guards = and <$> mapM (\offset -> (== (165::Word8)) <$> peekByteOff storage offset)
            ([0..7] ++ [size+8..size+15])
          name = let loop index
                       | index >= nameUnits = die "Unterminated native WIN32_FIND_DATAW name"
                       | otherwise = do
                           value <- peekByteOff buffer (nameOffset + 2*index) :: IO Word16
                           if value == 0 then pure [] else (value:) <$> loop (index+1)
                 in loop 0
      handle <- withArray0 0 (utf16 query) (\path -> first path buffer)
      if handle == intPtrToPtr (-1) then do
        err <- lastError
        guardOk <- guards
        pure $ object ["case" .= label,"invalid" .= True,"error" .= err,"guardsIntact" .= guardOk]
      else do
        (found,err,again,againError,guardOk) <- finally (do
          initial <- name
          let drain acc = do
                result <- next handle buffer
                if result == 0 then do
                  err <- lastError
                  pure (reverse acc,err)
                else name >>= \value -> drain (value:acc)
          (found,err) <- drain [initial]
          again <- next handle buffer
          againError <- lastError
          guardOk <- guards
          pure (found,err,again,againError,guardOk)) (void (close handle))
        pure $ object ["case" .= label,"invalid" .= False,"names" .= sort found,
          "error" .= err,"again" .= again,"againError" .= againError,"guardsIntact" .= guardOk]
#endif
