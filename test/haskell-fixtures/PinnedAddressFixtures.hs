-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module PinnedAddressFixtures (preparePinnedAddresses) where

import Control.Monad ((>=>), forM, forM_, unless, when)
import Data.Aeson (FromJSON, Result(..), Value(..), eitherDecodeStrict', fromJSON, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.&.), shiftR, xor)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (intercalate, isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.String (fromString)
import Data.Word (Word8, Word16)
import FixtureSupport (CommandResult(..), hashFile, hashes, run, runLogged, runLoggedWithInput, writeJson)
import Foreign (Ptr, alloca, castPtr, peek, poke)
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode)
import System.Timeout (timeout)
import Text.Read (readMaybe)

directory, prefix :: String
directory = "build/pinned-addresses"
prefix = "main:PinnedAddressAudit."

entries, frontiers, guestCalls :: [(String,Int)]
entries = [("pinnedBytes",3),("alignedBytes",4),("keepAliveWord8",1),("keepAliveLazy",1),("fingerprintByte",3)]
frontiers = [("publicFingerprintByte",3),("publicFingerprintRoundtrip",3)]
guestCalls = [("pinnedBytes",4),("alignedBytes",4),("keepAliveWord8",3),("keepAliveLazy",3),("fingerprintByte",3)]

values, words64, sizes :: [Integer]
values = [-2^(63 :: Int),-257,-256,-1,0,1,127,128,255,256,257,0x0123456789abcdef,2^(63 :: Int)-1]
words64 = [-2^(63 :: Int),-1,0,1,0x0123456789abcdef,0x7f0080ff0102fe03,2^(63 :: Int)-1]
sizes = [0,1,2,3,8,16,17,31,64]

requests :: [(String,[Integer])]
requests = concat [[("pinnedBytes",[size,offset,raw])] ++ [("alignedBytes",[size,alignment,offset,raw]) | alignment <- [8,16]] |
  size <- sizes, offset <- if size == 0 then [0] else [0..size-1], raw <- values] ++
  [(name,[raw]) | name <- ["keepAliveWord8","keepAliveLazy"], raw <- values] ++
  concat [concat [[(name,[high,low,index]) | name <- ["fingerprintByte","publicFingerprintByte"]] | index <- [0..15]] ++
    [("publicFingerprintRoundtrip",[high,low,index]) | index <- [0,1]] | high <- words64, low <- words64]

-- Native-domain model only: explicit byte storage and mutation, independently
-- checked by the JVM's arithmetic and big-endian byte-buffer model.
expected :: String -> [Integer] -> Integer
expected name arguments = case (name,arguments) of
  ("pinnedBytes",[size,offset,raw]) -> allocation size offset raw
  ("alignedBytes",[size,_,offset,raw]) -> allocation size offset raw
  ("keepAliveWord8",[raw]) -> keep raw 7
  ("keepAliveLazy",[raw]) -> keep raw 11
  ("publicFingerprintRoundtrip",[high,low,index]) -> if index == 0 then high else low
  (_,[high,low,index]) | name `elem` ["fingerprintByte","publicFingerprintByte"] ->
    (if index < 8 then high else low) `shiftR` (8 * fromInteger (7-index `mod` 8)) .&. 255
  _ -> error "Invalid pinned-address model request"
  where
    keep raw delta = let before = raw .&. 255 in before*257 + ((before+delta) .&. 255)
    allocation 0 _ _ = 0
    allocation size offset raw =
      let bytes = Map.insert offset (raw .&. 255) (Map.fromList [(0,11),(size-1,13)])
          before = bytes Map.! offset
          after = before `xor` 128
          changed = Map.insert offset after bytes
      in size*19 + before*257 + after*65537 + changed Map.! 0*17 + changed Map.! (size-1)*23

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

ensure :: Bool -> String -> Either String ()
ensure condition message = if condition then Right () else Left message

one :: String -> [a] -> Either String a
one _ [value] = Right value
one message _ = Left message

decode :: FromJSON a => Value -> Either String a
decode value = case fromJSON value of Success result -> Right result; Error message -> Left message

field :: FromJSON a => String -> Value -> Either String a
field key (Object fields) = maybe (Left ("Missing field: " ++ key)) decode (KeyMap.lookup (fromString key) fields)
field key _ = Left ("Expected object: " ++ key)

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

-- Paths identify concrete lambda sites, including duplicate-looking lambdas.
-- Structural equality is deliberately not a substitute for site identity.
data Step = Index Int | Key String deriving (Eq,Ord,Show)
type Path = [Step]

children :: Value -> [(Step,Value)]
children (Array items) = zipWith (\i value -> (Index i,value)) [0..] (toList items)
children (Object fields) = [(Key (Key.toString key),value) | (key,value) <- KeyMap.toAscList fields]
children _ = []

walk :: Value -> [(Path,Value)]
walk value = ([],value) : [(step:path,child) | (step,next) <- children value, (path,child) <- walk next]

at :: Path -> Value -> Either String Value
at [] value = Right value
at (step:path) value = maybe (Left ("Missing path: " ++ show step)) (at path) (lookup step (children value))

replace :: Path -> Value -> Value -> Either String Value
replace [] replacement _ = Right replacement
replace (step:path) replacement value = do
  child <- at [step] value >>= replace path replacement
  case (step,value) of
    (Key key,Object fields) -> pure (Object (KeyMap.insert (fromString key) child fields))
    (Index index,Array items) -> pure (toJSON [if i == index then child else item | (i,item) <- zip [0..] (toList items)])
    _ -> Left "Invalid replacement path"

array :: Value -> [Value]
array (Array items) = toList items
array _ = []

tagged :: String -> Value -> Bool
tagged tag value = take 1 (array value) == [String (fromString tag)]

proofPath :: Value -> Either String Path
proofPath value = do
  ensure (not (null (array value))) "Missing expression representation"
  let path = [Index (length (array value)-1),Key "rep"]
  proof <- at path value
  _ <- field "kind" proof :: Either String String
  pure path

rawProof :: Value -> Either String Value
rawProof value = proofPath value >>= (`at` value)

bindings :: Value -> Either String [(Path,Value)]
bindings modules = concat <$> forM (zip [0..] (array modules)) (\(i,modul) -> do
  bs <- field "bindings" modul :: Either String [Value]
  pure [([Index i,Key "bindings",Index j],binding) | (j,binding) <- zip [0..] bs])

applications :: Value -> String -> Maybe (Set.Set String) -> Either String [(Path,Value)]
applications modules primitive reachable = do
  bs <- bindings modules
  concat <$> forM bs (\(path,binding) -> do
    ident <- field "id" binding
    expression <- field "expr" binding
    pure [(path ++ [Key "expr"] ++ nested,node) | maybe True (Set.member ident) reachable,
      (nested,node) <- walk expression, tagged "app" node,
      take 2 (array (array node !! 1)) == [String "prim",String (fromString primitive)]])

voidRep :: Value
voidRep = object ["primReps" .= ([] :: [String]),"kind" .= ("void" :: String),"evaluated" .= True]

guestStructure :: String -> Value -> Value -> Either String Value
guestStructure entry report modules = do
  bs <- bindings modules
  indexed <- Map.fromList <$> forM bs (\pair@(_,binding) -> do ident <- field "id" binding; pure (ident :: String,pair))
  reached <- field "reachableBindings" report >>= mapM (field "id")
  reachable <- forM reached (\ident -> maybe (Left "Missing reachable binding") Right (Map.lookup ident indexed))
  names <- mapM (field "name" . snd) reachable
  let allocation = entry `elem` ["pinnedBytes","alignedBytes"]
      wanted = entry : ["addressBytes" | allocation] ++ ["keptBottom" | entry == "keepAliveLazy"]
  ensure (Set.fromList names == Set.fromList wanted) "Global closure changed"
  roots <- field "roots" report :: Either String [String]
  rootId <- one "Expected one root" roots
  (bindingPath,binding) <- maybe (Left "Missing root") Right (Map.lookup rootId indexed)
  root <- field "expr" binding
  formals <- at [Index 1] root >>= decode :: Either String [Value]
  ensure (tagged "lam" root && Just (length formals) == lookup entry entries) "Host arity changed"
  forM_ formals $ \formal -> do
    kind <- at [Key "rep",Key "kind"] formal
    reps <- at [Key "rep",Key "primReps"] formal
    lifted <- field "lifted" formal
    coercion <- field "coercion" formal
    ensure (kind == String "long" && reps == toJSON ["IntRep" :: String] && not lifted && not coercion) "Wrong host formal"
  call <- at [Index 2] root
  immediate <- at [Index 1] call
  stateFormals <- at [Index 1] immediate >>= decode :: Either String [Value]
  ensure (tagged "app" call && tagged "lam" immediate) "Missing immediate State lambda"
  state <- one "Expected one State formal" stateFormals
  stateProof <- field "rep" state
  stateType <- field "type" state :: Either String String
  lifted <- field "lifted" state
  coercion <- field "coercion" state
  arguments <- at [Index 2] call >>= decode :: Either String [Value]
  argument <- one "Expected one State argument" arguments
  flags <- at [Index 3] call
  ensure (stateProof == voidRep && stateType == "State# RealWorld" && not lifted && not coercion &&
    tagged "void" argument && flags == toJSON [False]) "State call shape changed"
  argumentProof <- rawProof argument
  ensure (argumentProof == voidRep) "State argument proof changed"
  sites <- forM reachable $ \(path,global) -> do
    name <- field "name" global :: Either String String
    expr <- field "expr" global
    ident <- field "id" global :: Either String String
    if name == "keptBottom" then do
      ensure (take 2 (array expr) == [String "var",String (fromString ident)]) "Bottom no longer retained self-reference"
      pure ([],0)
    else do
      joins <- concat <$> forM [(nested,node) | (nested,node) <- walk expr, tagged "let" node] (\(nested,node) -> do
        locals <- at [Index 2] node >>= decode :: Either String [Value]
        concat <$> forM (zip [0..] locals) (\(index,local) -> case field "joinValueArity" local :: Either String Int of
          Right arity | arity > 0 -> do
            rhs <- field "expr" local
            proof <- field "joinResultRep" local
            result <- at [Index (length (array rhs)-1),Key "resultRep"] rhs
            kind <- field "kind" proof :: Either String String
            reps <- field "primReps" proof :: Either String [String]
            ensure (tagged "lam" rhs && length (array (array rhs !! 1)) == arity && proof == result &&
              kind == "long" && reps == ["IntRep"]) "Incomplete join prefix"
            pure [nested ++ [Index 2,Index index,Key "expr"]]
          _ -> pure []))
      pure ([(path ++ [Key "expr"] ++ nested,node) | (nested,node) <- walk expr, tagged "lam" node, nested `notElem` joins],length joins)
  keep <- applications modules "keepAlive#" (Just (Set.fromList reached))
  (keepPath,keepApp) <- one "Expected one keepAlive call" keep
  let continuationPath = [Index 2,Index 2]
  continuation <- at continuationPath keepApp
  continuationFormals <- at [Index 1] continuation >>= decode :: Either String [Value]
  ensure (tagged "lam" continuation) "Continuation must be a lambda"
  continuationFormal <- one "Continuation arity changed" continuationFormals
  continuationRep <- field "rep" continuationFormal
  continuationLifted <- field "lifted" continuationFormal
  keepRep <- rawProof keepApp
  result <- at [Index (length (array continuation)-1),Key "resultRep"] continuation
  ensure (continuationRep == voidRep && not continuationLifted && keepRep == result) "Continuation/result proof changed"
  let lambdas = concatMap fst sites
      rootPath = bindingPath ++ [Key "expr"]
      allowed = [rootPath,rootPath ++ [Index 2,Index 1],keepPath ++ continuationPath] ++
        [path ++ [Key "expr"] | ((path,_),name) <- zip reachable names, name == "addressBytes"]
  ensure (Just (length lambdas) == lookup entry guestCalls && Set.fromList (map fst lambdas) == Set.fromList allowed) "Hidden guest lambda or wrong count"
  formalNames <- forM lambdas (\(_,lambda) -> at [Index 1] lambda >>= decode >>= mapM (field "name") :: Either String [String])
  pure $ object ["guestCalls" .= length lambdas,"localJoinPrefixes" .= sum (map snd sites),"lambdaFormals" .= formalNames,
    "lazyUncalledGlobal" .= (if entry == "keepAliveLazy" then Just ("keptBottom" :: String) else Nothing)]

-- Each forged certificate is applied to an accepted genuine export, not to an
-- already unsupported synthetic module. Result tuples retain their State slot.
negatives :: [(String,String,String,Maybe Int,String,String)]
negatives =
  [("read-word-not-word8","keepAliveWord8","readWord8OffAddr#",Nothing,"long","WordRep"),
   ("write-word-not-word8","keepAliveWord8","writeWord8OffAddr#",Just 2,"long","WordRep"),
   ("read-address-is-word","keepAliveWord8","readWord8OffAddr#",Just 0,"long","WordRep"),
   ("read-state-is-int","keepAliveWord8","readWord8OffAddr#",Just 2,"long","IntRep"),
   ("read-offset-is-word","keepAliveWord8","readWord8OffAddr#",Just 1,"long","WordRep"),
   ("contents-lifted-array","keepAliveWord8","byteArrayContents#",Just 0,"object","BoxedRep (Just Lifted)"),
   ("contents-result-is-word","keepAliveWord8","byteArrayContents#",Nothing,"long","WordRep"),
   ("allocation-size-is-word","pinnedBytes","newPinnedByteArray#",Just 0,"long","WordRep"),
   ("allocation-state-is-int","pinnedBytes","newPinnedByteArray#",Just 1,"long","IntRep"),
   ("aligned-alignment-is-word","alignedBytes","newAlignedPinnedByteArray#",Just 1,"long","WordRep"),
   ("keepalive-state-is-int","keepAliveWord8","keepAlive#",Just 1,"long","IntRep"),
   ("keepalive-result-word-not-word8","keepAliveWord8","keepAlive#",Nothing,"long","WordRep")]

forge :: Value -> Value -> String -> Maybe Int -> String -> String -> Either String Value
forge modules baseline primitive argument kind rep = do
  accepted <- field "accepted" baseline
  ensure accepted "Negative baseline rejected"
  reached <- field "reachableBindings" baseline >>= mapM (field "id")
  candidates <- applications modules primitive (Just (Set.fromList reached))
  (appPath,app) <- case candidates of first:_ -> Right first; [] -> Left "Missing mutation site"
  let expressionPath = maybe [] (\index -> [Index 2,Index index]) argument
  expr <- at expressionPath app
  nested <- proofPath expr
  let path = appPath ++ expressionPath ++ nested
  proof <- at path modules
  let tuple = argument == Nothing && field "aggregate" proof == Right ("unboxed-tuple" :: String)
  when tuple $ do
    components <- field "components" proof :: Either String [Value]
    ensure (length components == 2) "Unexpected result shape"
  let componentPath = path ++ if tuple then [Key "components",Index 1] else []
  changed <- replace (componentPath ++ [Key "kind"]) (String (fromString kind)) modules >>=
    replace (componentPath ++ [Key "primReps"]) (toJSON [rep])
  if tuple then replace (path ++ [Key "primReps"]) (toJSON [rep]) changed else pure changed

-- Exercise the actual producer checker; a second checker cannot establish
-- that this call-accounting contract rejects malformed concrete call sites.
structureControls :: Either String Value
structureControls = do
  baseline <- guestStructure "keepAliveWord8" report modules
  calls <- field "guestCalls" baseline :: Either String Int
  ensure (calls == 3) "Structural positive control failed"
  forM_ changes $ \(label,path,value) -> do
    changed <- replace path value modules
    case guestStructure "keepAliveWord8" report changed of
      Left _ -> pure ()
      Right _ -> Left ("Structural negative accepted: " ++ label)
  case forge (toJSON ([] :: [Value])) (object ["accepted" .= False]) "readWord8OffAddr#" Nothing "long" "WordRep" of
    Left "Negative baseline rejected" -> pure ()
    _ -> Left "Negative baseline guard failed"
  pure $ object ["acceptedBaselineGuestCalls" .= calls,"rejected" .= (map (\(label,_,_) -> label) changes ++ ["rejected-proof-baseline"])]
  where
    state name = object ["id" .= name,"name" .= name,"type" .= ("State# RealWorld" :: String),
      "rep" .= voidRep,"lifted" .= False,"coercion" .= False]
    result = object ["kind" .= ("unknown" :: String),"primReps" .= ["Word8Rep" :: String],"evaluated" .= False,
      "aggregate" .= ("unboxed-tuple" :: String),"components" .= [voidRep,
        object ["kind" .= ("long" :: String),"primReps" .= ["Word8Rep" :: String],"evaluated" .= True]]]
    void = toJSON [String "void",object ["rep" .= voidRep]]
    continuation = toJSON [String "lam",toJSON [state ("s" :: String)],void,object ["resultRep" .= result]]
    keep = toJSON [String "app",toJSON [String "prim",String "keepAlive#"],
      toJSON [toJSON [String "var",String "bytes"],void,continuation],toJSON [False,False,True],Bool False,Bool False,object ["rep" .= result]]
    immediate = toJSON [String "lam",toJSON [state ("s0" :: String)],keep,object ["resultRep" .= result]]
    call = toJSON [String "app",immediate,toJSON [void],toJSON [False],Bool False,Bool False,object []]
    formal = object ["id" .= ("raw" :: String),"name" .= ("raw" :: String),
      "rep" .= object ["kind" .= ("long" :: String),"primReps" .= ["IntRep" :: String]],"lifted" .= False,"coercion" .= False]
    root = toJSON [String "lam",toJSON [formal],call,object []]
    modules = toJSON [object ["bindings" .= [object ["id" .= ("root" :: String),"name" .= ("keepAliveWord8" :: String),"expr" .= root]]]]
    report = object ["roots" .= ["root" :: String],"reachableBindings" .= [object ["id" .= ("root" :: String)]]]
    rootPath = [Index 0,Key "bindings",Index 0,Key "expr"]
    callPath = rootPath ++ [Index 2]
    keepPath = callPath ++ [Index 1,Index 2]
    continuationPath = keepPath ++ [Index 2,Index 2]
    changes =
      [("host-arity",rootPath ++ [Index 1],toJSON [formal,formal]),
       ("state-rep",callPath ++ [Index 1,Index 1,Index 0,Key "rep",Key "kind"],String "long"),
       ("state-flag",callPath ++ [Index 3],toJSON [True]),
       ("continuation-rep",continuationPath ++ [Index 1,Index 0,Key "rep",Key "primReps"],toJSON ["IntRep" :: String]),
       ("result",keepPath ++ [Index 6,Key "rep",Key "primReps"],toJSON ["WordRep" :: String]),
       ("hidden-lambda",continuationPath ++ [Index 2],toJSON [String "let",Bool False,
         toJSON [object ["id" .= ("hidden" :: String),"expr" .= continuation]],void]),
       ("global",[Index 0,Key "bindings",Index 0,Key "name"],String "different")]

sourcePaths :: FilePath -> IO [FilePath]
sourcePaths root = do
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  pure $ sort $ ["compiler/test-fixtures/PinnedAddressAudit.hs","compiler/test-fixtures/PinnedAddressAuditNative.hs",
    "test/haskell-fixtures/PinnedAddressFixtures.hs","test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs",
    "thc.cabal","compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
    "scripts/audit-core.py","scripts/core-capabilities.json","scripts/generate-scalar-signatures.py",
    "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

preparePinnedAddresses :: FilePath -> Bool -> Bool -> Bool -> IO ()
preparePinnedAddresses root nativeOnly exportOnly allowUnsupported = do
  check (not (nativeOnly && exportOnly)) "Conflicting modes"
  controls <- either die pure structureControls
  createDirectoryIfMissing True (root </> directory)
  let manifestPath = root </> directory </> "manifest.json"
      logs = directory </> "commands"
      requestText = unlines [intercalate "\t" (name:map show args) | (name,args) <- requests]
      oracleText = unlines [intercalate "\t" (name:map show (args ++ [expected name args])) | (name,args) <- requests]
  present <- doesFileExist manifestPath
  when present $ do
    digest <- hashFile manifestPath
    let archive = root </> directory </> "previous-manifests" </> digest ++ ".json"
    createDirectoryIfMissing True (root </> directory </> "previous-manifests")
    exists <- doesFileExist archive
    unless exists (copyFile manifestPath archive)
    removeFile manifestPath
  check (length requests == 7269 && length (nub requests) == 7269) "Changed pinned-address corpus"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  check (lines version == ["9.14.1"]) "Pinned GHC 9.14.1 required"
  info <- run root [] ghc ["--info"] ""
  order <- alloca $ \ptr -> do poke ptr (1 :: Word16); byte <- peek (castPtr ptr :: Ptr Word8); pure (if byte == 1 then "little" else "big" :: String)
  case readMaybe info :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      lookup "target word big endian" fields == Just (if order == "big" then "YES" else "NO") -> pure ()
    _ -> die "Pinned addresses require native-order 64-bit GHC"
  writeFile (root </> directory </> "requests.tsv") requestText
  writeFile (root </> directory </> "expected.tsv") oracleText
  writeJson (root </> directory </> "structure-controls.json") controls
  nativeArtifacts <- if exportOnly then pure [] else do
    let native = directory </> "native"
        binary = native </> "pinned-address-oracle"
    createDirectoryIfMissing True (root </> native)
    _ <- runLogged 300 root logs "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-icompiler/test-fixtures","-odir",root </> native,"-hidir",root </> native,
      "compiler/test-fixtures/PinnedAddressAuditNative.hs","-o",root </> binary]
    result <- runLoggedWithInput (directory </> "requests.tsv") 60 root logs "native-oracle" [] (root </> binary) []
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout result)
    output <- readFile (root </> directory </> "oracle.tsv")
    check (output == oracleText) "Native TSV differs from independent model"
    pure ([directory </> "oracle.tsv"] ++ [native </> name | name <- ["pinned-address-oracle","Main.hi","Main.o","PinnedAddressAudit.hi","PinnedAddressAudit.o"]] ++
      commandFiles ["native-build","native-oracle"])
  stages <- if nativeOnly then pure [] else forM ["pre","post"] $ \stage -> do
    let base = directory </> stage
        paths = [base </> "core" </> name ++ ".json" | name <- ["PinnedAddressAudit","THC.InterfaceClosure"]]
    _ <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",root </> base </> "core"),("THC_GHC_OUT",root </> base </> "ghc"),("THC_SOURCE_NOTES","true")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ name | (name,_) <- entries ++ frontiers] ++ ["compiler/test-fixtures/PinnedAddressAudit.hs"])
    modules <- toJSON <$> mapM (readJson . (root </>)) paths
    boundary <- either die pure (at [Index 0,Key "boundary"] modules)
    check (boundary == String (if stage == "pre" then "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep")) "Wrong actual Core stage"
    reports <- forM (entries ++ frontiers) $ \(name,_) -> do
      report <- audit stage name paths name (if name `elem` map fst entries then if allowUnsupported then Nothing else Just 0 else Just 1)
      accepted <- either die pure (field "accepted" report)
      if name `elem` map fst entries then unless allowUnsupported (check accepted "Unsupported genuine Core") else do
        ids <- either die pure (field "missingGlobals" report >>= mapM (field "id")) :: IO [String]
        let missing = Set.fromList [drop 1 (dropWhile (/= ':') ident) | ident <- ids]
            required = Set.fromList ["GHC.Internal.Foreign.Storable.$fStorableFingerprint_$s$w" ++ operation ++ "W64" |
              operation <- "poke" : ["peek" | name == "publicFingerprintRoundtrip"]]
        check (not accepted && required `Set.isSubsetOf` missing) "Public Storable frontier changed"
      pure (name,report)
    let supported = [report | (name,report) <- reports, name `elem` map fst entries]
    accepted <- and <$> mapM (either die pure . field "accepted") supported
    primitives <- concat <$> mapM (either die pure . (field "primitives" >=> mapM (field "name"))) supported :: IO [String]
    check (all (`elem` primitives) ["newPinnedByteArray#","newAlignedPinnedByteArray#","byteArrayContents#","readWord8OffAddr#","writeWord8OffAddr#","keepAlive#"])
      "Required primitives disappeared"
    structures <- forM entries $ \(name,_) -> do
      report <- maybe (die "Missing baseline") pure (lookup name reports)
      structure <- either die pure (guestStructure name report modules)
      pure (stage ++ "/" ++ name,structure)
    keep <- either die pure (applications modules "keepAlive#" Nothing)
    keepSites <- forM keep $ \(_,app) -> either die pure $ do
      args <- at [Index 2] app >>= decode :: Either String [Value]
      proofs <- mapM rawProof args
      flags <- at [Index 3] app
      result <- rawProof app
      pure (object ["argumentProofs" .= proofs,"flags" .= flags,"result" .= result])
    negativesAndArtifacts <- if not accepted then pure [] else forM negatives $ \(label,name,primitive,argument,kind,rep) -> do
      baseline <- maybe (die "Missing negative baseline") pure (lookup name reports)
      changed <- either die pure (forge modules baseline primitive argument kind rep)
      let mutatedPaths = [base </> "negative" </> label ++ "-" ++ show i ++ ".json" | i <- [0 :: Int,1]]
      createDirectoryIfMissing True (root </> base </> "negative")
      forM_ (zip mutatedPaths (array changed)) (\(path,value) -> writeJson (root </> path) value)
      report <- audit stage ("negative-" ++ label) mutatedPaths name (Just 1)
      rejected <- either die pure (field "accepted" report)
      issues <- either die pure (field "issues" report) :: IO [Value]
      check (not rejected && not (null issues)) ("Forged proof accepted: " ++ label)
      summary <- either die pure (field "summary" report) :: IO Value
      pure (label,object ["entry" .= name,"primitive" .= primitive,"summary" .= summary,"issues" .= issues],
        mutatedPaths ++ [base </> "negative-" ++ label ++ ".audit.json"] ++ commandFiles [stage ++ "-negative-" ++ label ++ "-audit"])
    let negativeReports = Map.fromList [(label,report) | (label,report,_) <- negativesAndArtifacts]
        artifacts = paths ++ [base </> name ++ ".audit.json" | (name,_) <- entries ++ frontiers] ++
          commandFiles ((stage ++ "-export"):[stage ++ "-" ++ name ++ "-audit" | (name,_) <- entries ++ frontiers]) ++
          concat [files | (_,_,files) <- negativesAndArtifacts] ++ [base </> "negative-proofs.json" | accepted]
    when accepted (writeJson (root </> base </> "negative-proofs.json") (toJSON negativeReports))
    pure (stage,paths,Map.fromList reports,keepSites,negativeReports,structures,accepted,artifacts)
  inputs <- sourcePaths root >>= hashes root
  artifacts <- hashes root (sort ([directory </> name | name <- ["requests.tsv","expected.tsv","structure-controls.json"]] ++
    nativeArtifacts ++ concat [files | (_,_,_,_,_,_,_,files) <- stages]))
  let mode = if nativeOnly then "native-only" else if exportOnly then "export-only" else "full" :: String
      strict = not nativeOnly && all (\(_,_,_,_,_,_,accepted,_) -> accepted) stages
      nativeRows = if exportOnly then 0 else length requests
  writeJson manifestPath $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= info,
    "entries" .= Map.fromList entries,"publicFrontiers" .= Map.fromList frontiers,
    "nativeByteOrder" .= order,"fingerprintByteOrder" .= ("big" :: String),
    "strictAccepted" .= strict,"mode" .= mode,
    "stages" .= Map.fromList [(stage,paths) | (stage,paths,_,_,_,_,_,_) <- stages],
    "audits" .= Map.fromList [(stage,reports) | (stage,_,reports,_,_,_,_,_) <- stages],
    "keepAliveSites" .= Map.fromList [(stage,sites) | (stage,_,_,sites,_,_,_,_) <- stages],
    "negativeProofs" .= Map.fromList [(stage,reports) | (stage,_,_,_,reports,_,_,_) <- stages],
    "expectedGuestCallsByEntry" .= Map.fromList guestCalls,
    "checkedGuestStructureByStage" .= Map.fromList (concat [structures | (_,_,_,_,_,structures,_,_) <- stages]),
    "nativeRows" .= nativeRows,"modelRows" .= length requests,
    "rowCounts" .= Map.fromListWith (+) [(name,1 :: Int) | (name,_) <- requests],
    "rows" .= [object ["entry" .= name,"arguments" .= args,"expected" .= expected name args] | (name,args) <- requests],
    "inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "limits" .= (["Public Storable roots remain native evidence and explicit exported frontiers, not THC support.",
      "Primitive fingerprintByte is byte-layout conformance, not a replacement Storable implementation.",
      "Defined native domains only; malformed/bounds failures are non-native runtime tests.",
      "Managed pinning is not physical JVM pinning; no general foreign calls or raw process pointers.",
      "The existing shared Python audit-core.py proof implementation remains an explicit dependency."] :: [String])]
  putStrLn ("Pinned addresses: mode=" ++ mode ++ "; nativeRows=" ++ show nativeRows ++
    "; modelRows=7269; strictAccepted=" ++ show strict)
  where
    commandFiles names = [directory </> "commands" </> name ++ "." ++ suffix | name <- names, suffix <- ["stdout","stderr","command.json"]]
    audit stage label paths entry expectedExit = do
      let output = directory </> stage </> label ++ ".audit.json"
          args = ["scripts/audit-core.py"] ++ paths ++ ["--entry",prefix ++ entry,"--output",output]
          commandPrefix = root </> directory </> "commands" </> stage ++ "-" ++ label ++ "-audit"
          permitted = maybe [0,1] (:[]) expectedExit
      createDirectoryIfMissing True (root </> directory </> "commands")
      completed <- timeout (120*1000000) (readCreateProcessWithExitCode ((proc "python3" args) {cwd = Just root}) "")
      (status,out,err) <- maybe (die "Pinned-address audit timed out") pure completed
      let code = case status of ExitSuccess -> 0; ExitFailure n -> n
      writeFile (commandPrefix ++ ".stdout") out
      writeFile (commandPrefix ++ ".stderr") err
      writeJson (commandPrefix ++ ".command.json") (object ["argv" .= ("python3":args),"cwd" .= root,
        "exit" .= code,"expectedExits" .= permitted,"timeoutSeconds" .= (120 :: Int)])
      check (code `elem` permitted) ("Pinned-address audit failed: " ++ commandPrefix)
      report <- readJson (root </> output)
      accepted <- either die pure (field "accepted" report)
      check (accepted == (code == 0)) "Audit report/exit disagreement"
      pure report
