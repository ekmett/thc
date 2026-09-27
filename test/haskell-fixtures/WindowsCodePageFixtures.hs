-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}
module WindowsCodePageFixtures (prepareWindowsCodePages) where

#if !defined(mingw32_HOST_OS)
import System.Exit (die)
prepareWindowsCodePages :: FilePath -> IO ()
prepareWindowsCodePages _ = die "windows-codepages requires native Windows GHC 9.14.1"
#else
import Control.Exception (finally)
import Control.Monad (filterM, forM, forM_, unless, void, when)
import Data.Aeson (Value(..), FromJSON, eitherDecodeStrict, fromJSON, Result(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Aeson.Key (Key)
import qualified Data.ByteString as Bytes
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (nubBy, sort)
import qualified Data.Map.Strict as Map
import qualified Distribution.InstalledPackageInfo as Package
import Data.Time (getCurrentTime, defaultTimeLocale, formatTime)
import Data.Word (Word8, Word16, Word32)
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Array (peekArray, peekArray0, pokeArray)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, castPtr, nullPtr)
import Foreign.Storable (peek)
import FixtureSupport
import GHC hiding (exprType)
import GHC.Plugins hiding ((<>), count)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Runtime.Interpreter (wormhole)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import System.Directory (canonicalizePath, copyFile, createDirectoryIfMissing, doesDirectoryExist, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative, takeDirectory, takeExtension)
import qualified THC.Interface as Interface
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String,String)]
operations = [("ansiPage","GetACP"),("consolePage","GetConsoleCP"),("windowsError","GetLastError"),
  ("pageInfo","GetCPInfo"),("leadByte","IsDBCSLeadByteEx"),("multiByte","MultiByteToWideChar"),
  ("wideChar","WideCharToMultiByte"),("mapError","maperrno_func"),("mapCurrentError","maperrno"),
  ("errorMessage","base_getErrorMessage"),("localFree","LocalFree")]

field :: FromJSON a => Key -> Value -> IO a
field name (Object values) = case KeyMap.lookup name values of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die message
  Nothing -> die ("Missing Windows code-page fixture field " ++ show name)
field _ _ = die "Expected Windows code-page fixture object"

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

originalSymbol :: Id -> Maybe String
originalSymbol value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just owner) True) F.CCallConv F.PlayRisky))
    | unitString owner == "ghc-internal", unpackFS name `elem` map snd operations -> Just (unpackFS name)
  _ -> Nothing

filesUnder :: FilePath -> IO [FilePath]
filesUnder directory = do
  names <- sort <$> listDirectory directory
  fmap concat $ forM names $ \name -> do
    nested <- doesDirectoryExist (directory </> name)
    if nested then map (name </>) <$> filesUnder (directory </> name) else pure [name]

prepareWindowsCodePages :: FilePath -> IO ()
prepareWindowsCodePages root = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC" >>= canonicalizePath
  python <- maybe "python" id <$> lookupEnv "THC_PYTHON"
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let directory = "build/windows-codepages"
      logs = directory </> stamp
      overlay = root </> logs </> "interfaces"
      output = root </> logs </> "consumer"
      source = "compiler/test-fixtures/WindowsCodePageAudit.hs"
      execute = runLogged 180 root logs
      pkg = takeDirectory ghc </> "ghc-pkg.exe"
      oneLine = BS.unpack . BS.takeWhile (/= '\r') . BS.takeWhile (/= '\n') . commandStdout
      modules = ["GHC.Internal.Windows","GHC.Internal.IO.Encoding.CodePage","GHC.Internal.IO.Encoding.CodePage.API"]
      relative name = map (\c -> if c == '.' then '/' else c) name
      sources = ["src" </> relative name ++ ".hs" | name <- modules]
      archivePrefix = "ghc-9.14.1/libraries/ghc-internal"
      extracted = root </> logs </> "source"
      upstream = extracted </> archivePrefix
  createDirectoryIfMissing True overlay
  createDirectoryIfMissing True output
  stale <- doesFileExist (root </> directory </> "manifest.json")
  when stale (removeFile (root </> directory </> "manifest.json"))
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Windows code pages require pinned GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  catalog <- either die pure . eitherDecodeStrict =<< Bytes.readFile (root </> "compiler/windows-ghc-internal.json")
  archive <- field "archive" catalog
  archiveHash <- field "sha256" archive
  archiveUrl <- field "url" archive
  archivePath <- maybe (root </> "vendor/archives/ghc-9.14.1-src.tar.xz") id <$> lookupEnv "THC_GHC_SOURCE_ARCHIVE"
  present <- doesFileExist archivePath
  unless present $ do
    createDirectoryIfMissing True (takeDirectory archivePath)
    void $ execute "download" [] "curl.exe" ["--fail","--location",archiveUrl,"-o",archivePath]
  actualHash <- hashFile archivePath
  unless (actualHash == archiveHash) (die "Pinned GHC source archive SHA256 differs")
  createDirectoryIfMissing True extracted
  extraction <- execute "extract" [] "tar.exe" (["-xJf",archivePath,"-C",extracted] ++
    [archivePrefix ++ "/" ++ map (\c -> if c == '\\' then '/' else c) path | path <- sources ++ ["include","cbits/Win32Utils.c"]])
  inventory <- field "files" catalog :: IO [Value]
  copiedSource <- filesUnder upstream
  forM_ copiedSource $ \path -> do
    let normalized = map (\c -> if c == '\\' then '/' else c) path
    candidates <- filterM (\item -> (== normalized) <$> field "path" item) inventory
    expected <- if normalized == "cbits/Win32Utils.c"
      -- The production Core graph catalog covers Haskell and headers. This
      -- separately pinned C source documents the native oracle's error mapping.
      then pure "f62b489b53c951d02b769a35801abbd9945632ecb9a1f6ade3ccc4ca20060dac"
      else case candidates of [item] -> field "sha256" item; _ -> die ("Missing pinned source " ++ path)
    actual <- hashFile (upstream </> path)
    unless (actual == expected) (die ("Changed pinned source " ++ path))
  sourceHashes <- hashes root [upstream </> path | path <- copiedSource]
  registration <- execute "ghc-internal-package" [] pkg ["--expand-pkgroot","describe","ghc-internal"]
  rtsRegistration <- execute "rts-package" [] pkg ["--expand-pkgroot","describe","rts"]
  package <- either (die . show) (pure . snd) (Package.parseInstalledPackageInfo (commandStdout registration))
  rts <- either (die . show) (pure . snd) (Package.parseInstalledPackageInfo (commandStdout rtsRegistration))
  installed <- case Package.importDirs package of [path] -> pure path; _ -> die "Expected one native interface directory"
  inherited <- filter ((==".hi") . takeExtension) <$> filesUnder installed
  forM_ inherited $ \path -> do
    createDirectoryIfMissing True (takeDirectory (overlay </> path))
    copyFile (installed </> path) (overlay </> path)
  inheritedHashes <- hashes root [installed </> path | path <- inherited]
  let flags = ["-c","-O2","-g","-dcore-lint","-fwrite-if-simplified-core","-fforce-recomp",
        "-fignore-interface-pragmas","-XNoImplicitPrelude","-XNoPolyKinds","-DBIGNUM_GMP","-D_WIN32_WINNT=0x06010000",
        "-this-unit-id","ghc-internal","-package","ghc-internal","-i" ++ overlay,"-odir",overlay,"-hidir",overlay] ++
        ["-I" ++ path | path <- Package.includeDirs rts ++ Package.includeDirs package ++ [upstream </> "include"]]
  compiled <- forM (zip modules sources) $ \(name,path) -> execute ("compile-" ++ name) [] ghc (flags ++ [upstream </> path])
  -- Only declaration interfaces need rebuilding here. The complete production
  -- acquisition retains its separate normal-O2/imported-pragmas graph recipe.
  originals <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    before <- getSession
    (configured,_,_) <- parseDynamicFlags (hsc_logger before) initial (map noLoc
      ["-clear-package-db","-global-package-db","-package-env","-","-fno-ignore-interface-pragmas",
       "-this-unit-id","ghc-internal","-i","-i" ++ overlay])
    _ <- setSessionDynFlags configured { ghcMode = OneShot }
    environment <- getSession
    values <- liftIO $ fmap concat $ forM modules $ \name -> do
      let expected = mkModule (stringToUnit "ghc-internal") (mkModuleName name)
      core <- Interface.loadInterfaceCore environment expected (overlay </> relative name ++ ".hi") >>= maybe
        (die "Original Windows declaration interface lacks full Core") pure
      pure [value | (_,body) <- flattenBinds (Interface.interfaceBindings core), value <- variables body, originalSymbol value /= Nothing]
    let distinct = nubBy (\a b -> originalSymbol a == originalSymbol b && eqType (idType a) (idType b)) values
    liftIO $ unless (length distinct == length operations) (die "Missing original Windows FCallIds")
    pure distinct
  oracle <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    before <- getSession
    (configured,_,_) <- parseDynamicFlags (hsc_logger before) initial (map noLoc
      ["-O2","-package","ghc-internal","-fno-external-interpreter","-dcore-lint","-odir",output,"-hidir",output])
    _ <- setSessionDynFlags configured
    flagsNow <- getSessionDynFlags
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [value] -> pure value; _ -> liftIO (die "Unexpected code-page consumer graph")
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
        specialize name symbol = case ([(value,body) | (value,body) <- bindings,
                 getOccString value == name, isExternalName (varName value)],
                 [value | value <- originals, originalSymbol value == Just symbol]) of
          ([(value,body)],[original]) | Just (_,_,formal,_) <- splitFunTy_maybe (idType value), eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flagsNow) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied),applied)
          _ -> error ("Original Windows FCallId differs from typed consumer " ++ name)
        guests = [specialize name symbol | (name,symbol) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value,body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flagsNow ["unit-qualified"] adapted >>= writeFile (root </> logs </> "pre.json")
      (tidied,_) <- hscTidy current adapted
      serializePostTidyCore flagsNow ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> logs </> "post.json")
    natives <- forM operations $ \(name,symbol) -> liftIO $ do
      let nativeName = case name of first:rest -> "native" ++ toUpper first:rest; [] -> error "Empty entry"
      (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) (snd (specialize nativeName symbol)))
      native <- wormhole (hscInterp current) value
      pure (name,native)
    liftIO $ observe (Map.fromList natives)
  writeJson (root </> logs </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre","post"] $ \stage -> forM (map fst operations) $ \name ->
    execute (stage ++ "-audit-" ++ name) [] python ["scripts/audit-core.py","--entry",name,
      "--output",logs </> stage ++ "-" ++ name ++ ".audit.json",logs </> stage ++ ".json"]
  afterHashes <- hashes root [upstream </> path | path <- copiedSource]
  unless (sourceHashes == afterHashes) (die "Compiling declaration interfaces changed upstream sources")
  let commands = [version,library,extraction,registration,rtsRegistration] ++ compiled ++ audits
      inputs = [source,"compiler/windows-ghc-internal.json","thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/WindowsCodePageFixtures.hs",
        "compiler/THC/Plugin.hs","compiler/THC/Interface.hs","scripts/audit-core.py","scripts/core_original_foreign.py",
        "scripts/core-capabilities.json","src/main/kotlin/thc/runtime/CoreOriginalStdio.kt","src/main/c/windows-directory-abi.c"]
  inputHashes <- hashes root inputs
  rawArtifacts <- hashes root ([logs </> file | file <- ["pre.json","post.json","oracle.json"]] ++
    [makeRelative root (overlay </> relative name ++ ".hi") | name <- modules] ++ concatMap commandArtifacts commands ++
    [logs </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"],name <- map fst operations])
  let artifactHashes = Map.mapKeys (map (\c -> if c == '\\' then '/' else c)) rawArtifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"logs" .= logs,"entries" .= map fst operations,
     "originalFCallIds" .= True,"archiveSha256" .= archiveHash,"sourceHashes" .= sourceHashes,
     "inheritedInterfaceHashes" .= inheritedHashes,"inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn "windows-codepages: eleven genuine GHC FCallIds, native encoding/error oracle and 22 strict audits"

-- Each value was compiled by GHC from the typed consumer and its actual
-- original FCallId, as in the existing native directory oracle.
observe :: Map.Map String a -> IO Value
observe natives = do
  let ansi = unsafeCoerce (natives Map.! "ansiPage") :: IO Word
      console = unsafeCoerce (natives Map.! "consolePage") :: IO Word
      lastError = unsafeCoerce (natives Map.! "windowsError") :: IO Word
      info = unsafeCoerce (natives Map.! "pageInfo") :: Word -> Ptr () -> IO Int
      lead = unsafeCoerce (natives Map.! "leadByte") :: Word -> Word -> IO Int
      multi = unsafeCoerce (natives Map.! "multiByte") :: Word -> Word -> Ptr () -> Int -> Ptr () -> Int -> IO Int
      wide = unsafeCoerce (natives Map.! "wideChar") :: Word -> Word -> Ptr () -> Int -> Ptr () -> Int -> Ptr () -> Ptr () -> IO Int
      mapping = unsafeCoerce (natives Map.! "mapError") :: Word -> IO Int
      mapCurrent = unsafeCoerce (natives Map.! "mapCurrentError") :: IO ()
      message = unsafeCoerce (natives Map.! "errorMessage") :: Word -> IO (Ptr ())
      release = unsafeCoerce (natives Map.! "localFree") :: Ptr () -> IO (Ptr ())
      bytes pointer = peekArray 64 (castPtr pointer :: Ptr Word8)
      errorFor result = if result == 0 then lastError else pure 0
  acp <- ansi
  ccp <- console
  consoleError <- if ccp == 0 then lastError else pure 0
  infos <- forM [0,1252,932,65001,999999] $ \page -> allocaBytes 64 $ \output -> do
    fillBytes output 165 64
    result <- info page output
    err <- errorFor result
    content <- bytes output
    pure (object ["page" .= page,"result" .= result,"error" .= err,"bytes" .= content])
  leads <- forM [(page,byte) | page <- [1252,932,65001,999999],byte <- [0,64,129,255]] $ \(page,byte) -> do
    result <- lead page byte
    -- False also means an ordinary non-lead byte. Only the invalid page is an
    -- error control; its captured error must not be inferred from that Boolean.
    err <- if page == 999999 then lastError else pure 0
    pure (object ["page" .= page,"byte" .= byte,"result" .= result,"error" .= err])
  mapped <- forM ([0..260] ++ [1816,0xffffffff]) $ \err -> do
    result <- mapping err
    pure [toInteger err,toInteger result]
  mappedCurrent <- allocaBytes 20 $ \output -> do
    result <- info 999999 output
    unless (result == 0) (die "Invalid code-page negative control unexpectedly succeeded")
    err <- lastError
    mapCurrent
    Errno value <- getErrno
    pure (object ["error" .= err,"errno" .= toInteger value])
  multiRows <- forM multiCases $ \(label,page,flags,input,count,capacity,alias,sizing) ->
    allocaBytes 64 $ \source -> allocaBytes 64 $ \destination -> do
      fillBytes source 165 64; fillBytes destination 165 64
      pokeArray (castPtr source) input
      let output = if sizing then nullPtr else if alias then source else destination
      result <- multi page flags source count output capacity
      err <- errorFor result
      content <- bytes (if alias then source else destination)
      pure (object ["case" .= label,"page" .= page,"flags" .= flags,"input" .= input,
        "count" .= count,"capacity" .= capacity,"alias" .= alias,"sizing" .= sizing,
        "result" .= result,"error" .= err,"bytes" .= content])
  wideRows <- forM wideCases $ \(label,page,flags,input,count,capacity,def,used,sizing) ->
    allocaBytes 64 $ \source -> allocaBytes 64 $ \output ->
    allocaBytes 4 $ \defaultBytes -> allocaBytes 4 $ \usedBytes -> do
      fillBytes source 165 64; fillBytes output 165 64
      fillBytes defaultBytes 0 4; fillBytes usedBytes 90 4
      pokeArray (castPtr source) input
      pokeArray (castPtr defaultBytes) def
      result <- wide page flags source count (if sizing then nullPtr else output) capacity
        (if null def then nullPtr else defaultBytes) (if used then usedBytes else nullPtr)
      err <- errorFor result
      content <- bytes output
      usedValue <- peek (castPtr usedBytes :: Ptr Word32)
      pure (object ["case" .= label,"page" .= page,"flags" .= flags,"input" .= input,
        "count" .= count,"capacity" .= capacity,"default" .= def,"used" .= used,"sizing" .= sizing,
        "result" .= result,"error" .= err,"bytes" .= content,"usedValue" .= usedValue])
  messages <- forM [2,5,87,1113,0xffffffff] $ \err -> do
    pointer <- message err
    if pointer == nullPtr then pure (object ["error" .= err,"null" .= True,"units" .= ([] :: [Word16])])
    else do
      content <- peekArray0 0 (castPtr pointer :: Ptr Word16) `finally` do
        result <- release pointer
        unless (result == nullPtr) (die "Native LocalFree failed")
      pure (object ["error" .= err,"null" .= False,"units" .= content])
  nullReleased <- release nullPtr
  unless (nullReleased == nullPtr) (die "Native LocalFree(NULL) failed")
  pure (object ["ansi" .= acp,"console" .= ccp,"consoleError" .= consoleError,"info" .= infos,"lead" .= leads,"mapping" .= mapped,
    "mappedCurrent" .= mappedCurrent,"multi" .= multiRows,"wide" .= wideRows,"messages" .= messages])

multiCases :: [(String,Word,Word,[Word8],Int,Int,Bool,Bool)]
multiCases =
  [("ansi-euro",1252,0,[128,65],2,8,False,False)
  ,("dbcs",932,0,[130,160],2,8,False,False)
  ,("utf8-supplementary",65001,0,[240,159,153,130],4,8,False,False)
  ,("utf8-invalid",65001,8,[192],1,8,False,False)
  ,("utf8-replacement",65001,0,[192],1,8,False,False)
  ,("invalid-page",999999,0,[65],1,8,False,False)
  ,("invalid-flags",65001,1,[65],1,8,False,False)
  ,("short",65001,0,[65,66],2,1,False,False)
  ,("terminated",1252,0,[128,0],-1,8,False,False)
  ,("sizing",65001,0,[240,159,153,130],4,0,False,True)
  ,("same-buffer",1252,0,[65,0],1,8,True,False)
  ,("empty",1252,0,[65],0,8,False,False)]

wideCases :: [(String,Word,Word,[Word16],Int,Int,[Word8],Bool,Bool)]
wideCases =
  [("ansi-euro",1252,0,[0x20ac,65],2,16,[],True,False)
  ,("dbcs",932,0,[0x3042],1,16,[],True,False)
  ,("utf8-supplementary",65001,0,[0xd83d,0xde42],2,16,[],False,False)
  ,("utf16-invalid",65001,128,[0xd800],1,16,[],False,False)
  ,("utf16-replacement",65001,0,[0xd800],1,16,[],False,False)
  ,("default-character",1252,0,[0x4e00],1,16,[33],True,False)
  ,("default-dbcs",932,0,[0x2603],1,16,[129,172],True,False)
  ,("default-system",1252,0,[0x4e00],1,16,[],True,False)
  ,("invalid-utf8-default",65001,0,[65],1,16,[33],False,False)
  ,("invalid-page",999999,0,[65],1,16,[],False,False)
  ,("short",65001,0,[0xd83d,0xde42],2,1,[],False,False)
  ,("terminated",1252,0,[0x20ac,0],-1,16,[],True,False)
  ,("sizing",65001,0,[0xd83d,0xde42],2,0,[],False,True)]
#endif
