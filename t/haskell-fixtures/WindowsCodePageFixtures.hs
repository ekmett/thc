-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, OverloadedStrings #-}

-- |
-- Module      : WindowsCodePageFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1; native Windows
--
-- Export genuine Windows foreign declarations and compare native encoding,
-- error-state and allocation behavior with the JVM runtime.
module WindowsCodePageFixtures (prepareWindowsCodePages) where

#if !defined(mingw32_HOST_OS)
import System.Exit (die)
prepareWindowsCodePages :: FilePath -> IO ()
prepareWindowsCodePages _ = die "windows-codepages requires native Windows GHC 9.14.1"
#else
import Control.Exception (finally)
import Control.Monad (filterM, forM, forM_, unless, when)
import Data.Aeson (Value(..), FromJSON, eitherDecodeStrict, fromJSON, Result(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Aeson.Key (Key)
import qualified Data.ByteString as Bytes
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (isPrefixOf, nub, nubBy, sort)
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
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import THC.Compact.Module (readModuleValue)
import THC.Driver.NativeDependencies (nativeLinkInputs, nativeSymbolArchives)
import THC.Driver.Project (prepareWindowsRuntime)
import THC.Driver.PackageNative (installedNativeSignatures, nativeWrapperSource)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String,String)]
operations = [("ansiPage","GetACP"),("consolePage","GetConsoleCP"),("windowsError","GetLastError"),
  ("pageInfo","GetCPInfo"),("leadByte","IsDBCSLeadByteEx"),("multiByte","MultiByteToWideChar"),
  ("wideChar","WideCharToMultiByte"),("wideCharSafe","WideCharToMultiByte"),("mapError","maperrno_func"),("mapCurrentError","maperrno"),
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

originalCall :: Id -> Maybe (String,F.Safety)
originalCall value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just owner) True) F.CCallConv safety))
    | unitString owner == "ghc-internal", unpackFS name `elem` map snd operations,
      safety == F.PlayRisky || (safety == F.PlaySafe && unpackFS name == "WideCharToMultiByte") -> Just (unpackFS name,safety)
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
  pkg <- maybe (takeDirectory ghc </> "ghc-pkg.exe") id <$> lookupEnv "GHC_PKG"
  python <- maybe "python" id <$> lookupEnv "THC_PYTHON"
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let directory = "build/windows-codepages"
      logs = directory </> stamp
      overlay = root </> logs </> "interfaces"
      providers = root </> logs </> "providers"
      output = root </> logs </> "consumer"
      source = "t/fixtures/compiler/WindowsCodePageAudit.hs"
      execute = runLogged 180 root logs
      oneLine = BS.unpack . BS.takeWhile (/= '\r') . BS.takeWhile (/= '\n') . commandStdout
      modules = ["GHC.Internal.Windows","GHC.Internal.IO.Encoding.CodePage","GHC.Internal.IO.Encoding.CodePage.API",
        "GHC.Internal.IO.Windows.Encoding"]
      relative name = map (\c -> if c == '.' then '/' else c) name
      sources = ["src/" ++ relative name ++ ".hs" | name <- modules]
      upstream = root </> "nih/pinned/ghc-9.14.1/libraries/ghc-internal"
  createDirectoryIfMissing True overlay
  createDirectoryIfMissing True output
  createDirectoryIfMissing True providers
  stale <- doesFileExist (root </> directory </> "manifest.json")
  when stale (removeFile (root </> directory </> "manifest.json"))
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Windows code pages require pinned GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  let driverSelection = ["exe:thc","--offline","--disable-shared","--with-compiler=" ++ ghc,"--with-hc-pkg=" ++ pkg]
  built <- execute "driver-build" [] cabal ("build":driverSelection)
  located <- execute "driver-location" [] cabal ("list-bin":driverSelection)
  -- Ordinary foreign calls require the genuine exception bridge. Acquire the
  -- declared thc:runtime component; do not inject its source into this consumer.
  (_, packages) <- prepareWindowsRuntime root ghc pkg (oneLine located) (root </> logs)
  catalog <- either die pure . eitherDecodeStrict =<< Bytes.readFile (root </> "etc/ghc/9.14.1/windows-ghc-internal.json")
  upstreamIdentity <- field "upstream" catalog :: IO Value
  inventory <- field "files" catalog :: IO [Value]
  moduleInputs <- forM sources $ \path -> do
    candidates <- filterM (\item -> (== path) <$> field "path" item) inventory
    case candidates of [item] -> pure item; _ -> die ("Missing pinned source " ++ path)
  headers <- filterM (\item -> ("include/" `isPrefixOf`) <$> field "path" item) inventory
  pinnedFiles <- forM (moduleInputs ++ headers) $ \item -> do
    path <- field "path" item
    expected <- field "sha256" item
    original <- case item of
      Object values | KeyMap.member "source" values -> (root </>) <$> field "source" item
      _ -> pure (upstream </> path)
    actual <- hashFile original
    unless (actual == expected) (die ("Changed pinned source " ++ path))
    pure original
  -- This native oracle source is separate from the Core graph inventory.
  let nativeSource = upstream </> "cbits/Win32Utils.c"
  nativeHash <- hashFile nativeSource
  unless (nativeHash == "f62b489b53c951d02b769a35801abbd9945632ecb9a1f6ade3ccc4ca20060dac")
    (die "Changed pinned Win32Utils.c")
  let usedSources = nativeSource : pinnedFiles
  sourceHashes <- hashes root usedSources
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
      Interface.interfaceCoreCBD ["unit-qualified"] core >>= Bytes.writeFile (providers </> name ++ ".cbd")
      pure [value | (_,body) <- flattenBinds (Interface.interfaceBindings core), value <- variables body, originalCall value /= Nothing]
    let distinct = nubBy (\a b -> originalCall a == originalCall b && eqType (idType a) (idType b)) values
    liftIO $ unless (length distinct == length operations) (die "Missing original Windows FCallIds")
    pure distinct
  -- Derive every native signature from the genuine declaration owners. The
  -- runtime still selects its existing context-owned operations before these
  -- ordinary adapters; no symbol list supplies missing package functions.
  signatures <- fmap (sort . nub . concat) $ forM modules $ \name -> do
    providerModule <- Bytes.readFile (providers </> name ++ ".cbd") >>= either die pure . readModuleValue
    either die pure (installedNativeSignatures "ghc-internal" providerModule)
  unless (not (null signatures) && all (\(_,convention,_,_,_) -> convention == "ccall") signatures)
    (die "Code-page native adapters require original ordinary ccall declarations")
  let nativeEntries = zip [0 :: Int ..] signatures
      adapterEntry index = "thc_windows_codepage_" ++ show index
  wrappers <- either die pure (nativeWrapperSource
    [(signature,adapterEntry index,Nothing) | (index,signature) <- nativeEntries])
  let adapterSource = providers </> "adapters.c"
      adapter = providers </> "adapters.bc"
      exports = providers </> "dependencies.def"
      nativeLibrary = providers </> "dependencies.dll"
      adapterTriple = "x86_64-pc-windows-msvc19.33.0"
  BS.writeFile adapterSource (BS.pack ("#include <stdint.h>\n" ++
    "_Static_assert(sizeof(void *) == 8 && sizeof(uint32_t) == 4, \"unsupported Windows adapter ABI\");\n" ++ wrappers))
  -- Let the native linker resolve the declared libraries and publish their
  -- actual symbols. A PE import thunk exports the ordinary system function;
  -- no package function is replaced or allowed by a tested-symbol inventory.
  BS.writeFile exports (BS.pack ("EXPORTS\n" ++ unlines (nub [symbol | (symbol,_,_,_,_) <- signatures])))
  clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  adapterTarget <- execute "adapter-target" [] clang ["--target=" ++ adapterTriple,"-dumpmachine"]
  unless (oneLine adapterTarget == adapterTriple) (die "Clang did not select the Sulong MSVC ABI")
  adapterBuilt <- execute "adapter-build" [] clang ["--target=" ++ adapterTriple,"-O1","-g","-emit-llvm","-c",adapterSource,"-o",adapter]
  nativeInputs <- nativeLinkInputs pkg (oneLine library) root [] (Just "ghc-internal") ["-package","ghc-internal"]
  nativeArchives <- nativeSymbolArchives pkg (oneLine library) root "ghc-internal" ["-package","ghc-internal"]
    [(symbol,True) | (symbol,_,_,_,_) <- signatures]
  nativeArchiveHashes <- hashes root (map fst nativeArchives)
  nativeLinked <- execute "native-provider-link" [] clang
    (["-shared",exports] ++ map fst nativeArchives ++ nativeInputs ++ ["-o",nativeLibrary])
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
                 [value | value <- originals, originalCall value == Just (symbol,
                   if name `elem` ["wideCharSafe","nativeWideCharSafe"] then F.PlaySafe else F.PlayRisky)]) of
          ([(value,body)],[original]) | Just (_,_,formal,_) <- splitFunTy_maybe (idType value), eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flagsNow) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied),applied)
          _ -> error ("Original Windows FCallId differs from typed consumer " ++ name)
        guests = [specialize name symbol | (name,symbol) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value,body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCoreCBD flagsNow ["unit-qualified"] adapted >>= BS.writeFile (root </> logs </> "pre.cbd")
      (tidied,_) <- hscTidy current adapted
      serializePostTidyCoreCBD flagsNow ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> logs </> "post.cbd")
    natives <- forM operations $ \(name,symbol) -> liftIO $ do
      let nativeName = case name of first:rest -> "native" ++ toUpper first:rest; [] -> error "Empty entry"
      (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) (snd (specialize nativeName symbol)))
      native <- wormhole (hscInterp current) value
      pure (name,native)
    liftIO $ observe (Map.fromList natives)
  writeJson (root </> logs </> "oracle.json") oracle
  audits <- fmap concat $ forM ["pre","post"] $ \stage -> forM (map fst operations) $ \name ->
    execute (stage ++ "-audit-" ++ name) [] python ["bin/audit-core.py","--entry","main:WindowsCodePageAudit." ++ name,
      "--output",logs </> stage ++ "-" ++ name ++ ".audit.json",logs </> stage ++ ".cbd"]
  afterHashes <- hashes root usedSources
  unless (sourceHashes == afterHashes) (die "Compiling declaration interfaces changed upstream sources")
  let commands = [version,library,built,located,registration,rtsRegistration] ++ compiled ++ [adapterTarget,adapterBuilt,nativeLinked] ++ audits
      inputs = [source,"etc/ghc/9.14.1/windows-ghc-internal.json","thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/WindowsCodePageFixtures.hs",
        "src/compiler/THC/Plugin.hs","src/compiler/THC/Interface.hs","bin/audit-core.py","bin/core_original_foreign.py",
        "src/driver/THC/Driver/PackageNative.hs","src/driver/THC/Driver/NativeDependencies.hs","src/driver/THC/Driver/NativeLibrarySources.hs",
        "src/driver/THC/Driver/Project.hs","src/runtime/THC/Exception.hs","src/runtime/THC/Internal/Exception.hs",
        "src/test/java/thc/ForeignExceptionFixtureSupport.java","src/test/java/thc/runtime/WindowsCodePagesTest.java",
        "bin/core-capabilities.json","src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
        "src/main/java/thc/runtime/OriginalStdioExpression.java","src/main/java/thc/runtime/BytecodeProgram.java",
        "src/main/java/thc/runtime/BytecodeRoot.java","src/main/java/thc/runtime/WindowsCodePages.java","src/main/c/windows-directory-abi.c"]
  inputHashes <- hashes root inputs
  rawArtifacts <- hashes root (makeRelative root packages : [logs </> file | file <- ["pre.cbd","post.cbd","oracle.json"]] ++
    [makeRelative root (providers </> name ++ ".cbd") | name <- modules] ++
    map (makeRelative root) [adapterSource,adapter,exports,nativeLibrary] ++
    [makeRelative root (overlay </> relative name ++ ".hi") | name <- modules] ++ concatMap commandArtifacts commands ++
    [logs </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"],name <- map fst operations])
  let artifactHashes = Map.mapKeys (map (\c -> if c == '\\' then '/' else c)) rawArtifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"logs" .= logs,"entries" .= map fst operations,
     "packageManifest" .= makeRelative root packages,
     "originalFCallIds" .= True,"upstream" .= upstreamIdentity,"sourceHashes" .= sourceHashes,
     "nativeArchiveHashes" .= nativeArchiveHashes,
     "nativeAbi" .= [object ["symbol" .= symbol,"entry" .= adapterEntry index,
       "convention" .= convention,"safety" .= safety,"arguments" .= arguments,"result" .= result] |
       (index,(symbol,convention,safety,arguments,result)) <- nativeEntries],
     "inheritedInterfaceHashes" .= inheritedHashes,"inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn "windows-codepages: twelve genuine GHC FCallIds, native encoding/error oracle and 24 strict audits"

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
      wideSafe = unsafeCoerce (natives Map.! "wideCharSafe") :: Word -> Word -> Ptr () -> Int -> Ptr () -> Int -> Ptr () -> Ptr () -> IO Int
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
  let observeWide wideCall = forM wideCases $ \(label,page,flags,input,count,capacity,def,used,sizing) ->
        allocaBytes 64 $ \source -> allocaBytes 64 $ \output ->
        allocaBytes 4 $ \defaultBytes -> allocaBytes 4 $ \usedBytes -> do
          fillBytes source 165 64; fillBytes output 165 64
          fillBytes defaultBytes 0 4; fillBytes usedBytes 90 4
          pokeArray (castPtr source) input
          pokeArray (castPtr defaultBytes) def
          result <- wideCall page flags source count (if sizing then nullPtr else output) capacity
            (if null def then nullPtr else defaultBytes) (if used then usedBytes else nullPtr)
          err <- errorFor result
          content <- bytes output
          usedValue <- peek (castPtr usedBytes :: Ptr Word32)
          pure (object ["case" .= label,"page" .= page,"flags" .= flags,"input" .= input,
            "count" .= count,"capacity" .= capacity,"default" .= def,"used" .= used,"sizing" .= sizing,
            "result" .= result,"error" .= err,"bytes" .= content,"usedValue" .= usedValue])
  wideRows <- observeWide wide
  wideSafeRows <- observeWide wideSafe
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
    "mappedCurrent" .= mappedCurrent,"multi" .= multiRows,"wide" .= wideRows,"wideSafe" .= wideSafeRows,"messages" .= messages])

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
