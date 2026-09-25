-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module Main (main) where

import Control.Monad (forM, forM_, unless, when)
import Data.Char (chr, digitToInt, isHexDigit)
import Data.List (isPrefixOf, isSuffixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.IO as Text
import System.Directory
import System.Environment (getArgs)
import System.Exit (die)
import System.FilePath
import System.Process (readProcess)
import Text.HTML.TagSoup

data Guide = Guide FilePath String String

-- An allowlist, not a recursive copy of docs/ (which also holds large evidence).
guides :: [Guide]
guides =
  [ Guide "docs/driver.md" "driver" "Build and run"
  , Guide "docs/cabal.md" "cabal" "Cabal integration"
  , Guide "docs/site/embedding.md" "embedding" "Embed on the JVM"
  , Guide "docs/ghc-core.md" "ghc-core" "GHC library Core"
  , Guide "docs/core-package-manifest.md" "core-packages" "Core packages"
  , Guide "docs/interface-foreign.md" "interface-foreign" "Foreign artifacts"
  , Guide "docs/polyglot.md" "polyglot" "Polyglot calls"
  , Guide "docs/bytecode.md" "bytecode" "Bytecode backend"
  , Guide "docs/contributing.md" "contributing" "Development"
  , Guide "docs/documentation.md" "documentation" "Build this site"
  ]

site :: FilePath
site = "build/site"

repo :: String
repo = "https://github.com/ekmett/thc"

guidePath :: Guide -> FilePath
guidePath (Guide _ slug _) = "guides" </> slug <.> "html"

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["build", revision, pandoc] -> do
      checkRevision revision
      forM_ ["haskell", "jvm"] $ \kind -> do
        built <- Text.strip <$> Text.readFile ("build/docs" </> kind <.> "revision")
        unless (built == Text.pack revision) $ die (kind ++ " documentation is from a different revision")
      buildSite revision pandoc
      checkSite revision
    ["check", revision] -> checkRevision revision >> checkSite revision
    _ -> die "Usage: thc-docs (build REVISION PANDOC | check REVISION)"

checkRevision :: String -> IO ()
checkRevision revision = do
  unless (length revision == 40 && all (`elem` ("0123456789abcdef" :: String)) revision) $
    die "Documentation requires a full lowercase Git commit ID"
  current <- Text.strip . Text.pack <$> readProcess "git" ["rev-parse", "HEAD"] ""
  unless (current == Text.pack revision) $ die "Documentation revision must match this checkout's HEAD"

buildSite :: String -> FilePath -> IO ()
buildSite revision pandoc = do
  -- Only this generated directory is replaced. Refuse a redirected destination.
  exists <- doesPathExist site
  when exists $ do
    symbolic <- pathIsSymbolicLink site
    when symbolic $ die "Refusing to replace a symlink at build/site"
    removeDirectoryRecursive site
  createDirectoryIfMissing True (site </> "assets")
  copyFile "docs/site/site.css" (site </> "assets/site.css")
  haskellFiles <- filesBelow "build/docs/haskell"
  let roots = [takeDirectory p | p <- haskellFiles, takeFileName p == "THC-Plugin.html"]
  haskellRoot <- case roots of
    [root] -> pure root
    _ -> die "Expected exactly one lib:thc Haddock output; run make docs-haskell"
  copyTree haskellRoot (site </> "api/haskell")
  copyTree "build/docs/jvm" (site </> "api/jvm")
  apiFiles <- filesBelow (site </> "api")
  forM_ (filter ((== ".html") . takeExtension) apiFiles) $ \path -> do
    html <- Text.readFile path
    let relative = makeRelative site path
    enhanced <- addChrome revision relative html
    Text.writeFile path enhanced
  forM_ guides $ \guide@(Guide source _ title) -> renderGuide revision pandoc source (guidePath guide) title
  renderGuide revision pandoc "docs/site/index.md" "index.html" "Haskell on Truffle/Graal"
  Text.writeFile (site </> "revision.txt") (Text.pack (revision ++ "\n"))
  Text.writeFile (site </> ".nojekyll") ""

filesBelow :: FilePath -> IO [FilePath]
filesBelow root = do
  names <- sort <$> listDirectory root
  fmap concat $ forM names $ \name -> do
    let path = root </> name
    symbolic <- pathIsSymbolicLink path
    when symbolic $ die ("Unexpected symlink in documentation: " ++ path)
    directory <- doesDirectoryExist path
    if directory then filesBelow path else pure [path]

copyTree :: FilePath -> FilePath -> IO ()
copyTree source destination = do
  paths <- filesBelow source
  forM_ paths $ \path -> do
    let target = destination </> makeRelative source path
    createDirectoryIfMissing True (takeDirectory target)
    copyFile path target

-- Always relative to the generated site root: file://, / and /thc/ all agree.
fromPage :: FilePath -> FilePath -> String
fromPage page target = concat (replicate depth "../") ++ target
  where depth = length (filter (/= ".") (splitDirectories (takeDirectory page)))

escape :: String -> String
escape value = renderTags [TagText value]

link :: String -> String -> String
link url title = "<a href=\"" ++ escape url ++ "\">" ++ escape title ++ "</a>"

navigation :: String -> FilePath -> String
navigation revision page =
  "<header class=\"thc-header\"><nav aria-label=\"THC documentation\">" ++
  "<a class=\"thc-brand\" href=\"" ++ fromPage page "index.html" ++ "\">thc<span> / docs</span></a>" ++
  "<div class=\"thc-links\">" ++
  link (fromPage page "guides/driver.html") "Guides" ++
  link (fromPage page "api/jvm/index.html") "JVM reference" ++
  link (fromPage page "api/haskell/index.html") "Haskell API" ++
  link (repo ++ "/tree/" ++ revision) "Source ↗" ++ "</div></nav>" ++
  "<div class=\"thc-toolchain\">Experimental · GHC 9.14.1 · cabal-install 3.16 · " ++
  "GraalVM 25.3.4.1 / JDK 25 · Kotlin 2.4.20 <span>" ++
  link (repo ++ "/commit/" ++ revision) (take 12 revision) ++ "</span></div></header>"

addChrome :: String -> FilePath -> Text.Text -> IO Text.Text
addChrome revision page html = do
  let stylesheet = "<link rel=\"stylesheet\" href=\"" ++ fromPage page "assets/site.css" ++ "\">"
      (beforeBody, body) = Text.breakOn "<body" html
      (opening, remainder) = Text.breakOn ">" body
  unless (not (Text.null body) && not (Text.null remainder) && "</head>" `Text.isInfixOf` html) $
    die ("Expected an HTML document: " ++ page)
  pure $ Text.replace "</head>" (Text.pack stylesheet <> "</head>") $
    beforeBody <> opening <> ">" <> Text.pack (navigation revision page) <> Text.drop 1 remainder

renderGuide :: String -> FilePath -> FilePath -> FilePath -> String -> IO ()
renderGuide revision pandoc source output title = do
  fragment <- readProcess pandoc ["--from=gfm", "--to=html5", "--wrap=none", source] ""
  tags <- mapM rewriteTag (parseTags fragment)
  let sidebar = "<aside class=\"thc-sidebar\"><p>In this documentation</p>" ++
        concat [link (fromPage output (guidePath guide)) label | guide@(Guide _ _ label) <- guides] ++ "</aside>"
      content = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" ++
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" ++ escape title ++
        " · THC</title></head><body class=\"thc-guide\"><div class=\"thc-layout\">" ++ sidebar ++
        "<main class=\"thc-prose\" id=\"main\">" ++ renderTags tags ++
        "<footer class=\"thc-footer\">" ++ link (repo ++ "/blob/" ++ revision ++ "/" ++ source) "View this page's source" ++
        " · UPL-1.0 AND BSD-3-Clause</footer></main></div></body></html>"
  html <- addChrome revision output (Text.pack content)
  createDirectoryIfMissing True (takeDirectory (site </> output))
  Text.writeFile (site </> output) html
  where
    rewriteTag (TagOpen tag attrs) = TagOpen tag <$> mapM rewriteAttr attrs
    rewriteTag tag = pure tag
    rewriteAttr (key, value) | key `elem` ["href", "src"] = do
      url <- guideURL revision source output value
      pure (key, url)
    rewriteAttr attribute = pure attribute

external :: String -> Bool
external url = "//" `isPrefixOf` url || ':' `elem` takeWhile (`notElem` ("/?#" :: String)) url

-- Resolve repository-relative Markdown links before moving a guide. Omitted
-- reports, source and evidence remain pinned repository links, never site copies.
guideURL :: String -> FilePath -> FilePath -> String -> IO String
guideURL revision source output url
  | null url || "#" `isPrefixOf` url || external url = pure url
  | "/" `isPrefixOf` url = die ("Root-relative guide link: " ++ source ++ ": " ++ url)
  | otherwise = do
      let (path, suffix) = break (`elem` ("?#" :: String)) url
          resolved = collapse (takeDirectory source </> decodeURL path)
          pages = ("docs/site/index.md", "index.html") : [(src, guidePath g) | g@(Guide src _ _) <- guides]
      case lookup resolved pages of
        Just target -> pure (fromPage output target ++ suffix)
        Nothing -> do
          exists <- doesPathExist resolved
          unless (exists && not (".." `isPrefixOf` resolved)) $
            die ("Missing repository link in " ++ source ++ ": " ++ url)
          directory <- doesDirectoryExist resolved
          pure (repo ++ (if directory then "/tree/" else "/blob/") ++ revision ++ "/" ++ resolved ++ suffix)

collapse :: FilePath -> FilePath
collapse = joinPath . foldl step [] . splitDirectories
  where
    step xs "." = xs
    step [] ".." = [".."]
    step xs ".." | last xs /= ".." = init xs
    step xs x = xs ++ [x]

decodeURL :: String -> String
decodeURL ('%':a:b:rest) | isHexDigit a && isHexDigit b = chr (16 * digitToInt a + digitToInt b) : decodeURL rest
decodeURL (c:rest) = c : decodeURL rest
decodeURL [] = []

checkSite :: String -> IO ()
checkSite revision = do
  built <- Text.strip <$> Text.readFile (site </> "revision.txt")
  unless (built == Text.pack revision) $ die "The assembled site has a different revision"
  files <- filesBelow site
  let htmlFiles = filter ((== ".html") . takeExtension) files
      inventory = Set.fromList (map (makeRelative site) files)
  pages <- fmap Map.fromList $ forM htmlFiles $ \file -> do
    html <- Text.readFile file
    let tags = parseTags (Text.unpack html)
        anchors = Set.fromList [value | TagOpen tag attrs <- tags, (key,value) <- attrs,
          key == "id" || (tag == "a" && key == "name")]
        urls = [value | TagOpen _ attrs <- tags, (key,value) <- attrs, key `elem` ["href", "src"]]
        path = makeRelative site file
    unless ("class=\"thc-header\"" `Text.isInfixOf` html && Text.pack revision `Text.isInfixOf` html) $
      die ("Missing shared navigation/revision in " ++ path)
    pure (path, (anchors, urls))
  let failures = concat [checkURL inventory pages page url | (page, (_,urls)) <- Map.toList pages, url <- urls]
      required = ["index.html", "api/jvm/index.html", "api/haskell/index.html",
        "api/haskell/THC-Plugin.html", "api/haskell/THC-Interface.html"] ++ map guidePath guides
      excluded = [path | path <- Set.toList inventory, any (`isSuffixOf` path) [".bgv", ".log", ".zip", ".tar.xz"]]
      missing = [path | path <- required, Set.notMember path inventory]
  unless (null (failures ++ missing ++ excluded)) $
    die (unlines (take 50 (failures ++ map ("Missing page: " ++) missing ++ map ("Unexpected evidence: " ++) excluded)))
  -- Check that source analysis documented both languages, not just an empty index.
  unless (any ("/-calls/" `Text.isInfixOf`) (map Text.pack (Set.toList inventory)) &&
          any ("execution-context.html" `isSuffixOf`) (Set.toList inventory)) $
    die "Missing expected Java Calls or Kotlin executionContext documentation"
  putStrLn ("Documentation checked: " ++ show (length guides) ++ " guides, " ++ show (Map.size pages) ++
    " HTML pages, " ++ show (sum [length urls | (_,urls) <- Map.elems pages]) ++
    " links/assets; relative paths also work below /thc/. Revision " ++ revision)

checkURL :: Set.Set FilePath -> Map.Map FilePath (Set.Set String, [String]) -> FilePath -> String -> [String]
checkURL inventory pages page url
  | null url || external url = [problem "Local filesystem URL" | "file:" `isPrefixOf` url]
  | "/" `isPrefixOf` url = [problem "Root-relative URL breaks project Pages"]
  | otherwise =
      let (rawPath, remainder) = break (`elem` ("?#" :: String)) url
          path = if null rawPath then page else collapse (takeDirectory page </> decodeURL rawPath)
          target = if "/" `isSuffixOf` path then path ++ "index.html" else path
          fragment = case dropWhile (/= '#') remainder of [] -> ""; (_:rest) -> decodeURL rest
      in if Set.notMember target inventory then [problem ("Missing target " ++ target)]
         else case Map.lookup target pages of
           Just (anchors, _) | not (null fragment) && Set.notMember fragment anchors -> [problem ("Missing fragment " ++ fragment)]
           _ -> []
  where problem why = why ++ " in " ++ page ++ ": " ++ url
