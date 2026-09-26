-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module SimdByteArrayFixtures (prepareSimdByteArray) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (FromJSON, Result(..), Value(..), eitherDecodeStrict', fromJSON, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Char (toLower)
import Data.Foldable (toList)
import Data.List (isInfixOf, isPrefixOf, isSuffixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.Word (Word32, Word8)
import Distribution.Simple.Utils (createTempDirectory)
import FixtureSupport (CommandResult(..), hashFile, runLogged, runLoggedExpect, runLoggedWithInput, writeJson, readInteger, splitTab)
import Foreign.Marshal.Alloc (alloca)
import Foreign.Ptr (castPtr)
import Foreign.Storable (peek, poke)
import SimdByteArrayModel
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative, takeExtension, takeDirectory, splitDirectories)
import Text.Read (readMaybe)

get :: String -> Value -> Value
get key (Object fields) = maybe Null id (KM.lookup (Key.fromString key) fields)
get _ _ = Null

items :: Value -> [Value]
items (Array values) = toList values
items _ = []

at :: Int -> Value -> Value
at index value = case drop index (items value) of item:_ -> item; [] -> Null

firstValue :: [Value] -> Value
firstValue (value:_) = value
firstValue [] = Null

string :: Value -> String
string value = case fromJSON value of Success answer -> answer; Error _ -> ""

field :: FromJSON a => String -> Value -> IO a
field key value = case fromJSON (get key value) of Success answer -> pure answer; Error message -> die (key ++ ": " ++ message)

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either (die . ((path ++ ": ") ++)) pure . eitherDecodeStrict'

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

walk :: Value -> [Value]
walk value = value : concatMap walk (case value of Object fields -> KM.elems fields; Array values -> toList values; _ -> [])

expressions :: String -> Value -> [Value]
expressions tag = filter ((== toJSON tag) . at 0) . walk

exact :: String -> [String] -> Value -> Bool
exact kind reps proof = get "kind" proof == toJSON kind && get "primReps" proof == toJSON reps && get "aggregate" proof == Null &&
  case get "evaluated" proof of Bool _ -> True; _ -> False

scalar, state, array :: Value -> Bool
scalar = exact "long" ["IntRep"]
state = exact "void" []
array = exact "object" ["BoxedRep (Just Unlifted)"]

scalarStateTuple :: Value -> Bool
scalarStateTuple proof = get "aggregate" proof == String "unboxed-tuple" && get "kind" proof == String "unknown" &&
  get "primReps" proof == toJSON ["IntRep" :: String] && get "vector" proof == Null &&
  case items (get "components" proof) of [s,x] -> state s && scalar x; _ -> False

operations :: Family -> [String]
operations family = [prefix ++ suffix | prefix <- ["index","read","write"], suffix <- [shape ++ "Array#",lane ++ "ArrayAs" ++ shape ++ "#"]]
  where (shape,lane) = case family of
          Int32Lanes -> ("Int32X4","Int32")
          Word32Lanes -> ("Word32X4","Word32")
          FloatLanes -> ("FloatX4","Float")
          DoubleLanes -> ("DoubleX2","Double")

primitive :: Value -> String
primitive value = if at 0 (at 1 value) == String "prim" then string (at 1 (at 1 value)) else ""

counts :: Ord a => [a] -> Map.Map a Int
counts values = Map.fromListWith (+) [(value,1) | value <- values]

boundary :: String -> String
boundary "pre" = "optimized-Core-before-Tidy"
boundary _ = "optimized-Core-after-Tidy-before-CorePrep"

validateWorker :: Family -> Value -> IO ()
validateWorker family binding = do
  let name = string (get "name" binding)
      expr = get "expr" binding
      formals = items (at 1 expr)
      lambdas = expressions "lam" expr
      result = get "resultRep" (at 3 expr)
      rawIndex = floating family && "IndexWorker" `isSuffixOf` name
      proof i = get "rep" (at i (at 1 expr))
      memory = filter ((`elem` operations family) . primitive) (expressions "app" expr)
  check (length lambdas == (if rawIndex then 2 else 1) && array (proof 0)) (name ++ ": worker array/lambda boundary")
  when rawIndex $ do
    let local = lambdas !! 1
        calls = filter ((== String "lam") . at 0 . at 1) (expressions "app" expr)
    check (length (items (at 1 local)) == 1 && state (get "rep" (at 0 (at 1 local))) && scalar (get "resultRep" (at 3 local)) &&
      length calls == 1 && at 1 (firstValue calls) == local && length (items (at 2 (firstValue calls))) == 1) (name ++ ": scratch runRW call")
  if any (`isSuffixOf` name) ["IndexWorker","IndexGraph"] then
    check (length formals == (if rawIndex then 3 else 2) && all (scalar . get "rep") (drop 1 formals) && scalar result) (name ++ ": index ABI")
  else if "ReadWorker" `isSuffixOf` name then do
    let arity = if floating family then 4 else 3
    check (length formals == arity && all (scalar . get "rep") (take (arity-2) (drop 1 formals)) &&
      state (proof (arity-1)) && scalarStateTuple result) (name ++ ": read ABI")
  else check (length formals == lanes family+3 && all (scalar . get "rep") (take (lanes family+1) (drop 1 formals)) &&
    state (proof (lanes family+2)) && (if "StoreGraph" `isSuffixOf` name then array result else scalarStateTuple result)) (name ++ ": write ABI")
  check (length memory == 1) (name ++ ": exactly one vector memory operation")
  when (floating family && "Graph" `isSuffixOf` name) $ do
    let ps = counts (map (string . at 1) (expressions "prim" expr))
        (forbidden, indexCounts, storeCounts) = if family == FloatLanes then
          (["newByteArray#","readFloatArray#","writeFloatArray#","readWord32Array#","writeWord32Array#"],
           [("timesFloat#",4),("plusFloat#",3),("float2Int#",1)],[("int2Float#",4),("packFloatX4#",1),("unsafeFreezeByteArray#",1)])
          else (["newByteArray#","readDoubleArray#","writeDoubleArray#","readIntArray#","writeIntArray#"],
           [("*##",2),("+##",1),("double2Int#",1)],[("int2Double#",2),("packDoubleX2#",1),("unsafeFreezeByteArray#",1)])
    check (all (`Map.notMember` ps) forbidden) (name ++ ": scratch graph storage")
    check (all (\(p,n) -> Map.lookup p ps == Just n) (if "IndexGraph" `isSuffixOf` name then indexCounts else storeCounts)) (name ++ ": graph lane observation")
  when ("StoreGraph" `isSuffixOf` name) $ do
    let outer = at 2 expr
        afterWrite = at 3 (at 0 (at 3 outer))
        freeze = at 1 afterWrite
        alternative = at 0 (at 3 afterWrite)
    check (at 0 outer == String "case" && at 1 outer == firstValue memory && at 0 afterWrite == String "case" && primitive freeze == "unsafeFreezeByteArray#") (name ++ ": ordered final freeze")
    check (map (take 2 . items) (items (at 2 freeze)) == [[String "var",get "id" (firstValue formals)],[String "var",at 2 outer]]) (name ++ ": write state consumed by freeze")
    check (take 2 (items (at 3 alternative)) == [String "var",at 1 (at 2 alternative)]) (name ++ ": direct frozen return")

guestStructure :: Family -> Entry -> Value -> Value -> IO Value
guestStructure family entry report core = do
  let name = entryName entry
      bindings = Map.fromList [(get "id" binding,binding) | binding <- items (get "bindings" core)]
      rootId = at 0 (get "roots" report)
      reachable = [bindings Map.! get "id" item | item <- items (get "reachableBindings" report)]
      expectedNames = Set.fromList (name : maybe [] pure (helper family name))
  check (Set.fromList (map (string . get "name") reachable) == expectedNames) (name ++ ": global closure")
  facts <- forM reachable $ \binding -> do
    let expr = get "expr" binding
        lambdas = expressions "lam" expr
        references = [at 1 node | node <- expressions "var" expr, Map.member (at 1 node) bindings]
        calls = [node | node <- expressions "app" expr, at 0 (at 1 node) == String "var", Map.member (at 1 (at 1 node)) bindings]
        wrapper = get "id" binding == rootId
        needsCall = wrapper && helper family name /= Nothing
    check (at 0 expr == String "lam") (name ++ ": non-lambda root")
    if wrapper then do
      check (get "arity" binding == toJSON (entryArity entry) && length (items (at 1 expr)) == entryArity entry &&
        all (scalar . get "rep") (items (at 1 expr)) && scalar (get "resultRep" (at 3 expr)) && length lambdas == 2) (name ++ ": scalar wrapper ABI")
      let local = lambdas !! 1
          localCalls = filter ((== String "lam") . at 0 . at 1) (expressions "app" expr)
      check (length (items (at 1 local)) == 1 && state (get "rep" (at 0 (at 1 local))) && scalar (get "resultRep" (at 3 local)) &&
        length localCalls == 1 && at 1 (firstValue localCalls) == local && length (items (at 2 (firstValue localCalls))) == 1) (name ++ ": one runRW call")
    else validateWorker family binding
    check (length references == (if needsCall then 1 else 0) && length calls == length references) (name ++ ": residual helper count")
    forM_ calls $ \call -> do
      let callee = bindings Map.! at 1 (at 1 call)
      arity <- field "arity" callee :: IO Int
      check (Just (string (get "name" callee)) == helper family name && length (items (at 2 call)) == arity &&
        take 3 (drop 3 (items call)) == [toJSON (replicate arity False),Bool False,Bool False]) (name ++ ": saturated unlifted helper")
    check (all ((== 1) . length . items . at 3) (expressions "case" expr)) (name ++ ": conditional guest call path")
    pure (length lambdas,object ["id" .= get "id" binding,"name" .= get "name" binding,"lambdaCount" .= length lambdas])
  let total = sum (map fst facts)
  check (total == guestCalls family name) (name ++ ": actual guest root count")
  pure (object ["guestCalls" .= total,"roots" .= map snd facts])

inventory :: Family -> String -> Value -> IO Value
inventory family stage core = do
  check (get "boundary" core == toJSON (boundary stage)) "Wrong SIMD memory Core stage"
  let bindings = get "bindings" core
      ps = counts (map (string . at 1) (expressions "prim" bindings))
      vectors = filter ((== String "vector") . get "kind") (walk bindings)
      vector = object ["lanes" .= lanes family,"element" .= element family]
      rep = toJSON ["VecRep " ++ show (lanes family) ++ " " ++ element family]
      selected = [binding | binding <- items bindings, string (get "name" binding) `elem`
        (map entryName (entries family) ++ [name | entry <- entries family, Just name <- [helper family (entryName entry)]])]
      -- The unchanged shared auditor proves each recognized immediate read case.
      -- Counting its syntax here does not replace the exact read_case contract.
      readSites = [node | binding <- selected,node <- expressions "case" (get "expr" binding),
        let name = primitive (at 1 node), name `elem` operations family, "read" `isPrefixOf` name]
      literalKind = if family == Int32Lanes then "int32" else "word32"
      literals = filter ((== toJSON literalKind) . at 1) (expressions "lit" bindings)
  check (all (`Map.member` ps) (operations family) && Map.notMember "setByteArray#" ps) "Missing six SIMD memory primitives"
  check (not (null vectors) && all (\p -> get "primReps" p == rep && get "vector" p == vector && get "aggregate" p == Null) vectors) "Inexact vector leaf proof"
  check (length readSites == 6) "Expected four alias and two worker immediate vector reads"
  numbers <- forM literals $ \literal -> maybe (die "Non-integral narrow lane literal") pure (readInteger (string (at 2 literal)))
  check (not (null numbers) && if family == Int32Lanes then all (\x -> -2^(31 :: Int) <= x && x < 2^(31 :: Int)) numbers
         else all (\x -> 0 <= x && x < 2^(32 :: Int)) numbers && any (>= 2^(31 :: Int)) numbers) "Missing/noncanonical narrow lane literals"
  forM_ selected $ \binding -> do
    let name = string (get "name" binding)
        helperNames = [h | entry <- entries family, Just h <- [helper family (entryName entry)]]
        primitiveNames = map (string . at 1) (expressions "prim" (get "expr" binding))
        floatingOps = if family == FloatLanes then ["int2Float#","float2Int#","plusFloat#","timesFloat#"] else ["int2Double#","double2Int#","+##","*##"]
    when (name `elem` helperNames) (validateWorker family binding)
    when (floating family && not ("Graph" `isSuffixOf` name || "GraphIndexCase" `isSuffixOf` name || "GraphStoreCase" `isSuffixOf` name)) $
      check (all (`notElem` primitiveNames) floatingOps) (name ++ ": raw observation gained floating arithmetic")
  floatingFacts <- if floating family then do
    check (all (not . ("cast" `isInfixOf`) . map toLower) (Map.keys ps)) "Scalar bitcasts outside SIMD memory slice"
    let kind = if family == FloatLanes then "float" else "double"
        literalValues = map (string . at 2) (filter ((== toJSON kind) . at 1) (expressions "lit" bindings))
        wanted = if family == FloatLanes then ["3.0","5.0","7.0","11.0"] else ["3.0","5.0"]
    check (counts literalValues == Map.fromList [(x,3) | x <- wanted]) "Finite checksum literal sites changed"
    pure [Key.fromString (kind ++ "LiteralSites") .= length literalValues]
    else pure []
  pure (object (["localReadSites" .= length readSites,Key.fromString (literalKind ++ "LiteralSites") .= length literals,
    "vectorProofs" .= length vectors,"memoryPrimitiveCounts" .= Map.restrictKeys ps (Set.fromList (operations family))] ++ floatingFacts))

frontiers :: [String]
frontiers = ["vectorArgument","readVectorEscape","readTupleEscape"] ++ [f ++ o ++ "Worker" | f <- ["vector","scalar"],o <- ["Read","Write"]]

frontierIssues :: Value -> String -> Map.Map (String,String) Int
frontierIssues capabilities name = counts $ case name of
  "vectorArgument" -> [("vector-boundary",if enabled "arguments" then "vector host argument" else "vector formal argument")]
  "readVectorEscape" -> [("vector-boundary",if enabled "results" then "vector host result" else "vector function result")]
  "readTupleEscape" -> [("aggregate-boundary","unboxed-tuple host result"),("malformed-expression","Invalid local vector memory intrinsic: read requires an immediate exact case")] ++
    [("aggregate-representation","unboxed-tuple: unsupported component") | not (enabled "tuple-fields"), _ <- [1,2 :: Int]]
  _ -> [("aggregate-boundary","unboxed-tuple host result")]
  where enabled capability = toJSON (capability :: String) `elem` items (get "vectorTransport" capabilities)

negative :: Map.Map (String,String) Int -> Value -> IO ()
negative wanted report = check (get "accepted" report == Bool False && items (get "missingGlobals" report) == [] &&
  counts [(string (get "code" issue),string (get "detail" issue)) | issue <- items (get "issues" report)] == wanted) "Changed exact negative issue multiset"

wrongElements :: Family -> [String]
wrongElements Int32Lanes = ["Word32ElemRep"]
wrongElements Word32Lanes = ["Int32ElemRep"]
wrongElements FloatLanes = ["Int32ElemRep","Word32ElemRep","DoubleElemRep"]
wrongElements DoubleLanes = ["Int64ElemRep","Int32ElemRep","Word32ElemRep","FloatElemRep"]

changeField :: String -> (Value -> Value) -> Value -> Value
changeField key action value@(Object fields) = Object (KM.insert (Key.fromString key) (action (get key value)) fields)
changeField _ _ _ = error "Expected mutation object"

changeAt :: Int -> (Value -> Value) -> Value -> Value
changeAt index action value = toJSON [if i == index then action child else child | (i,child) <- zip [0..] (items value)]

mutate :: Family -> String -> String -> String -> Value -> Value
mutate family offsetFamily operation wrong = changeField "bindings" (mapArray binding)
  where
    mapArray action = toJSON . map action . items
    binding value | get "name" value == toJSON (offsetFamily ++ operation ++ "Worker") = changeField "expr" visit value
                  | otherwise = value
    visit value
      | at 0 value == String "app" && primitive value `elem` operations family = case operation of
          "Index" -> changeAt 6 (changeField "rep" proof) value
          "Read" -> changeAt 6 (changeField "rep" (changeField "components" (changeAt 1 proof))) value
          _ -> changeAt 2 (changeAt 2 (\expr -> changeAt (length (items expr)-1) (changeField "rep" proof) expr)) value
      | otherwise = case value of Object fields -> Object (fmap visit fields); Array _ -> mapArray visit value; _ -> value
    width = if wrong `elem` ["DoubleElemRep","Int64ElemRep"] then 2 else 4 :: Int
    proof = changeField "primReps" (const (toJSON ["VecRep " ++ show width ++ " " ++ wrong])) .
      changeField "vector" (const (object ["lanes" .= width,"element" .= wrong]))

record :: FilePath -> FilePath -> IO Value
record root path = do digest <- hashFile (root </> path); pure (object ["path" .= path,"sha256" .= digest])

parseRows :: Family -> String -> IO [(String,[Integer],Integer)]
parseRows family text = do
  parsed <- forM (lines text) $ \line -> case splitTab line of
    name:fields | Just entry <- lookup name [(entryName e,e) | e <- entries family], length fields == entryArity entry+1,
                  Just numbers <- traverse readInteger fields -> pure (name,init numbers,last numbers)
    _ -> die "Malformed SIMD memory corpus row"
  check (Set.size (Set.fromList [(n,a) | (n,a,_) <- parsed]) == length parsed) "Duplicate SIMD memory corpus row"
  pure parsed

type Audit = String -> String -> FilePath -> Bool -> IO (Value,CommandResult,FilePath)

mutationControls :: Family -> FilePath -> FilePath -> Audit -> String -> Value -> IO (Value,[CommandResult],[FilePath])
mutationControls family root attempt audit stage core = do
  controls <- forM (wrongElements family) $ \wrong -> do
    cases <- forM [(f,o) | f <- ["vector","scalar"],o <- ["Index","Read","Write"]] $ \(offsetFamily,operation) -> do
      let label = stage ++ "-wrong-" ++ wrong ++ "-" ++ offsetFamily ++ operation
          path = attempt </> "mutations" </> label ++ ".json"
          changed = mutate family offsetFamily operation wrong core
          detail = case operation of "Index" -> "result representation"; "Read" -> "read result components"; _ -> "argument representation"
          wanted = counts ([("malformed-expression","Invalid local vector memory intrinsic: " ++ detail)] ++
            [(code,message) | operation == "Index",(code,message) <- [("vector-shape","Exact vector primitive argument representation required"),("aggregate-shape","Conflicting or missing logical aggregate representation proofs")]])
      check (changed /= core) "Missing SIMD proof mutation target"
      createDirectoryIfMissing True (root </> attempt </> "mutations")
      writeJson (root </> path) changed
      (report,result,reportPath) <- audit label (offsetFamily ++ operation ++ "Case") path True
      negative wanted report
      pure (offsetFamily ++ operation,object ["origin" .= ("Mutated proof metadata only; never native input" :: String),"report" .= report],result,[path,reportPath])
    pure (wrong,toJSON (Map.fromList [(n,v) | (n,v,_,_) <- cases]),[result | (_,_,result,_) <- cases],concat [paths | (_,_,_,paths) <- cases])
  pure (if floating family then toJSON (Map.fromList [(wrong,value) | (wrong,value,_,_) <- controls]) else case controls of [(_,value,_,_)] -> value; _ -> Null,
        concat [commands | (_,_,commands,_) <- controls],concat [paths | (_,_,_,paths) <- controls])

first4 :: (a,b,c,d) -> a
first4 (value,_,_,_) = value

retainedControls :: Family -> FilePath -> FilePath -> Audit -> IO (Value,[CommandResult],[FilePath],[FilePath])
retainedControls family root attempt audit = do
  let base = "bench/experiments" </> familyName family </> "evidence-x86_64"
      retained = if family == DoubleLanes then base </> "captures/doublex2" else base
      provenance = if not (floating family) then retained </> "native/provenance.json.gz" else retained </> "input-provenance.json.gz"
      logs = attempt </> "commands"
      decompress label path = runLogged 60 root logs label [] "gzip" ["-dc",path]
  provenanceCommand <- decompress "retained-provenance" provenance
  parsed <- either die pure (eitherDecodeStrict' (commandStdout provenanceCommand))
  let source = if not (floating family) then parsed else get "core" parsed
      hashes = Map.fromList [(string (get "path" item),string (get "sha256" item)) | item <- items (get "artifacts" source)]
  when (family `elem` [Int32Lanes,Word32Lanes]) $ do
    let fixture = "compiler/test-fixtures" </> moduleName family ++ ".hs"
        wanted = [string (get "sha256" item) | item <- items (get "sources" source),get "path" item == toJSON fixture]
    current <- BS.readFile (root </> fixture)
    let header = "-- SPDX-FileCopyrightText: 2026 Edward Kmett\n-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause\n\n"
        path = attempt </> "retained-original-source.hs"
    check (header `BS.isPrefixOf` current && length wanted == 1) "Retained fixture source provenance missing"
    BS.writeFile (root </> path) (BS.drop (BS.length header) current)
    digest <- hashFile (root </> path)
    check (wanted == [digest]) "Retained original fixture body changed"
  stages <- forM ["pre","post"] $ \stage -> do
    let compressed = retained </> stage ++ "-core.json.gz"
        path = attempt </> "retained" </> stage ++ ".json"
        originalPath = "build" </> "simd-" ++ familyName family </> stage ++ "-core" </> moduleName family ++ ".json"
    result <- decompress ("retained-" ++ stage) compressed
    createDirectoryIfMissing True (root </> attempt </> "retained")
    BS.writeFile (root </> path) (commandStdout result)
    digest <- hashFile (root </> path)
    check (Map.lookup originalPath hashes == Just digest) "Retained genuine Core artifact hash changed"
    core <- readJson (root </> path)
    _ <- inventory family stage core
    positives <- forM [f ++ o ++ "Case" | f <- ["vector","scalar"],o <- ["Index","Read","Write"]] $ \entry -> do
      triple@(report,_,_) <- audit ("retained-" ++ stage ++ "-" ++ entry) entry path False
      check (get "accepted" report == Bool True && items (get "issues" report) == [] && items (get "missingGlobals" report) == []) "Retained positive rejected"
      pure triple
    (controls,commands,paths) <- mutationControls family root attempt audit ("retained-" ++ stage) core
    pure (stage,controls,result:[r | (_,r,_) <- positives] ++ commands,path:[p | (_,_,p) <- positives] ++ paths,compressed)
  pure (toJSON (Map.fromList [(stage,controls) | (stage,controls,_,_,_) <- stages]),provenanceCommand:concat [commands | (_,_,commands,_,_) <- stages],
    concat [paths | (_,_,_,paths,_) <- stages] ++ [attempt </> "retained-original-source.hs" | family `elem` [Int32Lanes,Word32Lanes]],
    provenance:[path | (_,_,_,_,path) <- stages])

prepareSimdByteArray :: FilePath -> String -> [String] -> IO ()
prepareSimdByteArray root name args = do
  family <- case filter ((== name) . familyName) families of [answer] -> pure answer; _ -> die "Unknown SIMD memory family"
  (exportOnly,ghcOptions) <- options False [] args
  let directory = "build" </> "simd-" ++ name
      provenancePath = directory </> "provenance.json"
      fixture = "compiler/test-fixtures" </> moduleName family ++ ".hs"
      nativeSource = "compiler/test-fixtures" </> moduleName family ++ "Native.hs"
      stages = if exportOnly then ["pre"] else ["pre","post"]
  createDirectoryIfMissing True (root </> directory)
  attempt <- makeRelative root <$> createTempDirectory (root </> directory) "prepare-run-"
  old <- doesFileExist (root </> provenancePath)
  when old $ do
    previous <- readJson (root </> provenancePath)
    -- Preserve the previous receipt and recorded artifacts before canonical
    -- outputs change. This is a copy, never a reseal of prior hashes.
    forM_ (provenancePath : map (string . get "path") (items (get "artifacts" previous))) $ \path -> do
      check ((directory ++ "/") `isPrefixOf` path && not (".." `elem` splitDirectories path)) "Unsafe prior artifact path"
      let saved = root </> attempt </> "previous" </> makeRelative directory path
      createDirectoryIfMissing True (takeDirectory saved)
      copyFile (root </> path) saved
    removeFile (root </> provenancePath)
  forM_ ["oracle.tsv","snan-oracle.tsv","snan-expected.tsv"] $ \file -> do
    exists <- doesFileExist (root </> directory </> file)
    when exists (removeFile (root </> directory </> file))
  let logs = attempt </> "commands"
      run label = runLogged 300 root logs label
      audit label entry path rejected = do
        let reportPath = attempt </> "audits" </> label ++ ".json"
        createDirectoryIfMissing True (root </> attempt </> "audits")
        result <- runLoggedExpect (if rejected then 1 else 0) 120 root logs label [] "python3"
          ["scripts/audit-core.py","--entry",entry,"--output",reportPath,path]
        report <- readJson (root </> reportPath)
        pure (report,result,reportPath)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  check (commandStdout version == "9.14.1\n") "SIMD memory fixtures require GHC 9.14.1"
  ghcInfo <- run "ghc-info" [] ghc ["--info"]
  check (maybe False ((== Just "8") . lookup "target word size") (readMaybe (BSC.unpack (commandStdout ghcInfo)) :: Maybe [(String,String)])) "SIMD memory corpus requires 64-bit GHC"
  little <- alloca $ \pointer -> do poke pointer (1 :: Word32); byte <- peek (castPtr pointer); pure (byte == (1 :: Word8))
  check little "SIMD memory corpus requires little-endian host"
  host <- run "host" [] "uname" ["-n"]
  architecture <- run "architecture" [] "uname" ["-m"]
  system <- run "system" [] "uname" ["-s"]
  compiler <- run "compiler-build" [] "compiler/build.sh" []
  let wanted = rows family
      expectedText = encodeRows wanted
  check (length wanted == expectedRows family && Set.size (Set.fromList [(entry,input) | (entry,input,_) <- wanted]) == length wanted) "Wrong SIMD model domain"
  writeFile (root </> directory </> "expected.tsv") expectedText
  writeFile (root </> directory </> "requests.tsv") (encodeRequests wanted)
  capabilities <- readJson (root </> "scripts/core-capabilities.json")
  prepared <- forM stages $ \stage -> do
    let corePath = directory </> stage ++ "-core" </> moduleName family ++ ".json"
    exists <- doesFileExist (root </> corePath)
    when exists (removeFile (root </> corePath))
    exported <- run (stage ++ "-export") [("THC_CORE_OUT",root </> directory </> stage ++ "-core"),("THC_GHC_OUT",root </> directory </> stage ++ "-ghc"),("THC_SOURCE_NOTES","true")]
      "compiler/export.sh" (ghcOptions ++ [x | exportOnly,x <- ["-fno-code","-fwrite-if-simplified-core"]] ++ ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [fixture])
    core <- readJson (root </> corePath)
    facts <- inventory family stage core
    reports <- forM (map entryName (entries family) ++ graphNames family ++ frontiers) $ \entry -> do
      triple@(report,_,_) <- audit (stage ++ "-" ++ entry) entry corePath (entry `elem` frontiers)
      if entry `elem` frontiers then negative (frontierIssues capabilities entry) report else
        check (get "accepted" report == Bool True && items (get "issues" report) == [] && items (get "missingGlobals" report) == []) (entry ++ ": positive audit")
      pure (entry,triple)
    let reportMap = Map.fromList [(entry,report) | (entry,(report,_,_)) <- reports]
    structures <- forM (entries family) $ \entry -> (,) (entryName entry) <$> guestStructure family entry (reportMap Map.! entryName entry) core
    forM_ (graphNames family) $ \entry -> do
      let bindings = filter ((== toJSON entry) . get "name") (items (get "bindings" core))
      check (length bindings == 1 && length (items (get "reachableBindings" (reportMap Map.! entry))) == 1) (entry ++ ": graph closure")
      let binding = firstValue bindings
      check (get "arity" binding == toJSON (if "Index" `isPrefixOf` drop 6 entry then 2 else lanes family+3) && length (expressions "lam" (get "expr" binding)) == 1) (entry ++ ": graph arity/lambda")
      validateWorker family binding
    controls <- mutationControls family root attempt audit stage core
    let auditPath = directory </> stage ++ "-audit.json"
    writeJson (root </> auditPath) (toJSON reportMap)
    pure (stage,object ([("entries",toJSON (Map.fromList structures))] ++ case facts of Object fields -> KM.toList fields; _ -> []),reportMap,controls,
          exported : [result | (_,(_,result,_)) <- reports],corePath:auditPath:[path | (_,(_,_,path)) <- reports])
  retained <- retainedControls family root attempt audit
  native <- if exportOnly then pure Nothing else do
    let directoryNative = directory </> "native"
        binary = directoryNative </> name ++ "-oracle"
    createDirectoryIfMissing True (root </> directoryNative)
    built <- run "native-build" [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-icompiler/test-fixtures","-odir",root </> directoryNative,"-hidir",root </> directoryNative] ++ ghcOptions ++ [nativeSource,"-o",root </> binary])
    observed <- runLoggedWithInput (directory </> "requests.tsv") 120 root logs "native-oracle" [] (root </> binary) []
    check (commandStdout observed == BSC.pack expectedText) "Native SIMD memory corpus differs from exact ordered byte model"
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
    diagnostic <- if not (floating family) then pure Nothing else do
      let expectedDiagnostic = diagnostics family
      writeFile (root </> directory </> "snan-expected.tsv") (encodeRows expectedDiagnostic)
      writeFile (root </> directory </> "snan-requests.tsv") (encodeRequests expectedDiagnostic)
      observation <- runLoggedWithInput (directory </> "snan-requests.tsv") 120 root logs "snan-oracle" [] (root </> binary) []
      actual <- parseRows family (BSC.unpack (commandStdout observation))
      check ([(n,a) | (n,a,_) <- actual] == [(n,a) | (n,a,_) <- expectedDiagnostic]) "Native signaling-NaN diagnostic keys changed"
      BS.writeFile (root </> directory </> "snan-oracle.tsv") (commandStdout observation)
      let differences = [object ["key" .= (toJSON n:map toJSON a),"expected" .= expectedValue,"actual" .= actualValue]
                        | ((n,a,expectedValue),(_,_,actualValue)) <- zip expectedDiagnostic actual,expectedValue /= actualValue]
      pure (Just (object ["kind" .= ("selected-signaling-NaN/native-only" :: String),"rows" .= length actual,"matches" .= null differences,"differences" .= differences,
        "claim" .= ("Pinned native observations only; no portable scalar copying/boxing or arithmetic NaN promise" :: String)],observation))
    pure (Just (built,observed,diagnostic,binary))
  let controlsCommands = concat [records | (_,_,_,(_,records,_),_,_) <- prepared]
      controlsPaths = concat [paths | (_,_,_,(_,_,paths),_,_) <- prepared]
      (_,retainedCommands,retainedPaths,retainedSources) = retained
      commands = [version,ghcInfo,host,architecture,system,compiler] ++ concat [cs | (_,_,_,_,cs,_) <- prepared] ++ controlsCommands ++ retainedCommands ++
        case native of Nothing -> []; Just (built,observed,diagnostic,_) -> [built,observed] ++ [result | Just (_,result) <- [diagnostic]]
      artifacts = [directory </> "expected.tsv",directory </> "requests.tsv"] ++ concat [paths | (_,_,_,_,_,paths) <- prepared] ++ controlsPaths ++ retainedPaths ++ concatMap commandArtifacts commands ++
        case native of Nothing -> []; Just (_,_,diagnostic,binary) -> [directory </> "oracle.tsv",binary] ++ [directory </> path | Just _ <- [diagnostic],path <- ["snan-expected.tsv","snan-requests.tsv","snan-oracle.tsv"]]
  compilerSources <- map ("compiler/THC" </>) . filter ((== ".hs") . takeExtension) <$> listDirectory (root </> "compiler/THC")
  auditorSources <- map ("scripts" </>) . filter (\path -> "core_" `isPrefixOf` path && takeExtension path == ".py") <$> listDirectory (root </> "scripts")
  sources <- mapM (record root) . sort . Set.toList . Set.fromList $ [fixture,nativeSource,
    "test/haskell-fixtures/SimdByteArrayFixtures.hs","test/haskell-fixtures/SimdByteArrayModel.hs","test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal",
    "scripts/audit-core.py","scripts/core-capabilities.json","src/main/kotlin/thc/runtime/VectorMemoryPrimitives.kt",
    "src/main/resources/thc/scalar-primop-signatures.json","compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
    ["src/main/kotlin/thc/runtime/VectorMemory.kt" | family == DoubleLanes] ++ compilerSources ++ auditorSources ++ retainedSources
  artifactRecords <- mapM (record root) (sort (Set.toList (Set.fromList artifacts)))
  let controlKey = case family of Int32Lanes -> "unsignedNegativeControls"; Word32Lanes -> "signedNegativeControls"; _ -> "familyNegativeControls"
      hasNative = maybe False (const True) native
      trim = takeWhile (/= '\n') . BSC.unpack . commandStdout
      provenance = object ["schema" .= (1 :: Int),"vector" .= name,"stages" .= stages,"modelByteOrder" .= ("little" :: String),
        "nativeByteOrder" .= (if hasNative then Just ("little" :: String) else Nothing),"nativeRows" .= (if hasNative then Just (length wanted) else Nothing),
        "modelRows" .= length wanted,"modelMatched" .= (if hasNative then Just True else Nothing),"entries" .= map entryValue (entries family),"graphEntries" .= graphEntries family,
        "positiveAuditsAccepted" .= True,"audits" .= Map.fromList [(s,reports) | (s,_,reports,_,_,_) <- prepared],"structure" .= Map.fromList [(s,facts) | (s,facts,_,_,_,_) <- prepared],
        "frontiers" .= frontiers,"expectedGuestCallsByEntry" .= Map.fromList [(entryName e,guestCalls family (entryName e)) | e <- entries family],
        "checkedGuestCallsByStage" .= Map.fromList [(s ++ "/" ++ entryName e,guestCalls family (entryName e)) | s <- stages,e <- entries family],
        "expectedGraphGuestCallsByEntry" .= Map.fromList [(n,1 :: Int) | n <- graphNames family],
        "checkedGraphGuestCallsByStage" .= Map.fromList [(s ++ "/" ++ n,1 :: Int) | s <- stages,n <- graphNames family],
        "guestCountPolicy" .= ("Count actual retained outer/runRW/helper lambdas; no settling calls" :: String),
        Key.fromString controlKey .= Map.fromList [(s,control) | (s,_,_,(control,_,_),_,_) <- prepared],
        "retainedControls" .= first4 retained,"nativeDiagnostics" .= (case native of Just (_,_,Just (diagnostic,_),_) -> diagnostic; _ -> Null),
        "commands" .= map commandRecord commands,"sources" .= sources,"artifacts" .= artifactRecords,"attempt" .= attempt,
        "toolchain" .= object ["ghc" .= ghc,"ghcVersion" .= ("9.14.1" :: String),"architecture" .= trim architecture,"machine" .= trim architecture,
          "host" .= trim host,"system" .= trim system,"byteOrder" .= ("little" :: String),"ghcInfo" .= BSC.unpack (commandStdout ghcInfo),"ghcOptions" .= ghcOptions],
        "claim" .= (if hasNative then "Native/model byte agreement and strict Core audits; no JVM graph or performance claim" else "Pre-Tidy Core and byte model only; NO native/post-Tidy validation" :: String),
        "limitations" .= (["Little-endian 64-bit native corpus only; selected inputs, not a complete transitive runtime inventory",
          "No native pointer-identity claim; JVM checks actual returned storage identity", "Bounded lane patterns, not exhaustive vector encodings",
          "Signaling NaNs are native diagnostics, never portable corpus rows; no arithmetic NaN payload promise",
          "Existing Python audit-core.py remains the shared exact Core proof mechanism"] :: [String])]
  writeJson (root </> attempt </> "provenance.json") provenance
  writeJson (root </> provenancePath) provenance
  putStrLn (name ++ ": model=" ++ show (length wanted) ++ ", native=" ++ show hasNative ++ ", stages=" ++ show stages)
  where
    options exported flags [] = pure (exported,reverse flags)
    options _ flags ("--export-only":rest) = options True flags rest
    options exported flags ("--ghc-option":flag:rest) = options exported (flag:flags) rest
    options exported flags (arg:rest) | "--ghc-option=" `isPrefixOf` arg = options exported (drop 13 arg:flags) rest
    options _ _ _ = die "Usage: thc-fixtures FAMILY [--export-only] [--ghc-option=OPTION]"
