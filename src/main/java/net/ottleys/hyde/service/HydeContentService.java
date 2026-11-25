package net.ottleys.hyde.service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import jakarta.annotation.PostConstruct;
import net.ottleys.hyde.config.HydeProperties;
import net.ottleys.hyde.model.MarkdownDocument;
import net.ottleys.hyde.model.NavigationNode;
import net.ottleys.hyde.model.SearchResult;
import net.ottleys.hyde.model.SiteConfig;

@Service
public class HydeContentService {

    private static final Logger log = LoggerFactory.getLogger(HydeContentService.class);

    private final HydeProperties properties;
    private final MarkdownService markdownService;

    private SiteConfig siteConfig = new SiteConfig();
    private FileTime configLastModified = null;

    // Cache indexed by canonical Path
    private final Map<Path, CachedEntry> fileCache = new ConcurrentHashMap<>();

    // Secondary index: normalized URL -> Path
    private final Map<String, Path> urlToPathIndex = new ConcurrentHashMap<>();

    public HydeContentService(HydeProperties properties, MarkdownService markdownService) {
        this.properties = properties;
        this.markdownService = markdownService;
    }

    @PostConstruct
    public void init() {
        Path root = properties.getContentDirectoryPath();
        log.info("Initializing Hyde Markdown Service. Content directory: {}", root);
        if (!Files.exists(root)) {
            log.warn("Content directory does not exist at {}. Creating sample content...", root);
            createSampleJekyllSite(root);
        }
        reloadAll();
    }

    /**
     * Scans and loads or refreshes all markdown files and _config.yml.
     */
    public synchronized void reloadAll() {
        Path root = properties.getContentDirectoryPath();
        if (!Files.exists(root)) {
            return;
        }

        // 1. Reload _config.yml if present
        loadSiteConfig(root);

        // 2. Discover markdown files
        Set<Path> existingFiles = new HashSet<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // Skip Jekyll generated dirs, git, build dirs
                    if (dirName.startsWith(".") || dirName.equals("_site") || dirName.equals("target") || dirName.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".md") || name.endsWith(".markdown")) {
                        existingFiles.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan content directory {}: {}", root, e.getMessage(), e);
        }

        // 3. Remove deleted files from cache
        fileCache.keySet().removeIf(path -> !existingFiles.contains(path));
        urlToPathIndex.values().removeIf(path -> !existingFiles.contains(path));

        // 4. Parse or refresh each discovered file
        for (Path file : existingFiles) {
            refreshFile(file, false);
        }

        log.info("Loaded {} Jekyll markdown files from {}", fileCache.size(), root);
    }

    private void loadSiteConfig(Path root) {
        Path configFile = root.resolve("_config.yml");
        if (!Files.exists(configFile)) {
            configFile = root.resolve("_config.yaml");
        }

        if (Files.exists(configFile)) {
            try {
                FileTime modified = Files.getLastModifiedTime(configFile);
                if (configLastModified == null || modified.compareTo(configLastModified) > 0) {
                    String yamlText = Files.readString(configFile);
                    Yaml yaml = new Yaml();
                    Object obj = yaml.load(yamlText);
                    SiteConfig cfg = new SiteConfig();
                    if (obj instanceof Map<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) rawMap;
                        cfg.setRaw(map);
                        if (map.get("title") != null) cfg.setTitle(String.valueOf(map.get("title")));
                        if (map.get("description") != null) cfg.setDescription(String.valueOf(map.get("description")));
                        if (map.get("author") != null) cfg.setAuthor(String.valueOf(map.get("author")));
                        if (map.get("baseurl") != null) cfg.setBaseUrl(String.valueOf(map.get("baseurl")));
                    }
                    this.siteConfig = cfg;
                    this.configLastModified = modified;
                    log.info("Loaded site configuration from {}", configFile.getFileName());
                }
            } catch (IOException | YAMLException e) {
                log.warn("Could not load Jekyll _config.yml: {}", e.getMessage());
            }
        } else {
            SiteConfig cfg = new SiteConfig();
            cfg.setTitle(properties.getSiteTitle());
            cfg.setDescription(properties.getSiteDescription());
            cfg.setAuthor(properties.getDefaultAuthor());
            cfg.setBaseUrl(properties.getBaseUrl());
            this.siteConfig = cfg;
        }
    }

    private MarkdownDocument refreshFile(Path file, boolean force) {
        try {
            if (!Files.exists(file)) {
                fileCache.remove(file);
                urlToPathIndex.values().remove(file);
                return null;
            }

            FileTime modified = Files.getLastModifiedTime(file);
            CachedEntry cached = fileCache.get(file);

            if (!force && properties.isCacheEnabled() && cached != null && cached.lastModified.equals(modified)) {
                return cached.document;
            }

            Path root = properties.getContentDirectoryPath();
            String relative = root.relativize(file).toString().replace('\\', '/');
            String content = Files.readString(file);

            MarkdownDocument doc = markdownService.parseDocument(relative, content, siteConfig);
            doc.setLastModified(modified.toInstant());
            doc.setFileSize(Files.size(file));

            fileCache.put(file, new CachedEntry(doc, modified));

            // Register URL routes
            String canonicalUrl = doc.getUrl();
            urlToPathIndex.put(canonicalUrl, file);

            // If it's a post, also register alternative date-slug routes if needed
            if (doc.isPost()) {
                String filename = file.getFileName().toString();
                String postSlug = HydePathUtils.extractPostSlug(filename);
                urlToPathIndex.put("/posts/" + postSlug, file);

                LocalDate date = doc.getPostDate();
                if (date != null) {
                    // /YYYY/MM/DD/title
                    String titleOnly = filename.replaceFirst("^\\d{4}-\\d{2}-\\d{2}-", "")
                            .replaceAll("\\.(md|markdown)$", "");
                    String jekyllDefault = String.format("/%04d/%02d/%02d/%s",
                            date.getYear(), date.getMonthValue(), date.getDayOfMonth(), titleOnly);
                    urlToPathIndex.put(jekyllDefault, file);
                }
            }

            // Also register direct path matching (e.g. /about.md, /docs/guide.md)
            String rawRelativePath = "/" + relative.replaceAll("\\.(md|markdown)$", "");
            urlToPathIndex.put(rawRelativePath, file);

            return doc;
        } catch (IOException | RuntimeException e) {
            log.error("Failed to parse markdown file {}: {}", file, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Finds a document by requested URL. Automatically checks file modification timestamp
     * and refreshes dynamically at runtime!
     */
    public Optional<MarkdownDocument> getDocumentByUrl(String url) {
        String normalized = HydePathUtils.normalizeUrl(url);

        Path file = urlToPathIndex.get(normalized);
        if (file == null) {
            // Try fallback resolving: e.g. url could be "/about" -> look for "about.md"
            file = tryResolveDirectFile(normalized);
        }

        if (file != null) {
            MarkdownDocument doc = refreshFile(file, false);
            return Optional.ofNullable(doc);
        }

        return Optional.empty();
    }

    private Path tryResolveDirectFile(String normalizedUrl) {
        Path root = properties.getContentDirectoryPath();
        String pathStr = normalizedUrl.startsWith("/") ? normalizedUrl.substring(1) : normalizedUrl;
        if (pathStr.isEmpty()) {
            pathStr = "index";
        }

        // Try pathStr + .md
        Path candidate1 = root.resolve(pathStr + ".md");
        if (Files.exists(candidate1)) return candidate1.toAbsolutePath().normalize();

        // Try pathStr + .markdown
        Path candidate2 = root.resolve(pathStr + ".markdown");
        if (Files.exists(candidate2)) return candidate2.toAbsolutePath().normalize();

        // Try pathStr / index.md
        Path candidate3 = root.resolve(pathStr).resolve("index.md");
        if (Files.exists(candidate3)) return candidate3.toAbsolutePath().normalize();

        // Try _posts / pathStr
        if (!pathStr.startsWith("_posts/")) {
            Path candidate4 = root.resolve("_posts").resolve(pathStr + ".md");
            if (Files.exists(candidate4)) return candidate4.toAbsolutePath().normalize();
        }

        return null;
    }

    /**
     * Returns all Jekyll posts, sorted by date descending.
     */
    public List<MarkdownDocument> getAllPosts() {
        return fileCache.values().stream()
                .map(entry -> refreshFile(entry.document.getRelativePath() != null ?
                        properties.getContentDirectoryPath().resolve(entry.document.getRelativePath()) : null, false))
                .filter(doc -> doc != null && doc.isPost())
                .sorted(Comparator.comparing(MarkdownDocument::getPostDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MarkdownDocument::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * Returns all documents (pages + posts).
     */
    public List<MarkdownDocument> getAllDocuments() {
        return fileCache.values().stream()
                .map(entry -> entry.document)
                .toList();
    }

    /**
     * Returns posts tagged with the given tag.
     */
    public List<MarkdownDocument> getPostsByTag(String tag) {
        if (tag == null || tag.isBlank()) return Collections.emptyList();
        String searchTag = tag.trim().toLowerCase(Locale.ROOT);
        return getAllPosts().stream()
                .filter(doc -> doc.getFrontMatter().getTags().stream()
                        .anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(searchTag)))
                .toList();
    }

    /**
     * Returns posts grouped under the given category.
     */
    public List<MarkdownDocument> getPostsByCategory(String category) {
        if (category == null || category.isBlank()) return Collections.emptyList();
        String searchCategory = category.trim().toLowerCase(Locale.ROOT);
        return getAllPosts().stream()
                .filter(doc -> doc.getFrontMatter().getCategories().stream()
                        .anyMatch(c -> c.toLowerCase(Locale.ROOT).equals(searchCategory)))
                .toList();
    }

    /**
     * Returns all unique tags and their document counts.
     */
    public Map<String, Long> getAllTagsWithCounts() {
        Map<String, Long> map = new HashMap<>();
        for (MarkdownDocument doc : getAllDocuments()) {
            for (String tag : doc.getFrontMatter().getTags()) {
                map.merge(tag, 1L, Long::sum);
            }
        }
        return map;
    }

    /**
     * Returns all unique categories and their document counts.
     */
    public Map<String, Long> getAllCategoriesWithCounts() {
        Map<String, Long> map = new HashMap<>();
        for (MarkdownDocument doc : getAllDocuments()) {
            for (String cat : doc.getFrontMatter().getCategories()) {
                map.merge(cat, 1L, Long::sum);
            }
        }
        return map;
    }

    /**
     * Builds navigation tree representing the content folder hierarchy.
     */
    public NavigationNode getNavigationTree() {
        NavigationNode rootNode = new NavigationNode("Home", "/", "/", true);

        List<MarkdownDocument> docs = new ArrayList<>(getAllDocuments());
        docs.sort(Comparator.comparing(doc -> Objects.requireNonNull(doc).getRelativePath()));

        for (MarkdownDocument doc : docs) {
            String rel = doc.getRelativePath();
            if (rel == null || rel.equalsIgnoreCase("index.md")) {
                continue;
            }
            String[] segments = rel.split("/");
            NavigationNode current = rootNode;

            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                boolean isLast = (i == segments.length - 1);

                if (isLast) {
                    // Document leaf node
                    NavigationNode docNode = new NavigationNode(doc.getTitle(), rel, doc.getUrl(), false);
                    current.addChild(docNode);
                } else {
                    // Folder node
                    NavigationNode folderNode = null;
                    for (NavigationNode child : current.getChildren()) {
                        if (child.isDirectory() && child.getName().equalsIgnoreCase(segment)) {
                            folderNode = child;
                            break;
                        }
                    }
                    if (folderNode == null) {
                        folderNode = new NavigationNode(segment, segment, null, true);
                        current.addChild(folderNode);
                    }
                    current = folderNode;
                }
            }
        }

        return rootNode;
    }

    /**
     * Full-text search across all titles, tags, and markdown content.
     */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()|| query.length() > 100) {
            return Collections.emptyList();
        }

        String q = query.trim().toLowerCase(Locale.ROOT);
        List<SearchResult> results = new ArrayList<>();

        for (MarkdownDocument doc : getAllDocuments()) {
            int score = 0;
            String title = doc.getTitle() != null ? doc.getTitle().toLowerCase(Locale.ROOT) : "";
            String raw = doc.getRawContent() != null ? doc.getRawContent().toLowerCase(Locale.ROOT) : "";

            if (title.contains(q)) {
                score += 50;
            }

            boolean tagMatch = doc.getFrontMatter().getTags().stream()
                    .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(q));
            if (tagMatch) {
                score += 30;
            }

            boolean catMatch = doc.getFrontMatter().getCategories().stream()
                    .anyMatch(c -> c.toLowerCase(Locale.ROOT).contains(q));
            if (catMatch) {
                score += 20;
            }

            int contentMatches = countOccurrences(raw, q);
            if (contentMatches > 0) {
                score += Math.min(30, contentMatches * 5);
            }

            if (score > 0) {
                String snippet = extractSnippet(doc.getRawContent(), query);
                results.add(new SearchResult(doc, snippet, score));
            }
        }

        results.sort(Comparator.comparingInt(SearchResult::score).reversed());
        return results;
    }

    private int countOccurrences(String text, String target) {
        if (text == null || target == null || target.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private String extractSnippet(String content, String query) {
        if (content == null || content.isBlank()) return "";

        // 1. Sanitize layout spacing early to prevent off-by-one errors on indices
        String cleanContent = content.replaceAll("[\\r\\n]+", " ").trim();

        // 2. Safely find the case-insensitive index boundary
        String lowerContent = cleanContent.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int idx = lowerContent.indexOf(lowerQuery);

        // 3. Handle a fallback if no match is found
        if (idx == -1) {
            return cleanContent.length() > 160 ? cleanContent.substring(0, 157) + "..." : cleanContent;
        }

        // 4. Calculate safe extraction window markers
        int start = Math.max(0, idx - 60);

        // Guard against overflow: calculate the window length safely
        int end = Math.min(cleanContent.length(), idx + query.length() + 80);

        // 5. Extract the safe substring window
        String snippet = cleanContent.substring(start, end);

        // 6. Affix text continuation cues elegantly
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < cleanContent.length()) {
            snippet = snippet + "...";
        }

        return snippet;
    }


    /**
     * Resolves and verifies a static asset path in the content folder, preventing path traversal.
     */
    public Optional<Path> resolveAssetFile(String relativeAssetPath) {
        if (relativeAssetPath == null || relativeAssetPath.isBlank()) {
            return Optional.empty();
        }

        // Fast rejection for path traversal sequences and control characters
        if (relativeAssetPath.contains("..") || relativeAssetPath.contains("\0") || relativeAssetPath.contains("\\")) {
            log.warn("Blocked path traversal attempt in asset path: {}", relativeAssetPath);
            return Optional.empty();
        }

        // Check for URL-encoded traversal tokens (e.g. %2e%2e, %2f, %5c)
        try {
            String decoded = URLDecoder.decode(relativeAssetPath, StandardCharsets.UTF_8);
            if (decoded.contains("..") || decoded.contains("\0") || decoded.contains("\\")) {
                log.warn("Blocked encoded path traversal attempt: {}", relativeAssetPath);
                return Optional.empty();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid URL encoding in asset path: {}", relativeAssetPath);
            return Optional.empty();
        }

        Path root = properties.getContentDirectoryPath();
        if (!Files.exists(root)) {
            return Optional.empty();
        }

        try {
            Path realRoot = root.toRealPath();

            String cleanRelative = relativeAssetPath.replace('\\', '/');
            while (cleanRelative.startsWith("/")) {
                cleanRelative = cleanRelative.substring(1);
            }

            Path target = realRoot.resolve(cleanRelative).normalize();

            // 1. Strict lexical boundary check against the content root
            if (!target.startsWith(realRoot)) {
                log.warn("Blocked lexical path traversal attempt: {}", relativeAssetPath);
                return Optional.empty();
            }

            // Fallback: if not found directly and path starts with "images/", check "assets/images/"
            if (!Files.exists(target) && cleanRelative.startsWith("images/")) {
                Path altTarget = realRoot.resolve("assets/" + cleanRelative).normalize();
                if (Files.exists(altTarget)) {
                    target = altTarget;
                }
            }

            // 2. Must exist and be a regular readable file (not directory or special file)
            if (!Files.exists(target) || !Files.isRegularFile(target) || !Files.isReadable(target)) {
                return Optional.empty();
            }

            // 3. Symlink escape check: ensure canonical path is within the real content directory
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                log.warn("Blocked symlink traversal attempt: {} resolves to {}", relativeAssetPath, realTarget);
                return Optional.empty();
            }

            return Optional.of(realTarget);
        } catch (IOException e) {
            log.warn("Error resolving asset file {}: {}", relativeAssetPath, e.getMessage());
            return Optional.empty();
        }
    }

    public SiteConfig getSiteConfig() {
        return siteConfig;
    }

    /**
     * Creates a rich, realistic sample Jekyll site so the user can immediately experience
     * browsing markdown files, posts, documentation, and tags.
     */
    private void createSampleJekyllSite(Path root) {
        try {
            Files.createDirectories(root.resolve("_posts"));
            Files.createDirectories(root.resolve("docs"));
            Files.createDirectories(root.resolve("assets/images"));

            // _config.yml
            Files.writeString(root.resolve("_config.yml"), """
                    title: "My Hyde Knowledge Base"
                    description: "A Jekyll Markdown directory rendered at runtime with Spring Boot"
                    author: "Site Author"
                    baseurl: ""
                    """);

            // index.md
            Files.writeString(root.resolve("index.md"), """
                    ---
                    layout: home
                    title: "Welcome to Hyde: A Jekyll Dynamic Viewer"
                    description: "Dynamic Markdown Viewer rendered at runtime without static build steps."
                    tags: [getting-started, spring-boot, jekyll]
                    ---

                    # Welcome to Hyde: A Jekyll Dynamic Viewer

                    This Spring Boot application reads your directory of markdown files originally designed for **Jekyll**, and presents them dynamically in your browser at runtime.

                    > [!NOTE]
                    > No static HTML files are generated or written to disk. The Markdown and YAML Front Matter are parsed live on demand!

                    ## Key Capabilities

                    * **Dynamic Runtime Parsing**: Edits made to your markdown files on disk are immediately reflected on next browser refresh!
                    * **Jekyll Compatibility**: YAML front matter (`title`, `date`, `tags`, `categories`, `permalink`), `_posts/` folder convention, and Liquid tag preprocessing (`{% highlight %}`, `{% post_url %}`, `{% link %}`, `{{ page.title }}`).
                    * **Table of Contents**: Automatically generated navigation from your headings.
                    * **Full-text Search**: Instant search across all markdown titles, tags, and contents.
                    * **Rich Code Blocks & Tables**: GitHub Flavored Markdown (GFM) tables, task lists, and syntax highlighting.

                    ## Explore the Content

                    * Read the [About Page](about.md)
                    * Check out [Getting Started Guide](docs/getting-started.md)
                    * Explore [Configuration Options](docs/configuration.md)
                    * Browse the latest posts below!
                    """);

            // about.md
            Files.writeString(root.resolve("about.md"), """
                    ---
                    layout: page
                    title: "About This Application"
                    author: "Antigravity Team"
                    description: "Learn why dynamic runtime rendering is beneficial for Markdown and Jekyll archives."
                    tags: [about, architecture]
                    ---

                    # About This Application

                    Static site generators like Jekyll typically require a build step (`jekyll build`) that pre-compiles markdown files into static HTML files.

                    While static generation is great for pure CDNs, runtime parsing offers distinct advantages:

                    1. **Instant Feedback**: Update a `.md` file in your favorite editor, and instantly refresh your browser to view changes.
                    2. **Zero Build Artifact Clutter**: No `_site` directory filling up your disk or git repository.
                    3. **Dynamic Features**: Real-time search, dynamic filtering by tags and categories, and live table of contents.
                    4. **Enterprise Integration**: Easily wrap authentication, permissions, or database integrations around your markdown docs with Spring Security.

                    ### Technology Stack

                    | Component | Technology |
                    | :--- | :--- |
                    | **Framework** | Spring Boot 4.1 (Java 21) |
                    | **Markdown Engine** | CommonMark Java + GFM Extensions |
                    | **Front Matter Parser** | SnakeYAML + CommonMark YAML extension |
                    | **Templating** | Thymeleaf + Modern Responsive Layout |
                    | **Syntax Highlighting** | Prism.js with line numbers and copy buttons |

                    [Back to Home](/)
                    """);

            // _posts/2024-01-15-welcome-to-hyde.md
            Files.writeString(root.resolve("_posts/2024-01-15-welcome-to-hyde.md"), """
                    ---
                    layout: post
                    title: "Welcome to Hyde: A Jekyll Dynamic Viewer"
                    date: 2024-01-15 10:00:00 -0700
                    author: "Alice Developer"
                    categories: [announcements, news]
                    tags: [jekyll, spring-boot, intro]
                    ---

                    Welcome to the first post in our Hyde-powered site!

                    This document demonstrates standard Jekyll post conventions:
                    * Stored in `_posts/YYYY-MM-DD-title.md`
                    * Front matter with `layout`, `title`, `date`, `categories`, and `tags`
                    * Automatic reading time calculation and excerpt generation.

                    <!--more-->

                    ### Liquid Tag Handling

                    In Jekyll, code blocks often use Liquid tags like `{% highlight java %}`:

                    {% highlight java %}
                    package com.example;

                    public class HelloWorld {
                        public static void main(String[] args) {
                            System.out.println("Hello from Hyde: A Jekyll Dynamic Viewer!");
                        }
                    }
                    {% endhighlight %}

                    Our Spring Boot service automatically translates Liquid highlight tags into standard Markdown code fences!

                    ### Internal Links

                    You can link to other posts using Jekyll's `{% post_url %}` tag:
                    Check out [Spring Boot Integration]({% post_url 2024-02-20-spring-boot-and-markdown %})!
                    """);

            // _posts/2024-02-20-spring-boot-and-markdown.md
            Files.writeString(root.resolve("_posts/2024-02-20-spring-boot-and-markdown.md"), """
                    ---
                    layout: post
                    title: "Deep Dive: Dynamic Markdown Processing"
                    date: 2024-02-20 14:30:00 -0700
                    author: "Bob Architect"
                    categories: [engineering]
                    tags: [spring-boot, java, performance]
                    ---

                    How does this application render Markdown files dynamically at runtime without static compilation?

                    Let's break down the architecture:

                    ### Processing Pipeline

                    1. **File Watcher & Timestamp Check**: Files are read from disk. When an HTTP request arrives, the service checks `Files.getLastModifiedTime()`.
                    2. **YAML Front Matter Separation**: SnakeYAML extracts structured metadata into a `FrontMatter` POJO.
                    3. **Liquid Preprocessor**: Translates Jekyll Liquid tags into CommonMark constructs.
                    4. **AST Visitor**: Extracts headings into a Table of Contents (TOC).
                    5. **Link Rewriting**: Resolves relative links (e.g., `[guide](docs/guide.md)`) into browser routes.
                    6. **HtmlRenderer**: Generates safe, clean HTML streamed directly to the Thymeleaf view.

                    ### Task List Example

                    - [x] Configure Spring Boot 4 & Java 21
                    - [x] Parse YAML Front Matter
                    - [x] Implement Liquid tag preprocessor
                    - [x] Add GitHub Flavored Markdown (Tables, Task Lists)
                    - [x] Full-text search and category filtering
                    """);

            // _posts/2024-03-10-advanced-features.md
            Files.writeString(root.resolve("_posts/2024-03-10-advanced-features.md"), """
                    ---
                    layout: post
                    title: "Advanced Jekyll Features: Tables, Badges, and Permalinks"
                    date: 2024-03-10 09:15:00 -0700
                    author: "Alice Developer"
                    categories: [guides]
                    tags: [advanced, gfm, markdown]
                    permalink: /articles/advanced-features/
                    ---

                    This post uses a custom front matter `permalink: /articles/advanced-features/`.

                    Notice how you can access this page either via `/articles/advanced-features/` or via `/posts/2024-03-10-advanced-features`!

                    ### Markdown Table Test

                    | Metric | Static Jekyll | Dynamic Spring Boot |
                    | :--- | :--- | :--- |
                    | **Build Time** | Minutes for large sites | **0 ms (On-demand)** |
                    | **Live Edit** | Requires file watcher rebuild | **Instant browser refresh** |
                    | **Storage** | Duplicates HTML files | **Only Markdown source** |
                    | **Dynamic Search** | Client-side lunr.js index | **Server-side query engine** |

                    ### Code Example in Python

                    ```python
                    import os

                    def list_posts(directory):
                        return [f for f in os.listdir(directory) if f.endswith(".md")]

                    print("Found posts:", list_posts("./_posts"))
                    ```
                    """);

            // docs/getting-started.md
            Files.writeString(root.resolve("docs/getting-started.md"), """
                    ---
                    layout: doc
                    title: "Getting Started Guide"
                    description: "How to run and configure Hyde: A Jekyll Markdown Viewer."
                    tags: [docs, tutorial]
                    ---

                    # Getting Started Guide

                    Learn how to point this Spring Boot application to your own Jekyll repository!

                    ## Step 1: Clone or Configure

                    By default, the application looks for markdown files in `./content`.
                    To point to your existing Jekyll site, you have two options:

                    ### Option A: application.properties / application.yml

                    ```yaml
                    hyde:
                      content-path: "/path/to/my-jekyll-site"
                    ```

                    ### Option B: Environment Variable or CLI Argument

                    ```bash
                    # Via Environment Variable
                    export HYDE_CONTENT_PATH="/path/to/my-jekyll-site"
                    mvn spring-boot:run

                    # Via CLI argument
                    java -jar app.jar --hyde.content-path="/path/to/my-jekyll-site"
                    ```

                    ## Step 2: Accessing the Browser

                    Open your browser to:
                    ```
                    http://localhost:8080/
                    ```

                    You can also view any document's raw Markdown by adding `?raw=true`:
                    * Example: `http://localhost:8080/docs/getting-started?raw=true`
                    """);

            // docs/configuration.md
            Files.writeString(root.resolve("docs/configuration.md"), """
                    ---
                    layout: doc
                    title: "Configuration Reference"
                    description: "Complete list of application configuration properties."
                    tags: [docs, reference, configuration]
                    ---

                    # Configuration Reference

                    All configuration options are defined under the `hyde.*` namespace:

                    | Property | Default | Description |
                    | :--- | :--- | :--- |
                    | `hyde.content-path` | `./content` | Path to the directory containing Jekyll markdown files |
                    | `hyde.site-title` | `"Hyde: A Jekyll Dynamic Viewer"` | Default site title if `_config.yml` is missing |
                    | `hyde.site-description` | `""` | Default site description |
                    | `hyde.default-author` | `"Site Author"` | Default author |
                    | `hyde.cache-enabled` | `true` | Memory cache for parsed AST (refreshed on file change) |
                    | `hyde.excerpt-length` | `220` | Max characters for post summary cards |

                    For more details, visit the [Getting Started Guide](getting-started.md).
                    """);

            // assets/images/sample.svg
            Files.writeString(root.resolve("assets/images/sample.svg"), """
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 100" width="300" height="100">
                      <rect width="300" height="100" rx="10" fill="#2b3137"/>
                      <circle cx="50" cy="50" r="30" fill="#dc3545"/>
                      <text x="95" y="58" font-family="system-ui, sans-serif" font-size="20" font-weight="bold" fill="#ffffff">Jekyll + Spring</text>
                    </svg>
                    """);

            log.info("Successfully created sample Jekyll site in {}", root);
        } catch (IOException e) {
            log.error("Failed to create sample Jekyll site: {}", e.getMessage(), e);
        }
    }

    private record CachedEntry(MarkdownDocument document, FileTime lastModified) {}
}
