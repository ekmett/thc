{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import GHC hiding (exprType)
import GHC.Plugins hiding (getSession, setSession)
import GHC.Driver.Backend (interpreterBackend)
import GHC.Runtime.Context (ic_mod_index)
import THC.Interactive
import THC.Plugin (InteractiveStage(..), serializeInteractiveCore)
import Control.Monad (unless, void)
import qualified Control.Monad.Catch as Catch
import Control.Exception (IOException)
import Data.IORef
import Data.List (isInfixOf)
import System.Environment (getArgs)
import System.Directory
import System.FilePath ((</>))

ensure :: GhcMonad m => String -> Bool -> m ()
ensure label ok = unless ok $ liftIO $ ioError $ userError label

capture :: FilePath -> String -> String -> Ghc PreparedStatement
capture out label source = do
  value <- prepareStatement (label ++ ".ghci") 1 source
  case value of
    Nothing -> liftIO $ ioError $ userError ("no statement: " ++ label)
    Just p -> do
      env <- getSession
      bytes <- liftIO $ statementJSON env p
      liftIO $ writeFile (out </> label ++ ".json") bytes
      raw <- liftIO $ desugaredStatementJSON env p
      liftIO $ writeFile (out </> label ++ "-desugared.json") raw
      pure p

references :: Id -> CoreExpr -> Bool
references wanted = go
  where
    go (Var v) = idName v == idName wanted
    go (App f x) = go f || go x
    go (Lam _ x) = go x
    go (Let b x) = any (go . snd) (flattenBinds [b]) || go x
    go (Case x _ _ as) = go x || any (\(Alt _ _ e) -> go e) as
    go (Cast x _) = go x
    go (Tick _ x) = go x
    go _ = False

single :: PreparedStatement -> Id
single p = case statementBindings p of [v] -> v; _ -> error "expected one interactive binding"

main :: IO ()
main = do
  [libdir,out] <- getArgs
  createDirectoryIfMissing True out
  captures <- newIORef ([] :: [(Module, String)])
  runGhc (Just libdir) $ do
    flags <- getSessionDynFlags
    void $ setSessionDynFlags (gopt_set flags Opt_DoCoreLinting) { backend = interpreterBackend, ghcLink = LinkInMemory
                                   , importPaths = [out], verbosity = 0 }
    env <- getSession
    setSession $ rejectNativeEvaluation $ installModuleCapture (\current guts -> do
      body <- serializeInteractiveCore current (cg_module guts) InteractiveModule
                  (cg_binds guts) (cg_tycons guts) []
      modifyIORef' captures (++ [(cg_module guts, body)])) env
    setContext [IIDecl (simpleImportDecl (mkModuleName "Prelude"))]

    x0 <- capture out "x-original" "let x = (40 :: Int)"
    commitStatementContext x0 -- type-context simulation; no guest evaluation claimed
    f <- capture out "f-retains-x" "let f n = x + n"
    ensure "closure does not retain original x" $ references (single x0) (statementCore f)
    commitStatementContext f
    x1 <- capture out "x-shadow" "let x = (99 :: Int)"
    ensure "shadowed Name reused" $ idName (single x0) /= idName (single x1)
    commitStatementContext x1
    next <- capture out "expression-print" "f x"
    ensure "future statement does not use shadowed x" $ references (single x1) (statementCore next)
    ensure "future statement refers to old x" $ not $ references (single x0) (statementCore next)
    ensure "GHCi omitted it" $ occNameString (nameOccName (idName (single next))) == "it"
    ensure "GHCi print action absent" $ "print" `isInfixOf` showSDocUnsafe (ppr (statementDesugaredCore next))

    before <- ic_mod_index . hsc_IC <$> getSession
    rejected <- handleSourceError (\_ -> pure True) $ prepareStatement "bad.ghci" 1 "True + (1 :: Int)" >> pure False
    after <- ic_mod_index . hsc_IC <$> getSession
    ensure "type error accepted or changed context" (rejected && before == after)
    stale <- Catch.catch (commitStatementContext x0 >> pure False) (\(e :: IOException) -> pure ("stale name epoch" `isInfixOf` show e))
    ensure "stale commit accepted" stale

    multiple <- capture out "pattern-bindings" "let (left, right) = ((1 :: Int), (2 :: Int))"
    ensure "pattern binding Ids lost" $ length (statementBindings multiple) == 2
    commitStatementContext multiple

    bottom <- capture out "lazy-bottom" "let dormant = (error \"not evaluated\" :: Int)"
    commitStatementContext bottom
    let sentinel = out </> "USER-CODE-RAN"
    void $ capture out "io-action" ("writeFile " ++ show sentinel ++ " \"unexpected\"")
    attempted <- Catch.catch (execStmt ("writeFile " ++ show sentinel ++ " \"unexpected\"") execOptions >> pure False)
                             (\(e :: IOException) -> pure ("Native user evaluation is disabled" `isInfixOf` show e))
    ensure "native fallback was not rejected" attempted
    exists <- liftIO $ doesFileExist sentinel
    ensure "user IO executed" (not exists)

    declarations <- prepareDeclarations "decls.ghci" 1
      "data Chain = End | Int :*: Chain; infixr 6 :*:; answer :: Int; answer = 42; class Label a where { label :: a -> Int }; instance Label Chain where { label _ = answer }"
    env1 <- getSession
    declJSON <- liftIO $ declarationsJSON env1 declarations
    liftIO $ writeFile (out </> "declarations.json") declJSON
    ensure "declarations have no post-Tidy bindings" $ not $ null $ cg_binds $ declarationGuts declarations
    commitDeclarationsContext declarations
    fixityUse <- capture out "fixity-use" "let chain = (1 :: Int) :*: 2 :*: End"
    -- This typechecks only with the declared right-associative fixity.
    commitStatementContext fixityUse
    void $ capture out "instance-use" "let labelled = label End"
    let modulePath = out </> "Loaded.hs"
    liftIO $ writeFile modulePath $ unlines
      ["module Loaded where", "data Box = Box Int", "value :: Int", "value = 7"
      ,"dormant :: Int", "dormant = error \"module body not evaluated\""]
    target <- guessTarget modulePath Nothing Nothing
    setTargets [target]
    loaded <- load LoadAllTargets
    ensure "module load failed" $ succeeded loaded
    first <- liftIO $ readIORef captures
    firstBody <- case first of
      [(_, body)] -> pure body
      _ -> liftIO $ ioError $ userError "module post-Tidy hook not called exactly once"
    liftIO $ writeFile (out </> "module-first.json") firstBody
    loadedModule <- findModule (mkModuleName "Loaded") Nothing
    setContext [IIModule loadedModule, IIDecl (simpleImportDecl (mkModuleName "Prelude"))]
    void $ capture out "loaded-expression" "value"
    unchanged <- load LoadAllTargets
    ensure "unchanged module load failed" $ succeeded unchanged
    unchangedCaptures <- liftIO $ readIORef captures
    ensure "unchanged load produced duplicate module captures" $ length unchangedCaptures `elem` [1, 2]
    liftIO $ putStrLn $ "PASS unchanged load: " ++ show (length unchangedCaptures - 1) ++ " actual GHC post-Tidy captures"
    -- Force recompilation rather than relying on filesystem timestamp granularity.
    flags2 <- getSessionDynFlags
    void $ setSessionDynFlags (gopt_set flags2 Opt_ForceRecomp)
    liftIO $ writeFile modulePath $ unlines
      ["module Loaded where", "data Box = Box Int", "value :: Int", "value = 8"
      ,"dormant :: Int", "dormant = error \"module body not evaluated\""]
    reloaded <- load LoadAllTargets
    ensure "module reload failed" $ succeeded reloaded
    second <- liftIO $ readIORef captures
    ensure "changed reload capture count" $ length second == length unchangedCaptures + 1
    reloadedBody <- case reverse second of
      (_, body) : _ -> pure body
      _ -> liftIO $ ioError $ userError "changed reload has no Core"
    ensure "reload returned old Core" $ firstBody /= reloadedBody
    liftIO $ writeFile (out </> "module-reloaded.json") reloadedBody
    liftIO $ putStrLn "PASS module: real load/reload, complete post-Tidy capture, retained bottom"
    liftIO $ putStrLn "PASS statements: real typechecker/desugarer, Names, errors, lazy values, native guard"
  putStrLn "PASS capture-only: no THC evaluation or working REPL claimed"
