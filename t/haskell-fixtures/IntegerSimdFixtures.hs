-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : IntegerSimdFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for integer simd.
module IntegerSimdFixtures (prepareIntegerSimd) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Char (toLower)
import Data.Foldable (toList)
import Data.List (intercalate, isPrefixOf, isSuffixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (arch, os)

data Family = Family { shape :: String, width :: Int, lanes :: Int, signed :: Bool }
families :: [Family]
families = [Family "Int8X16" 8 16 True, Family "Int16X8" 16 8 True,
            Family "Word16X8" 16 8 False, Family "Word32X4" 32 4 False]
modulus :: Family -> Integer
modulus f = 2 ^ width f
laneRep, element :: Family -> String
laneRep f = (if signed f then "Int" else "Word") ++ show (width f) ++ "Rep"
element f = (if signed f then "Int" else "Word") ++ show (width f) ++ "ElemRep"
operations :: Family -> [String]
operations f = ["plusCase","minusCase","timesCase"] ++ ["negateCase" | signed f] ++ ["packCase","broadcastCase"]
entryNames :: Family -> [String]
entryNames f = operations f ++ ["laneCase","scalarHelperCase","tupleHelperCase"]
helper :: String -> String
helper "scalarHelperCase" = "scalarWorker"
helper "tupleHelperCase" = "tupleWorker"
helper _ = ""
arity :: String -> Int
arity "laneCase" = 4
arity _ = 2
guestCalls :: String -> Int
guestCalls name = if null (helper name) then 1 else 2
edges :: Family -> [Integer]
edges f | signed f = [-half,-half+1,-middle,-1,0,1,middle,half-2,half-1]
        | otherwise = [0,1,2,half `div` 2-1,half-1,half,half+1,modulus f-2,modulus f-1]
  where half = modulus f `div` 2; middle = if width f == 8 then 65 else 257
affines :: Family -> ([(Int,Integer,Integer)], [(Int,Integer,Integer)])
affines f = (take (lanes f) left,take (lanes f) right)
  where half = modulus f `div` 2
        left = [(0,1,0),(1,1,0),(0,1,1),(1,1,-1),(0,1,half-1),(1,1,-half),(0,3,7),(1,5,-11),
                (0,7,29),(1,9,-31),(0,11,37),(1,13,-41),(0,15,43),(1,17,-47),(0,19,53),(1,21,-59)]
        right = [(1,1,2),(0,1,-3),(1,7,13),(0,11,-17),(1,1,half),(0,1,1-half),(1,13,19),(0,17,-23),
                 (1,23,61),(0,25,-67),(1,27,71),(0,29,-73),(1,31,79),(0,33,-83),(1,35,89),(0,37,-97)]
narrow :: Family -> Integer -> Integer
narrow f value = let bits = value `mod` modulus f in
  if signed f && bits >= modulus f `div` 2 then bits-modulus f else bits
resultLanes :: Family -> String -> Integer -> Integer -> [Integer]
resultLanes f name a b = map (narrow f) $ case name of
  "plusCase" -> zipWith (+) x y
  "minusCase" -> zipWith (-) x y
  "timesCase" -> zipWith (*) x y
  "negateCase" -> map negate x
  "packCase" -> x
  "broadcastCase" -> replicate (lanes f) (a-b+29)
  _ -> error ("Unknown SIMD operation " ++ name)
  where operand = map (\(source,scale,bias) -> narrow f ([a,b] !! source * scale + bias))
        (left,right) = affines f; x = operand left; y = operand right
answer :: Family -> String -> [Integer] -> Integer
answer f "laneCase" [op,lane,a,b] = resultLanes f (operations f !! fromInteger op) a b !! fromInteger lane
answer f name [a,b] = sum (zipWith (*) weights (resultLanes f operation a b)) + if name == "scalarHelperCase" then 48 else 0
  where weights = [3,5,7,11,13,17,19,23,29,31,37,41,43,47,53,59]
        operation = case name of "scalarHelperCase" -> "plusCase"; "tupleHelperCase" -> "timesCase"; _ -> name
answer _ name _ = error ("Wrong SIMD arity " ++ name)
cases :: Family -> String -> [[Integer]]
cases f "laneCase" = [[toInteger op,toInteger lane,a,b] |
  op <- [0..length (operations f)-1], lane <- [0..lanes f-1], x <- edges f, y <- edges f,
  let (a,b) = if operations f !! op == "broadcastCase" then (x+y-29,y) else
        let (left,right) = affines f
            seed target (source,scale,bias) = (source, (target-bias) * inverse scale `mod` modulus f)
            seeds = Map.fromList [seed x (left !! lane),seed y (right !! lane)]
        in (seeds Map.! 0,seeds Map.! 1)]
  where inverse a = go 1 0 a (modulus f)
        go x _ 1 _ = x `mod` modulus f
        go x y a b = let (q,r) = a `divMod` b in go y (x-q*y) b r
cases f _ = map (\(a,b) -> [a,b]) (Set.toAscList pairs)
  where m = modulus f
        values = Set.toAscList $ Set.fromList $ [-2^(63 :: Int),2^(63 :: Int)-1,-m-1,-m,m-1,m,m+1,0x123456789abcdef,-0x123456789abcdef] ++ edges f ++
          [sign * (2^bit+delta) | bit <- [0..width f-1], delta <- [-1,0,1], sign <- [-1,1]]
        initial = [(a,values !! ((17*i+5) `mod` length values)) | (i,a) <- zip [0..] values]
        pairs = Set.fromList (initial ++ [(b,a) | (a,b) <- initial] ++ [(a,b) | a <- edges f,b <- edges f])

get :: String -> Value -> Value
get key (Object fields) = maybe Null id (KM.lookup (Key.fromString key) fields)
get _ _ = Null
items :: Value -> [Value]
items (Array values) = toList values
items _ = []
at :: Int -> Value -> Value
at i value = case drop i (items value) of x:_ -> x; _ -> Null
str :: Value -> String
str (String value) = Text.unpack value
str _ = ""
walk :: Value -> [Value]
walk value = value : concatMap walk (case value of Object fields -> KM.elems fields; Array values -> toList values; _ -> [])
exprs :: String -> Value -> [Value]
exprs tag = filter ((== toJSON tag) . at 0) . walk
rep :: Value -> Value
rep value = get "rep" (last (Null:items value))
scalar :: String -> Value -> Bool
scalar register proof = get "kind" proof == String "long" && get "primReps" proof == toJSON [register] && get "aggregate" proof == Null
laneTuple :: Family -> Value -> Bool
laneTuple f proof = get "kind" proof == String "unknown" && get "aggregate" proof == String "unboxed-tuple" &&
  get "primReps" proof == toJSON (replicate (lanes f) (laneRep f)) && length components == lanes f && all (scalar (laneRep f)) components
  where components = items (get "components" proof)
ensure :: Bool -> String -> Either String ()
ensure True _ = Right ()
ensure False message = Left message
primitiveCounts :: Family -> String -> Map.Map String Int
primitiveCounts f name = Map.fromList [(prefix ++ shape f ++ "#",count) | (prefix,count) <- counts]
  where operation = case name of "scalarHelperCase" -> "plusCase"; "tupleHelperCase" -> "timesCase"; _ -> name
        counts = case operation of
          "laneCase" -> [("pack",if signed f then 8 else 7),("unpack",length (operations f))] ++
            [(prefix,1) | prefix <- ["plus","minus","times","broadcast"] ++ ["negate" | signed f]]
          "packCase" -> [("pack",1),("unpack",1)]
          "broadcastCase" -> [("broadcast",1),("unpack",1)]
          "negateCase" -> [("pack",1),("negate",1),("unpack",1)]
          _ -> [("pack",2),(take (length operation-4) operation,1),("unpack",1)]
primitives :: Family -> [String]
primitives f = sort [prefix ++ shape f ++ "#" | prefix <- ["pack","unpack","broadcast","plus","minus","times"] ++ ["negate" | signed f]]
guestStructure :: Family -> String -> Value -> Value -> Either String Value
guestStructure f name report modul = do
  rootId <- case roots of [ident] -> Right ident; _ -> Left "Expected one root"
  root <- maybe (Left "Missing root") Right (Map.lookup rootId bindings)
  let reachable = Set.fromList (map (get "id") (items (get "reachableBindings" report)))
      helpers = [b | ident <- Set.toList reachable, Just b <- [Map.lookup ident bindings], get "name" b == toJSON (helper name), not (null (helper name))]
      actual = root:helpers
  ensure (length actual == guestCalls name && reachable == Set.fromList (map (get "id") actual)) "Actual global closure changed"
  forM_ actual $ \binding -> do
    let e = get "expr" binding; formals = items (at 1 e); isRoot = binding == root
        refs = [at 1 v | v <- exprs "var" e, Map.member (at 1 v) bindings]
        calls = [v | v <- exprs "app" e, at 0 (at 1 v) == String "var", Map.member (at 1 (at 1 v)) bindings]
        wantedRefs = [get "id" h | h <- helpers, isRoot]
    ensure (at 0 e == String "lam" && length formals == (if isRoot then arity name else 2) && all (scalar "IntRep" . get "rep") formals) "Machine Int formal boundary changed"
    ensure (length (exprs "lam" e) == 1) "Extra guest lambda"
    ensure ((if get "name" binding == String "tupleWorker" then laneTuple f else scalar "IntRep") (get "resultRep" (last (Null:items e)))) "Result boundary changed"
    ensure (refs == wantedRefs && length calls == length wantedRefs) "Guest call references changed"
    forM_ calls $ \call -> ensure (length (items (at 2 call)) == 2 && take 3 (drop 3 (items call)) == [toJSON [False,False],Bool False,Bool False] &&
      map (take 2 . items) (items (at 2 call)) == [[String "var",get "id" formal] | formal <- formals]) "Helper call saturation/arguments changed"
    unless (null helpers) $ ensure (all ((== 1) . length . items . at 3) (exprs "case" e)) "Conditional helper path"
  let counts = Map.fromList [(str (get "name" p),length (items (get "uses" p))) | p <- items (get "primitives" report), str (get "name" p) `elem` primitives f]
  ensure (counts == primitiveCounts f name) "Local vector operation counts changed"
  pure $ object ["guestCalls" .= length actual,"roots" .= [object ["id" .= get "id" b,"name" .= get "name" b] | b <- actual],"vectorPrimitiveCounts" .= counts]
  where bindings = Map.fromList [(get "id" b,b) | b <- items (get "bindings" modul)]; roots = items (get "roots" report)
inventory :: Family -> String -> Value -> Either String Value
inventory f stage modul = do
  ensure (get "boundary" modul == toJSON (if stage == "pre" then "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep" :: String)) "Core boundary changed"
  ensure (not (null vectors) && all vector vectors) "Inexact vector representation"
  ensure (Set.fromList (map (at 1 . at 1) calls) == Set.fromList (map toJSON (primitives f))) "Vector operation disappeared"
  ensure (all (\call -> length (items (at 2 call)) == 1 && laneTuple f (rep (at 0 (at 2 call)))) packs) "Pack requires one exact logical lane tuple"
  ensure (all (laneTuple f . rep) unpacks) "Unpack lane tuple changed"
  when (width f /= 16 || not (signed f)) $ ensure (not (null literals) && all validLiteral literals) "Missing/noncanonical narrow literals"
  ensure (case frontiers of
    [frontier] -> at 0 frontier == String "lam" && length (items (at 1 frontier)) == 1 && get "kind" (get "rep" (at 0 (at 1 frontier))) == String "vector"
    _ -> False) "Missing exact vector argument control"
  pure $ object $ ["vectorProofs" .= length vectors,"packSites" .= length packs,"unpackSites" .= length unpacks,"primitives" .= primitives f] ++
    [Key.fromString (map toLower (take (length (laneRep f)-3) (laneRep f)) ++ "LiteralSites") .= length literals | width f /= 16 || not (signed f)]
  where vectors = filter ((== String "vector") . get "kind") (walk modul)
        vector p = get "vector" p == object ["lanes" .= lanes f,"element" .= element f] && get "primReps" p == toJSON ["VecRep " ++ show (lanes f) ++ " " ++ element f] && get "aggregate" p == Null
        calls = filter ((`elem` map toJSON (primitives f)) . at 1 . at 1) (exprs "app" (get "bindings" modul))
        packs = filter ((== toJSON ("pack" ++ shape f ++ "#")) . at 1 . at 1) calls
        unpacks = filter ((== toJSON ("unpack" ++ shape f ++ "#")) . at 1 . at 1) calls
        literals = filter ((== toJSON (map toLower (take (length (laneRep f)-3) (laneRep f)))) . at 1) (exprs "lit" (get "bindings" modul))
        validLiteral v = case readInteger (str (at 2 v)) of Just n -> narrow f n == n; _ -> False
        frontiers = [get "expr" b | b <- items (get "bindings" modul),get "name" b == String "vectorArgument"]

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'
checked :: Either String a -> IO a
checked = either die pure

set :: String -> Value -> Value -> Value
set key value (Object fields) = Object (KM.insert (Key.fromString key) value fields)
set _ _ value = value
index :: Int -> (Value -> Value) -> Value -> Value
index i change value = toJSON [if n == i then change x else x | (n,x) <- zip [0..] (items value)]
lastItem :: (Value -> Value) -> Value -> Value
lastItem change value = index (length (items value)-1) change value
mapField :: String -> (Value -> Value) -> Value -> Value
mapField key change value = set key (change (get key value)) value
mapBinding :: String -> (Value -> Value) -> Value -> Value
mapBinding name change = mapField "bindings" (toJSON . map (\b -> if get "name" b == toJSON name then change b else b) . items)
firstExpression :: String -> (Value -> Value) -> Value -> Value
firstExpression prim change = snd . go
  where go value | at 0 value == String "app" && at 1 (at 1 value) == toJSON prim = (True,change value)
        go (Array values) = let (found,changed) = foldl step (False,[]) (toList values) in (found,toJSON changed)
        go (Object fields) = let (found,changed) = foldl fieldStep (False,[]) (KM.toList fields) in (found,Object (KM.fromList changed))
        go value = (False,value)
        step (done,values) value = let (found,changed) = if done then (False,value) else go value in (done || found,values ++ [changed])
        fieldStep (done,values) (key,value) = let (found,changed) = if done then (False,value) else go value in (done || found,values ++ [(key,changed)])

-- Exercise the same producer predicates with corrupted copies of genuine Core.
-- No mutation is written back to the original export or used as a native input.
proofControls :: Family -> Value -> Map.Map String Value -> IO ()
proofControls f modul reports = do
  forM_ ["plusCase","scalarHelperCase","tupleHelperCase"] $ \name -> do
    let report = reports Map.! name
        rootExpr change = mapBinding name (mapField "expr" change) modul
        reject m r = case guestStructure f name r m of Left _ -> pure (); Right _ -> die (name ++ ": negative guest structure accepted")
    checked (guestStructure f name report modul) >> pure ()
    reject (rootExpr (index 1 (index 0 (mapField "rep" (set "primReps" (toJSON [laneRep f])))))) report
    reject (rootExpr (index 2 (\e -> toJSON [String "lam",toJSON ([] :: [Value]),e]))) report
    reject (mapField "bindings" (toJSON . (++ [object ["id" .= ("extra" :: String),"name" .= ("extra" :: String)]]) . items) modul)
      (mapField "reachableBindings" (toJSON . (++ [object ["id" .= ("extra" :: String)]]) . items) report)
    reject modul (mapField "primitives" (toJSON . map (mapField "uses" (toJSON . drop 1 . items)) . items) report)
    when (guestCalls name == 2) $ do
      let callChange change = rootExpr (mapCalls change)
          mapCalls change value | at 0 value == String "app" && at 0 (at 1 value) == String "var" = change value
          mapCalls change (Array values) = toJSON (map (mapCalls change) (toList values))
          mapCalls change (Object fields) = Object (fmap (mapCalls change) fields)
          mapCalls _ value = value
          conditional value | at 0 value == String "case" = index 3 (toJSON . (\xs -> xs ++ take 1 xs) . items) value
          conditional (Array values) = toJSON (map conditional (toList values))
          conditional (Object fields) = Object (fmap conditional fields)
          conditional value = value
      reject (callChange (index 2 (toJSON . drop 1 . items))) report
      reject (callChange (index 2 (index 0 (const (toJSON [String "lit",String "int",String "0"]))))) report
      reject (rootExpr conditional) report
      let wrongResult = object ["kind" .= ("vector" :: String)]
      reject (mapBinding (helper name) (mapField "expr" (lastItem (set "resultRep" wrongResult))) modul) report
  let good = object ["kind" .= ("unknown" :: String),"aggregate" .= ("unboxed-tuple" :: String),
        "primReps" .= replicate (lanes f) (laneRep f),"components" .= replicate (lanes f) (object ["kind" .= ("long" :: String),"primReps" .= [laneRep f]])]
      rejectTuple value = unless (not (laneTuple f value)) (die "Negative lane tuple accepted")
  unless (laneTuple f good) (die "Positive lane tuple rejected")
  forM_ [0..lanes f-1] $ \lane -> forM_ (filter (/= laneRep f) ["Int8Rep","Int16Rep","Int32Rep","Word8Rep","Word16Rep","Word32Rep","IntRep","FloatRep"]) $ \wrong ->
    rejectTuple (mapField "components" (index lane (set "primReps" (toJSON [wrong]))) good)
  forM_ [mapField "components" (toJSON . drop 1 . items) good,
         mapField "components" (toJSON . (\xs -> xs ++ take 1 xs) . items) good,
         set "primReps" (toJSON ([] :: [String])) good,set "kind" (String "object") good,set "aggregate" Null good] rejectTuple

signedControl :: Family -> String -> Value -> Value
signedControl f variant = mapBinding "plusCase" (mapField "expr" (firstExpression primitive (index 2 (index 0 (lastItem (mapField "rep" mutate))))))
  where primitive = (if variant == "signedLaneTuple" then "pack" else "plus") ++ shape f ++ "#"
        signedLane = "Int" ++ show (width f) ++ "Rep"
        signedElement = "Int" ++ show (width f) ++ "ElemRep"
        mutate p | variant == "signedLaneTuple" = set "primReps" (toJSON (replicate (lanes f) signedLane)) $
                     mapField "components" (toJSON . map (set "primReps" (toJSON [signedLane])) . items) p
                 | otherwise = set "primReps" (toJSON ["VecRep " ++ show (lanes f) ++ " " ++ signedElement]) $
                     mapField "vector" (set "element" (toJSON signedElement)) p

prepareIntegerSimd :: FilePath -> String -> [String] -> IO ()
prepareIntegerSimd root family options = do
  f <- case filter ((== family) . map toLower . shape) families of [value] -> pure value; _ -> die "Unknown integer SIMD family"
  unless (options == [] || options == ["--export-only"]) $ die "Expected optional --export-only"
  let exportOnly = options == ["--export-only"]
      out = "build/simd-" ++ family; logs = out </> "commands"; fixture = "t/fixtures/compiler/Simd" ++ shape f ++ ".hs"
      nativeSource = "t/fixtures/compiler/Simd" ++ shape f ++ "Native.hs"
      stages = if exportOnly then ["pre"] else ["pre","post"]
      entries = [object ["name" .= name,"arity" .= arity name,"cases" .= cases f name] | name <- entryNames f]
      rows = [(name,args,answer f name args) | name <- entryNames f,args <- cases f name]
      table = concat [intercalate "\t" (name:map show (args ++ [result])) ++ "\n" | (name,args,result) <- rows]
      requests = concat [intercalate "\t" (name:map show args) ++ "\n" | (name,args,_) <- rows]
      runStep label env program args = runLogged 600 root logs label env program args
      record path = do digest <- hashFile (root </> path); pure $ object ["path" .= path,"sha256" .= digest]
  createDirectoryIfMissing True (root </> out)
  forM_ ["oracle.tsv","provenance.json"] $ \name -> do
    exists <- doesFileExist (root </> out </> name); when exists (removeFile (root </> out </> name))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  python <- maybe "python3" id <$> lookupEnv "PYTHON"
  version <- runStep "ghc-version" [] ghc ["--numeric-version"]
  unless (BSC.unpack (commandStdout version) == "9.14.1\n") $ die "Requires GHC 9.14.1"
  info <- runStep "ghc-info" [] ghc ["--info"]
  build <- runStep "plugin-build" [] "bin/build-compiler.sh" []
  writeFile (root </> out </> "expected.tsv") table
  writeFile (root </> out </> "requests.tsv") requests
  stageResults <- forM stages $ \stage -> do
    let path = out </> stage ++ "-core/Simd" ++ shape f ++ ".json"
        env = [("THC_CORE_OUT",root </> out </> stage ++ "-core"),("THC_GHC_OUT",root </> out </> stage ++ "-ghc"),("THC_SOURCE_NOTES","true")]
    exists <- doesFileExist (root </> path); when exists (removeFile (root </> path))
    exported <- runStep (stage ++ "-export") env "bin/export-core.sh" $
      (if exportOnly then ["-fno-code","-fwrite-if-simplified-core"] else []) ++
      ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [fixture]
    modul <- readJson (root </> path)
    structure <- checked (inventory f stage modul)
    audited <- forM (entryNames f ++ ["vectorArgument"]) $ \name -> do
      let reportPath = out </> stage ++ "-" ++ name ++ "-audit.json"
      command <- runLoggedExpect 0 120 root logs (stage ++ "-audit-" ++ name) [] python
        ["bin/audit-core.py","--entry",name,"--output",reportPath,path]
      report <- readJson (root </> reportPath)
      unless (get "missingGlobals" report == toJSON ([] :: [Value])) $ die (name ++ ": missing globals")
      unless (get "accepted" report == Bool True && null (items (get "issues" report))) $ die "Strict positive audit failed"
      pure (name,report,command,reportPath)
    let reports = Map.fromList [(name,report) | (name,report,_,_) <- audited]
    proofs <- forM (entryNames f) $ \name -> do p <- checked (guestStructure f name (reports Map.! name) modul); pure (name,p)
    proofControls f modul reports
    negative <- if signed f then pure [] else forM ["signedLaneTuple","signedVectorOperand"] $ \variant -> do
      let changed = signedControl f variant modul
          inputPath = out </> stage ++ "-MUTATED-" ++ variant ++ ".json"
          reportPath = out </> stage ++ "-MUTATED-" ++ variant ++ "-audit.json"
          argument = ("vector-shape","Exact vector primitive argument representation required")
          aggregate = ("aggregate-shape","Conflicting or missing logical aggregate representation proofs")
          expected = Map.fromList $ if variant == "signedLaneTuple" then
            [(argument,1),(aggregate,lanes f+1),(("scalar-representation","Conflicting exact scalar primitive representations"),lanes f)] else
            [(argument,1),(aggregate,2),(("vector-shape","Exact vector primitive result representation required"),1)]
      unless (changed /= modul) $ die "Signed metadata control did not mutate"
      writeJson (root </> inputPath) changed
      command <- runLoggedExpect 1 120 root logs (stage ++ "-" ++ variant) [] python
        ["bin/audit-core.py","--entry","plusCase","--output",reportPath,inputPath]
      report <- readJson (root </> reportPath)
      let actual = Map.fromListWith (+) [((str (get "code" i),str (get "detail" i)),1 :: Int) | i <- items (get "issues" report)]
      unless (get "accepted" report == Bool False && null (items (get "missingGlobals" report)) && actual == expected) $ die (variant ++ ": exact signedness errors changed: " ++ show actual)
      pure (variant,object ["origin" .= ("Deliberately mutated metadata; not original GHC Core or native oracle input" :: String),"root" .= ("plusCase" :: String),"report" .= report],command,[inputPath,reportPath])
    let auditPath = out </> stage ++ "-audit.json"
        commands = exported:[command | (_,_,command,_) <- audited] ++ [command | (_,_,command,_) <- negative]
    writeJson (root </> auditPath) (toJSON reports)
    pure (stage,toJSON reports,set "entries" (toJSON (Map.fromList proofs)) structure,
      toJSON (Map.fromList [(name,value) | (name,value,_,_) <- negative]), commands,
      [path,auditPath] ++ [p | (_,_,_,p) <- audited] ++ concat [ps | (_,_,_,ps) <- negative])
  native <- if exportOnly then pure ([],[]) else do
    let directory = out </> "native"; binary = directory </> family ++ "-oracle"
    createDirectoryIfMissing True (root </> directory)
    built <- runStep "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-it/fixtures/compiler","-odir",directory,"-hidir",directory,"-o",binary,nativeSource]
    observed <- runLoggedWithInput (out </> "requests.tsv") 60 root logs "native-oracle" [] (root </> binary) []
    -- Exact text includes ordered keys, uniqueness, complete coverage and values.
    unless (commandStdout observed == BSC.pack table) $ die "Native/integer model mismatch"
    BS.writeFile (root </> out </> "oracle.tsv") (commandStdout observed)
    pure ([built,observed],[out </> "oracle.tsv",binary])
  scripts <- sort . filter (\p -> "core_" `isPrefixOf` p && ".py" `isSuffixOf` p) <$> listDirectory (root </> "bin")
  compiler <- sort . filter (".hs" `isSuffixOf`) <$> listDirectory (root </> "src/compiler/THC")
  let commands = [version,info,build] ++ concat [cs | (_,_,_,_,cs,_) <- stageResults] ++ fst native
      sources = [fixture,nativeSource,"t/haskell-fixtures/IntegerSimdFixtures.hs","t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs","thc.cabal",
        "src/test/java/thc/runtime/IntegerSimdModelTest.java","src/test/java/thc/runtime/IntegerSimdModel.java",
        "bin/audit-core.py","bin/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
        map ("bin" </>) scripts ++ map ("src/compiler/THC" </>) compiler ++ map ("bin" </>) ["build-compiler.sh","export-core.sh","toolchain.sh"]
      artifacts = [out </> "expected.tsv",out </> "requests.tsv"] ++ concat [ps | (_,_,_,_,_,ps) <- stageResults] ++ snd native ++ concatMap commandArtifacts commands
  sourceRecords <- mapM record sources
  artifactRecords <- mapM record artifacts
  writeJson (root </> out </> "provenance.json") $ object
    ["schema" .= (1 :: Int),"vector" .= family,"stages" .= stages,"nativeRows" .= (if exportOnly then Nothing else Just (length rows)),
     "modelRows" .= length rows,"modelMatched" .= (if exportOnly then Nothing else Just True),"entries" .= entries,
     "positiveAuditsAccepted" .= True,"audits" .= Map.fromList [(s,a) | (s,a,_,_,_,_) <- stageResults],
     "structure" .= Map.fromList [(s,a) | (s,_,a,_,_,_) <- stageResults],
     "signedUnsignedNegativeControls" .= Map.fromList [(s,a) | (s,_,_,a,_,_) <- stageResults],
     "expectedGuestCallsByEntry" .= Map.fromList [(name,guestCalls name) | name <- entryNames f],
     "checkedGuestCallsByStage" .= Map.fromList [(stage ++ "/" ++ name,guestCalls name) | stage <- stages,name <- entryNames f],
     "proofNegativeControlsPassed" .= True,
     "hostEntries" .= [object ["name" .= ("vectorArgument" :: String),"arity" .= (1 :: Int)]],
     "guestCountPolicy" .= ("Same exact per-call guest-entry count with Truffle inlining enabled or disabled; no host bridge." :: String),
     "sources" .= sourceRecords,"artifacts" .= artifactRecords,"commands" .= map commandRecord commands,
     "toolchain" .= object ["ghcVersion" .= ("9.14.1" :: String),"machine" .= arch,"system" .= os,"ghcInfo" .= BSC.unpack (commandStdout info)],
     "claim" .= (if exportOnly then "Pre-Tidy Core and independent integer model only; NO native/post-Tidy validation." else "Native/model comparison, exact Core metadata and strict static audits; no JVM or hardware SIMD claim." :: String)]
  putStrLn (family ++ ": stages=" ++ show stages ++ ", model rows=" ++ show (length rows) ++ ", native=" ++ show (not exportOnly))
