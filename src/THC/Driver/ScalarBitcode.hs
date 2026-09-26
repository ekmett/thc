-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
-- | The first package-owned C profile. Cabal's configured C invocation produces
-- LLVM; its native roundtrip must equal Cabal's actual object before admission.
module THC.Driver.ScalarBitcode
  ( ScalarBitcode, withScalarBitcode, scalarBuildInputs, linkScalarBitcode
  , parseDependencies, scalarFunctions ) where

import Control.Exception (bracket)
import Control.Monad (forM, forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, (.=), fromJSON, Result(..))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (isAlpha, isAlphaNum, isSpace)
import Data.List (intercalate, isInfixOf, isPrefixOf, isSuffixOf, nub, sort, sortOn)
import qualified Data.Text as T
import Distribution.Compiler (CompilerFlavor(GHC))
import Distribution.PackageDescription
import Distribution.Pretty (prettyShow)
import qualified Distribution.Simple.Compiler as Compiler
import Distribution.Simple.Configure (getPersistBuildConfig)
import Distribution.Simple.Flag (toFlag)
import Distribution.Simple.GHC (componentCcGhcOptions)
import Distribution.Simple.LocalBuildInfo
import Distribution.Simple.Program (ghcProgram, lookupProgram, programPath)
import Distribution.Simple.Program.GHC (GhcOptions(..), renderGhcOptions)
import Distribution.Simple.Program.Run (programInvocation, progInvokeArgs, progInvokeEnv)
import Distribution.Utils.Path (getSymbolicPath, makeSymbolicPath)
import Distribution.Verbosity (silent)
import Numeric (showHex)
import System.Directory
import System.Environment (getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath
import System.IO (hClose, openTempFile)
import System.Process (proc, readCreateProcessWithExitCode, CreateProcess(..))

-- Kept inside the bracket containing the compiler's immutable bitcode snapshot.
data ScalarBitcode = ScalarBitcode
  { scalarBuildInputs :: Value, scalarRoot :: FilePath, scalarDirectory :: FilePath
  , scalarObject :: FilePath, scalarTarget :: String, scalarUnit :: String
  , scalarDefinitions :: [(String, [String], String)]
  , scalarTools :: (FilePath, FilePath), scalarInputs :: [(FilePath,String)] }

withScalarBitcode :: FilePath -> FilePath -> String -> Value -> (Maybe ScalarBitcode -> IO a) -> IO a
withScalarBitcode dist ghc unit component action = do
  lbi <- getPersistBuildConfig Nothing (makeSymbolicPath dist)
  clbi <- case [c | c <- allComponentsInBuildOrder lbi, prettyShow (componentUnitId c) == unit] of
    [value] -> pure value
    _ -> fail "scalar cbits: setup-config component differs from Cabal plan"
  let selected = getComponent (localPkgDescr lbi) (componentLocalName clbi)
      info = componentBuildInfo selected
  if null (cSources info) then action Nothing else do
    root <- get component "src-dir" >>= canonicalizePath
    check (buildType (localPkgDescr lbi) == Simple && length (cSources info) == 1 &&
      null (cxxSources info) && null (asmSources info) && null (cmmSources info) && null (jsSources info) &&
      null (extraLibs info) && null (extraLibsStatic info) && null (extraGHCiLibs info) &&
      null (ldOptions info) && null (frameworks info))
      "scalar cbits requires one local C translation unit and no other native products or libraries"
    check (not (withProfExe lbi || withProfLib lbi || withDynExe lbi) &&
      prettyShow (Compiler.compilerId (compiler lbi)) == "ghc-9.14.1")
      "scalar cbits requires the selected GHC9.14.1 vanilla component"
    source <- case cSources info of
      [path] -> pure (getSymbolicPath path)
      _ -> fail "scalar cbits requires one C source"
    let targetDir = case selected of
          CLib{} -> getSymbolicPath (componentBuildDir lbi clbi)
          _ -> ""
    check (not (null targetDir) && not (isAbsolute source) && takeExtension source == ".c" &&
      ".." `notElem` splitDirectories source) "scalar cbits requires a registered local library with a package-relative C source"
    let options = (componentCcGhcOptions silent lbi info clbi (makeSymbolicPath targetDir)
                    (makeSymbolicPath source)) { ghcOptFPic = toFlag True }
    configured <- maybe (fail "scalar cbits: configured GHC missing") pure (lookupProgram ghcProgram (withPrograms lbi))
    actualGhc <- canonicalizePath (programPath configured)
    expectedGhc <- canonicalizePath ghc
    check (actualGhc == expectedGhc) "scalar cbits: configured GHC differs from build-info"
    let invocation = programInvocation configured (renderGhcOptions (compiler lbi) (hostPlatform lbi) options)
        arguments = progInvokeArgs invocation
    check (all ((== "PATH") . fst) (progInvokeEnv invocation) && all allowedCc (ghcOptCcOptions options) &&
      not (any (\flag -> any (`isPrefixOf` flag) ["-optc", "-pgma", "-pgml", "-pgmP", "-B"])
        (hcOptions GHC info))) "scalar cbits: unsupported configured native options or environment"
    cc <- case [path | (flag,path) <- zip arguments (drop 1 arguments), flag == "-pgmc"] of
      [] -> fail "scalar cbits: configured Clang must be explicit in the C recipe"
      paths -> do
        check (isAbsolute (last paths)) "scalar cbits requires an absolute configured Clang path"
        resolveTool (last paths)
    ccVersion <- command root cc ["--version"]
    check ("clang version" `isInfixOf` ccVersion) "scalar cbits requires configured Clang; GCC is not substituted"
    link <- llvmTool "THC_LLVM_LINK" "llvm-link"
    opt <- llvmTool "THC_LLVM_OPT" "opt"
    nm <- llvmTool "THC_LLVM_NM" "llvm-nm"
    linkVersion <- command root link ["--version"]
    optVersion <- command root opt ["--version"]
    nmVersion <- command root nm ["--version"]
    original <- canonicalizePath (root </> targetDir </> replaceExtension source "o")
    originalHash <- digest original
    externalSymbols <- command root nm ["--undefined-only","--format=posix",original]
    check (all isSpace externalSymbols) "scalar cbits: Cabal's native object has external dependencies"
    withDirectory dist $ \temporary -> do
      let bitcode = temporary </> "original.bc"
          dependencies = temporary </> "original.d"
          disassembly = temporary </> "original.ll"
          native = temporary </> "certified.o"
      -- No compiler substitution, C macro rewriting, or guessed include path.
      _ <- commandWithEnv (progInvokeEnv invocation) root actualGhc (arguments ++ ["-o",bitcode,"-optc-emit-llvm",
        "-optc-MD","-optc-MF","-optc" ++ dependencies,"-optc-MT","-optcthc_scalar_input",
        "-optc-Werror=date-time"])
      dependencyText <- readFile dependencies
      paths <- either fail pure (parseDependencies dependencyText)
      inputs <- observe =<< mapM (canonicalizePath . (root </>)) paths
      sourcePath <- canonicalizePath (root </> source)
      check (sourcePath `elem` map fst inputs) "scalar cbits: dependency inventory omitted its source"
      _ <- command root cc ["-S","-emit-llvm",bitcode,"-o",disassembly]
      ir <- readFile disassembly
      definitions <- either fail pure (scalarFunctions ir)
      target <- case [value | line <- lines ir, Just value <- [quoted "target triple = " line]] of
        [value] -> pure value
        _ -> fail "scalar cbits: LLVM target missing or ambiguous"
      -- This deliberately conservative equality also rejects a stale native
      -- object, nondeterministic C expansion, or unsupported backend options.
      _ <- command root cc (["-c",bitcode,"-o",native,"-fPIC","--target=" ++ target] ++ ghcOptCcOptions options)
      certified <- digest native
      check (certified == originalHash) "scalar cbits: bitcode does not reproduce Cabal's native object"
      verify inputs
      actualHash <- digest original
      check (actualHash == originalHash) "scalar cbits: native object changed"
      let recipe = object ["schema" .= (1::Int),"profile" .= ("thc-local-scalar-ccall-v1"::String),
            "ghcArguments" .= arguments,"ghcEnvironment" .= progInvokeEnv invocation,
            "clang" .= cc,"clangVersion" .= ccVersion,
            "llvmLink" .= linkVersion,"llvmOpt" .= optVersion,"llvmNm" .= nmVersion,"target" .= target,
            "nativeObject" .= object ["path" .= original,"sha256" .= originalHash],
            "inputs" .= [object ["path" .= path,"sha256" .= hash] | (path,hash) <- inputs]]
          prepared = ScalarBitcode recipe root temporary bitcode target unit definitions (link,opt)
            ((original,originalHash):inputs)
      result <- action (Just prepared)
      verify (scalarInputs prepared)
      pure result
  where
    allowedCc flag = flag `elem` ["-O0","-O1","-O2","-O3","-std=c11","-std=c17","-std=gnu11","-std=gnu17",
      "-fno-strict-aliasing"] || any (`isPrefixOf` flag) ["-D","-U","-W"]

linkScalarBitcode :: ScalarBitcode -> String -> [(String,BS.ByteString)] -> IO [(String,BS.ByteString)]
linkScalarBitcode recipe componentHash modules = do
  parsed <- forM modules $ \(name,bytes) -> do
    value <- either fail pure (eitherDecodeStrict' bytes)
    imports <- case member value "staticForeignImports" of
      Nothing -> pure []
      Just proof -> do
        check (member proof "status" == Just (String "verified") && member proof "schema" == Just (Number 1) &&
          member proof "unit" == Just (String (T.pack (scalarUnit recipe))) &&
          member value "foreign" == Nothing) "scalar cbits requires verified import associations without native stub obligations"
        entries <- get proof "imports"
        forM entries $ \entry -> do
          call <- get entry "emitted"
          symbol <- get call "symbol"
          arguments <- get call "arguments"
          result <- get call "result"
          output <- case result of
            ["void",rep] -> pure rep
            _ -> fail "scalar cbits requires one State/scalar result"
          check (member call "unit" == Just (String (T.pack (scalarUnit recipe))) &&
            member call "convention" == Just (String "ccall") && member call "safety" == Just (String "unsafe") &&
            member entry "isFunction" == Just (Bool True) && member entry "header" == Just Null &&
            not (null arguments) && last arguments == "void" &&
            all (`elem` reps) (init arguments ++ [output])) "scalar cbits import is outside the unsafe scalar ccall profile"
          pure (symbol,init arguments,output)
    pure (name,value,imports)
  let abi = sortOn first (nub (concat [imports | (_,_,imports) <- parsed]))
  check (not (null abi) && length (map first abi) == length (nub (map first abi)))
    "scalar cbits has no imports or conflicting signatures"
  forM_ abi $ \declaration -> check (declaration `elem` scalarDefinitions recipe)
    ("scalar cbits LLVM type disagrees with GHC: " ++ first declaration)
  let entries = [(symbol,"thc_scalar_" ++ componentHash ++ "_" ++ show index,args,result)
                | (index,(symbol,args,result)) <- zip [0::Int ..] abi]
      wrapper = scalarDirectory recipe </> "entries.ll"
      linked = scalarDirectory recipe </> "linked.bc"
      private = scalarDirectory recipe </> "private.bc"
      (link,opt) = scalarTools recipe
  writeFile wrapper ("target triple = " ++ show (scalarTarget recipe) ++ "\n" ++ concatMap wrapperIr entries)
  _ <- command (scalarRoot recipe) link [scalarObject recipe,wrapper,"-o",linked]
  _ <- command (scalarRoot recipe) opt ["-passes=internalize,globaldce",
    "-internalize-public-api-list=" ++ intercalate "," [entry | (_,entry,_,_) <- entries],linked,"-o",private]
  bytes <- BS.readFile private
  verify (scalarInputs recipe)
  let proof = object ["schema" .= (1::Int),"format" .= ("llvm-bitcode"::String),
        "profile" .= ("thc-local-scalar-ccall-v1"::String),"unit" .= scalarUnit recipe,
        "target" .= scalarTarget recipe,"componentSha256" .= componentHash,
        "bitcodeSha256" .= sha bytes,"bitcodeHex" .= hex bytes,
        "abi" .= [object ["symbol" .= symbol,"entry" .= entry,"arguments" .= args,"result" .= result]
                  | (symbol,entry,args,result) <- entries]]
  forM parsed $ \(name,value,imports) -> case value of
    Object fields -> pure (name,BL.toStrict (encode (Object (if null imports then fields else KM.insert "packageScalarLink" proof fields))))
    _ -> fail "scalar cbits module is not an object"
  where first (name,_,_) = name

reps :: [String]
reps = ["Int32Rep","Int64Rep","FloatRep","DoubleRep"]
llvmType :: String -> String
llvmType rep = case rep of
  "Int32Rep" -> "i32"
  "Int64Rep" -> "i64"
  "FloatRep" -> "float"
  "DoubleRep" -> "double"
  _ -> error "validated scalar representation"
wrapperIr :: (String,String,[String],String) -> String
wrapperIr (symbol,entry,arguments,result) =
  let types = map llvmType arguments; output = llvmType result
      parameters = intercalate ", " [ty ++ " %a" ++ show index | (index,ty) <- zip [0::Int ..] types]
  in "declare " ++ output ++ " @" ++ symbol ++ "(" ++ intercalate ", " types ++ ")\n" ++
     "define " ++ output ++ " @" ++ entry ++ "(" ++ parameters ++ ") {\n" ++
     "  %result = call " ++ output ++ " @" ++ symbol ++ "(" ++ parameters ++ ")\n" ++
     "  ret " ++ output ++ " %result\n}\n"

-- Parse only canonical LLVM-disassembled scalar definitions. Everything outside
-- this closed module profile is rejected, rather than guessed from C spelling.
scalarFunctions :: String -> Either String [(String,[String],String)]
scalarFunctions ir = do
  let rows = map (dropWhile isSpace) (lines ir)
  unless (not (any (\line -> any (`isPrefixOf` line) ["declare ","@","module asm"]) rows) &&
    not (any (`isInfixOf` ir) ["thread_local"," asm ","callbr ","invoke ","inttoptr ","ptrtoint ","blockaddress("]) &&
    all (\line -> not ("call " `isInfixOf` line) || '@' `elem` takeWhile (/= '(') line) rows)
    (Left "scalar cbits LLVM contains external declarations, globals, TLS, assembly or exceptional calls")
  let functions = [line | line <- rows,"define " `isPrefixOf` line]
  definitions <- traverse signature functions
  unless (not (null definitions) && length definitions == length (nub [name | (name,_,_) <- definitions]))
    (Left "scalar cbits LLVM function inventory is empty or ambiguous")
  pure [definition | (line,definition) <- zip functions definitions,
         not (any (`elem` words (takeWhile (/= '@') line)) ["internal","private"])]
  where
    rep "i32" = Right "Int32Rep"; rep "i64" = Right "Int64Rep"
    rep "float" = Right "FloatRep"; rep "double" = Right "DoubleRep"
    rep _ = Left "scalar cbits LLVM has a non-scalar function carrier"
    signature line = do
      let (prefix,rest) = break (== '@') line
          (name,parameters) = break (== '(') (drop 1 rest)
          (args,suffix) = break (== ')') (drop 1 parameters)
      unless (identifier name && not (null parameters) && not (null suffix) &&
        not (any (\word -> "cc" `isSuffixOf` word || word `elem` ["cc","inreg","sret","signext","zeroext",
          "weak","weak_odr","linkonce","linkonce_odr","extern_weak","available_externally"])
          (words prefix)) && "{" `isSuffixOf` line)
        (Left "scalar cbits LLVM has an unsupported function declaration")
      result <- case reverse (words prefix) of value:_ -> rep value; _ -> Left "LLVM result missing"
      inputs <- if null args then pure [] else traverse parameter (split ',' args)
      pure (name,inputs,result)
    parameter text = case words text of
      value:attributes -> do
        -- The wrappers preserve scalar types, not ABI-affecting extension
        -- attributes. Only semantic attributes that can be omitted are allowed.
        unless (all (\attribute -> attribute `elem` ["noundef","returned"] || "%" `isPrefixOf` attribute) attributes)
          (Left "scalar cbits LLVM parameter attributes are unsupported")
        rep value
      _ -> Left "LLVM parameter missing"

parseDependencies :: String -> Either String [FilePath]
parseDependencies text = case break (== ':') text of
  ("thc_scalar_input",_:body) -> fmap (sort . nub) (tokens body)
  _ -> Left "scalar cbits dependency output has an unexpected target"
  where
    tokens = go [] []
    go word found [] = Right (reverse (if null word then found else reverse word:found))
    go word found ('\\':'\n':rest) = go word found rest
    go word found ('\\':c:rest) = go (c:word) found rest
    go _ _ ['\\'] = Left "scalar cbits dependency escape is incomplete"
    go word found (c:rest) | isSpace c = go [] (if null word then found else reverse word:found) rest
                         | c `elem` ("$#:"::String) = Left "scalar cbits dependency syntax is unsupported"
                         | otherwise = go (c:word) found rest

identifier :: String -> Bool
identifier [] = False
identifier (c:cs) = (isAlpha c || c == '_') && all (\x -> isAlphaNum x || x == '_') cs
quoted :: String -> String -> Maybe String
quoted prefix line | prefix `isPrefixOf` line = case reads (drop (length prefix) line) of [(value,"")] -> Just value; _ -> Nothing
                   | otherwise = Nothing
split :: Char -> String -> [String]
split separator value = case break (== separator) value of (part,[]) -> [part]; (part,_:rest) -> part:split separator rest
member :: Value -> String -> Maybe Value
member (Object fields) key = KM.lookup (Key.fromString key) fields
member _ _ = Nothing
get :: FromJSON a => Value -> String -> IO a
get value key = case member value key of
  Just field -> case fromJSON field of Success result -> pure result; Error err -> fail err
  Nothing -> fail ("scalar cbits: missing " ++ key)
check :: Bool -> String -> IO ()
check yes message = unless yes (fail message)
command :: FilePath -> FilePath -> [String] -> IO String
command = commandWithEnv []
commandWithEnv :: [(String,Maybe String)] -> FilePath -> FilePath -> [String] -> IO String
commandWithEnv overrides directory tool arguments = do
  inherited <- getEnvironment
  let environment = [(key,value) | (key,Just value) <- overrides] ++
        filter (\(key,_) -> key `notElem` map fst overrides) inherited
  (status,out,err) <- readCreateProcessWithExitCode ((proc tool arguments) {cwd=Just directory,env=Just environment}) ""
  check (status == ExitSuccess) ("scalar cbits: " ++ tool ++ " failed: " ++ take 4096 err)
  pure out
resolveTool :: FilePath -> IO FilePath
resolveTool name = do
  path <- if isAbsolute name then pure name else findExecutable name >>= maybe (fail ("required scalar cbits tool missing: " ++ name)) pure
  canonicalizePath path
llvmTool :: String -> String -> IO FilePath
llvmTool variable fallback = lookupEnv variable >>= maybe (resolveTool fallback) resolveTool
observe :: [FilePath] -> IO [(FilePath,String)]
observe paths = mapM (\path -> (,) path <$> digest path) (sort (nub paths))
verify :: [(FilePath,String)] -> IO ()
verify inputs = forM_ inputs $ \(path,hash) -> do
  actual <- digest path
  check (actual == hash) ("scalar cbits input changed during acquisition: " ++ path)
digest :: FilePath -> IO String
digest path = sha <$> BS.readFile path
sha :: BS.ByteString -> String
sha = hex . SHA.hash
hex :: BS.ByteString -> String
hex = concatMap (\byte -> let value=showHex byte "" in if length value==1 then '0':value else value) . BS.unpack
withDirectory :: FilePath -> (FilePath -> IO a) -> IO a
withDirectory parent = bracket create removePathForcibly
  where create = do
          (path,handle) <- openTempFile parent "thc-scalar-"
          hClose handle; removeFile path; createDirectory path
          canonicalizePath path
