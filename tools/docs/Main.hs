-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module Main (main) where

import Control.Monad (forM, forM_, unless, when)
import Data.Char (chr, digitToInt, isHexDigit, ord)
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
import Numeric (showHex)

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
  , Guide "docs/runtime-services.md" "runtime-services" "Haskell runtime services"
  , Guide "docs/bytecode.md" "bytecode" "Bytecode backend"
  , Guide "docs/primop-behavior.md" "primop-behavior" "Primop behavior and limits"
  , Guide "docs/contributing.md" "contributing" "Development"
  , Guide "docs/documentation.md" "documentation" "Build this site"
  ]

site :: FilePath
site = "build/site"

mascotAssets :: [FilePath]
mascotAssets =
  [ "turbo-haskell-bot-flipped-light.png", "turbo-haskell-bot-flipped-dark.png"
  , "turbo-haskell-bot-blocks.webm", "mascot.js"
  ]

-- Dokka fetches this fragment into its sidebar. Adding document chrome here
-- would duplicate navigation inside every API page.
isFragment :: FilePath -> Bool
isFragment = (== "api/jvm/navigation.html")

isHaddock :: FilePath -> Bool
isHaddock path = any (`isPrefixOf` path) ["api/haskell/", "api/runtime/"]

-- Haddock's instance-origin links name the actual defining module, even when
-- that module is hidden. Keep that attribution without publishing private APIs.
-- Only these exact module-page links may point to their tracked source instead.
hiddenHaddockSources :: [(FilePath, FilePath)]
hiddenHaddockSources =
  [("api/runtime/THC-Runtime-Types.html", "runtime/THC/Runtime/Types.hs")]

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
      checkSiteRoot
      forM_ ["haskell", "runtime", "jvm"] $ \kind -> do
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

-- Check the ancestor too: checking only build/site would permit a symlinked
-- build directory to redirect recursive replacement outside the checkout.
checkSiteRoot :: IO ()
checkSiteRoot = do
  root <- getCurrentDirectory >>= canonicalizePath
  redirected <- pathIsSymbolicLink "build"
  actual <- canonicalizePath "build"
  unless (not redirected && actual == root </> "build") $
    die "Refusing redirected build directory; documentation output must stay in this checkout"

buildSite :: String -> FilePath -> IO ()
buildSite revision pandoc = do
  forM_ hiddenHaddockSources $ \(_, source) -> do
    exists <- doesFileExist source
    tracked <- Text.strip . Text.pack <$> readProcess "git" ["ls-files", "--", source] ""
    unless (exists && tracked == Text.pack source) $
      die ("Hidden Haddock module source must exist and be tracked: " ++ source)
  -- Only this generated directory is replaced. Refuse a redirected destination.
  exists <- doesPathExist site
  when exists $ do
    symbolic <- pathIsSymbolicLink site
    when symbolic $ die "Refusing to replace a symlink at build/site"
    removeDirectoryRecursive site
  createDirectoryIfMissing True (site </> "assets")
  copyFile "docs/site/site.css" (site </> "assets/site.css")
  copyFile "docs/site/site.js" (site </> "assets/site.js")
  copyFile "docs/site/theme.js" (site </> "assets/theme.js")
  forM_ mascotAssets $ \name ->
    copyFile ("docs/site" </> name) (site </> "assets" </> name)
  forM_ [("haskell", "THC-Plugin.html", "lib:thc"),
         ("runtime", "THC.html", "lib:runtime")] $ \(kind, marker, component) -> do
    haskellFiles <- filesBelow ("build/docs" </> kind)
    let roots = [takeDirectory p | p <- haskellFiles, takeFileName p == marker]
    haskellRoot <- case roots of
      [root] -> pure root
      _ -> die ("Expected exactly one " ++ component ++ " Haddock output; run make docs-haskell")
    copyTree haskellRoot (site </> "api" </> kind)
  copyTree "build/docs/jvm" (site </> "api/jvm")
  apiFiles <- filesBelow (site </> "api")
  forM_ (filter ((== ".html") . takeExtension) apiFiles) $ \path -> do
    let relative = makeRelative site path
    unless (isFragment relative) $ do
      html <- Text.readFile path
      let repaired = if isHaddock relative then repairHaddock revision relative html else html
      enhanced <- stylePage revision relative repaired
      Text.writeFile path enhanced
  forM_ guides $ \guide@(Guide source _ title) -> renderGuide revision pandoc source (guidePath guide) title
  renderGuide revision pandoc "docs/site/index.md" "home.html" "Haskell on Truffle/Graal"
  renderShell revision ("home.html" : map guidePath guides ++
    [makeRelative site path | path <- apiFiles, takeExtension path == ".html",
      not (isFragment (makeRelative site path))])
  Text.writeFile (site </> "revision.txt") (Text.pack (revision ++ "\n"))
  Text.writeFile (site </> ".nojekyll") ""

-- Haddock 2.33 emits instance-method self links without target IDs, even with
-- dependency interfaces installed. Anchor the actual method declarations; do
-- not exempt missing fragments from validation. Record selectors can also lack
-- a source line: their pinned file URL remains useful without a dangling #L.
-- "Defined in" module links are not entity-home links: Haddock 2.33 emits the
-- original instance module even when hidden. Attribute the known private type
-- module to its real source, leaving public reexports and arbitrary gaps alone.
repairHaddock :: String -> FilePath -> Text.Text -> Text.Text
repairHaddock revision page html = Text.pack (renderTags (go anchors tags))
  where
    tags = parseTags (Text.unpack html)
    anchors = Set.fromList [value | TagOpen _ attrs <- tags, ("id", value) <- attrs]
    go seen (p@(TagOpen "p" attrs) : TagOpen "a" attrs' : rest)
      | lookup "class" attrs == Just "src"
      , Just ('#':fragment) <- lookup "href" attrs'
      , "v:" `isPrefixOf` fragment
      , Set.notMember fragment seen =
          p : TagOpen "a" (("id", fragment) : attrs') : go (Set.insert fragment seen) rest
    go seen (TagOpen tag attrs : rest) = TagOpen tag (map source attrs) : go seen rest
    go seen (tag : rest) = tag : go seen rest
    go _ [] = []
    source ("href", url)
      | Just path <- lookup (takeDirectory page </> url) hiddenHaddockSources =
          ("href", repo ++ "/blob/" ++ revision ++ "/" ++ path)
    source ("href", url) | (repo ++ "/blob/") `isPrefixOf` url && "#L" `isSuffixOf` url =
      ("href", take (length url - 2) url)
    source attr = attr

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

-- Keep generator documents independently usable. The shell owns site navigation;
-- Haddock and Dokka keep their own symbol search, index, anchors and source links.
stylePage :: String -> FilePath -> Text.Text -> IO Text.Text
stylePage revision page html = do
  let stylesheet = "<link rel=\"stylesheet\" href=\"" ++ fromPage page "assets/site.css" ++ "\">"
      theme = "<script src=\"" ++ fromPage page "assets/theme.js" ++ "\"></script>"
      metadata = "<meta name=\"thc-revision\" content=\"" ++ revision ++ "\">"
      section = if isHaddock page then "haskell" else
        if "api/jvm/" `isPrefixOf` page then "jvm" else "guide"
      (beforeBody, body) = Text.breakOn "<body" html
      (opening, remainder) = Text.breakOn ">" body
  unless (not (Text.null body) && not (Text.null remainder) && "</head>" `Text.isInfixOf` html) $
    die ("Expected an HTML document: " ++ page)
  pure $ Text.replace "</head>" (Text.pack (metadata ++ theme ++ stylesheet) <> "</head>") $
    beforeBody <> opening <> Text.pack (" data-thc-section=\"" ++ section ++ "\">") <> Text.drop 1 remainder

jsonString :: String -> String
jsonString value = '"' : concatMap encode value ++ "\""
  where
    encode '"' = "\\\""
    encode '\\' = "\\\\"
    encode '<' = "\\u003c"
    encode c | ord c < 32 = "\\u" ++ replicate (4 - length hex) '0' ++ hex
      where hex = showHex (ord c) ""
    encode c = [c]

renderShell :: String -> [FilePath] -> IO ()
renderShell revision pages = do
  let item page label = "<a class=\"thc-nav-link\" data-page=\"" ++ escape page ++
        "\" href=\"" ++ escape page ++ "\" target=\"thc-content\">" ++ escape label ++ "</a>"
      guideItem guide@(Guide _ _ title) = item (guidePath guide) title
      manifest = "<script type=\"application/json\" id=\"thc-pages\">[" ++
        concat (zipWith (++) ("" : repeat ",") (map jsonString pages)) ++ " ]</script>"
      html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" ++
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" ++
        "<meta name=\"thc-revision\" content=\"" ++ revision ++ "\">" ++
        "<title>THC documentation</title><script src=\"assets/theme.js\"></script>" ++
        "<link rel=\"stylesheet\" href=\"assets/site.css\"></head>" ++
        "<body class=\"thc-shell\"><button class=\"thc-menu\" type=\"button\" aria-expanded=\"false\" " ++
        "aria-controls=\"thc-rail\">Documentation menu</button><div class=\"thc-shell-layout\">" ++
        "<aside class=\"thc-rail\" id=\"thc-rail\"><a class=\"thc-brand\" href=\"home.html\" " ++
        "data-page=\"home.html\" target=\"thc-content\">thc<span> / docs</span></a>" ++
        "<fieldset class=\"thc-appearance\" id=\"thc-appearance\"><legend>Appearance</legend>" ++
        "<div class=\"thc-theme-options\">" ++
        "<button type=\"button\" data-thc-appearance=\"light\" aria-pressed=\"false\">Light</button>" ++
        "<button type=\"button\" data-thc-appearance=\"dark\" aria-pressed=\"false\">Dark</button>" ++
        "<button type=\"button\" data-thc-appearance=\"system\" aria-pressed=\"true\">Follow OS</button>" ++
        "</div></fieldset>" ++
        "<nav aria-label=\"THC documentation\"><p class=\"thc-nav-label\">Start</p>" ++
        item "home.html" "Overview" ++ "<p class=\"thc-nav-label\">Guides</p>" ++
        concatMap guideItem guides ++ "<p class=\"thc-nav-label\">Reference</p>" ++
        item "api/runtime/index.html" "Haskell runtime API" ++
        item "api/haskell/index.html" "Compiler API" ++ item "api/jvm/index.html" "JVM internals" ++
        "</nav><div class=\"thc-rail-footer\">" ++
        "<p>Experimental · GHC 9.14.1 · GraalVM 25.3.4.1</p>" ++
        link (repo ++ "/tree/" ++ revision) "Source ↗" ++ " · " ++
        link (repo ++ "/commit/" ++ revision) (take 12 revision) ++ "</div></aside>" ++
        "<iframe id=\"thc-content\" name=\"thc-content\" title=\"THC documentation content\" " ++
        "src=\"home.html\"></iframe></div>" ++ manifest ++
        "<script src=\"assets/site.js\" defer></script></body></html>"
  Text.writeFile (site </> "index.html") (Text.pack html)

renderGuide :: String -> FilePath -> FilePath -> FilePath -> String -> IO ()
renderGuide revision pandoc source output title = do
  fragment <- readProcess pandoc ["--from=gfm", "--to=html5", "--wrap=none", source] ""
  tags <- mapM rewriteTag (parseTags fragment)
  let content = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" ++
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" ++ escape title ++
        " · THC</title></head><body class=\"thc-guide\">" ++
        "<main class=\"thc-prose\" id=\"main\">" ++ renderTags tags ++
        "<footer class=\"thc-footer\">" ++ link (repo ++ "/blob/" ++ revision ++ "/" ++ source) "View this page's source" ++
        " · UPL-1.0 AND BSD-3-Clause</footer></main></body></html>"
  html <- stylePage revision output (Text.pack content)
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
          pages = ("docs/site/index.md", "home.html") : [(src, guidePath g) | g@(Guide src _ _) <- guides]
          assets = [("docs/site" </> name, "assets" </> name) |
            name <- mascotAssets]
      case lookup resolved (pages ++ assets) of
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
    unless (isFragment path || ("name=\"thc-revision\"" `Text.isInfixOf` html &&
            "assets/site.css" `Text.isInfixOf` html &&
            "assets/theme.js" `Text.isInfixOf` html && Text.pack revision `Text.isInfixOf` html)) $
      die ("Missing shared theme/revision in " ++ path)
    pure (path, (anchors, urls))
  let failures = concat [checkURL inventory pages page url | (page, (_,urls)) <- Map.toList pages, url <- urls]
      required = ["index.html", "home.html", "assets/site.js", "assets/theme.js",
        "api/jvm/index.html", "api/haskell/index.html", "api/runtime/index.html",
        "api/haskell/THC-Plugin.html", "api/haskell/THC-Interface.html"] ++
        map ("api/runtime" </>) ["THC.html", "THC-Runtime.html", "THC-Thread.html",
          "THC-Memory.html", "THC-GC.html", "THC-Trace.html", "THC-Internal-JIT.html"] ++
        map ("assets" </>) mascotAssets ++ map guidePath guides
      excluded = [path | path <- Set.toList inventory, any (`isSuffixOf` path) [".bgv", ".log", ".zip", ".tar.xz"]]
      missing = [path | path <- required, Set.notMember path inventory]
  unless (null (failures ++ missing ++ excluded)) $
    die (unlines (take 50 (failures ++ map ("Missing page: " ++) missing ++ map ("Unexpected evidence: " ++) excluded)))
  shell <- Text.readFile (site </> "index.html")
  unless ("id=\"thc-pages\"" `Text.isInfixOf` shell &&
          "id=\"thc-content\"" `Text.isInfixOf` shell &&
          "id=\"thc-appearance\"" `Text.isInfixOf` shell) $
    die "Missing site route inventory, content frame, or appearance control"
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
