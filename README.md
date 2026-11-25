# Hyde: A Jekyll Dynamic Markdown Viewer

A Spring Boot application that reads a directory of markdown files originally designed for the **Jekyll static site generator** and dynamically renders them in the browser at runtime.

**No static HTML files are generated or stored on disk.** All Markdown documents and YAML front matter metadata are parsed and streamed live on demand.

---

## Key Features

1. **Pure Runtime Parsing (No Static Generation)**
   * Parses Markdown files and YAML Front Matter dynamically when requested by a browser.
   * Edit any `.md` file in your favorite editor, hit **Refresh** in your browser, and see the update immediately.
   * Built-in file modification timestamp checking ensures high performance while maintaining instant live reload.

2. **Jekyll Conventions & Compatibility**
   * **YAML Front Matter**: Extracts `title`, `date`, `author`, `layout`, `tags`, `categories`, `permalink`, and arbitrary custom attributes.
   * **`_posts/` Convention**: Automatically discovers and dates posts formatted as `YYYY-MM-DD-title.md`.
   * **Permalinks**: Honors custom front matter `permalink: /my-custom-url/` as well as standard Jekyll paths.
   * **Liquid Preprocessor**: Automatically translates common Jekyll Liquid tags:
     * `{% highlight <lang> %} ... {% endhighlight %}` &rarr; Standard fenced code blocks.
     * `{% post_url YYYY-MM-DD-title %}` &rarr; Canonical post routes.
     * `{% link path/to/doc.md %}` &rarr; Resolved application routes.
     * `{{ page.property }}` and `{{ site.property }}` &rarr; Variable interpolation.
     * `{% comment %} ... {% endcomment %}` &rarr; Cleanly stripped out.
   * **Internal Link Rewriting**: Markdown links like `[Guide](docs/guide.md)` or `[Post](_posts/2024-01-15-welcome.md)` are automatically rewritten into active web application routes.

3. **Modern, Responsive Web UI**
   * **Clean Documentation Layout**: Built with Bootstrap 5.3, supporting both **Light and Dark themes** with automatic persistence.
   * **Table of Contents (TOC)**: Auto-generated heading navigation with smooth scroll-spy.
   * **Full-text Search**: Real-time search across titles, tags, categories, and article content with snippet highlighting.
   * **Category & Tag Browsing**: Filter posts by clicking any tag or category badge.
   * **Syntax Highlighting & Copy**: Prism.js syntax highlighting with one-click code copy button.
   * **Raw Markdown View**: Add `?raw=true` to any URL or click the "Raw Markdown" button to inspect unparsed Markdown source.
   * **Directory Tree Navigation**: Collapsible sidebar tree representing folder structure.

4. **Security & Asset Serving**
   * Safe static asset serving for images and attachments with strict path traversal prevention (`../` boundary checks).

---

## Quick Start

### 1. Prerequisites
* **Java 21** or later
* **Maven 3.8+** (or use system maven)

### 2. Run the Application
The project includes a ready-to-use sample Hyde site in `./content`:

```bash
mvn spring-boot:run
```

Or build the executable jar:
```bash
mvn package -DskipTests
java -jar target/hyde-1.0.0-SNAPSHOT.jar
```

Open your browser to:
```
http://localhost:8080/
```

---

## Pointing to Your Own Jekyll Directory

To point this viewer to your existing Jekyll markdown repository, use any of the following approaches:

### Option A: Via Command Line Argument
```bash
java -jar target/hyde-1.0.0-SNAPSHOT.jar --hyde.content-path="/path/to/my-jekyll-site"
```

### Option B: Via Environment Variable
```bash
export JEKYLL_CONTENT_PATH="/path/to/my-jekyll-site"
mvn spring-boot:run
```

### Option C: Via `application.yml`
Edit `src/main/resources/application.yml`:
```yaml
jekyll:
  content-path: "/path/to/my-jekyll-site"
  site-title: "My Custom Knowledge Base"
  cache-enabled: true
```

---

## Configuration Properties Reference

| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `hyde.content-path` | `HYDE_CONTENT_PATH` | `./content` | Path to your Jekyll directory |
| `hyde.site-title` | `HYDE_SITE_TITLE` | `Hyde: A Jekyll Dynamic Viewer` | Fallback title if `_config.yml` is missing |
| `hyde.site-description` | `HYDE_SITE_DESCRIPTION` | `Dynamic Markdown Viewer` | Fallback description |
| `hyde.default-author` | `HYDE_DEFAULT_AUTHOR` | `Site Author` | Fallback author |
| `hyde.cache-enabled` | `HYDE_CACHE_ENABLED` | `true` | Memory cache (auto-checks last-modified timestamp) |
| `hyde.excerpt-length` | `HYDE_EXCERPT_LENGTH` | `220` | Max characters for post summary cards |

---

## Supported URL Patterns

* `/` &mdash; Renders `index.md` (or recent posts if no index is present)
* `/posts` &mdash; Chronological listing of all posts in `_posts/`
* `/posts/{slug}` &mdash; Renders post (e.g. `/posts/2024-01-15-welcome-to-jekyll`)
* `/docs/{file}` &mdash; Renders nested markdown docs (e.g. `/docs/getting-started`)
* `/{page}` &mdash; Renders root markdown page (e.g. `/about`)
* `/{custom-permalink}` &mdash; Renders pages with front matter `permalink: /custom-path/`
* `/tags/{tag}` &mdash; Filter posts by tag (e.g. `/tags/spring-boot`)
* `/categories/{category}` &mdash; Filter posts by category (e.g. `/categories/engineering`)
* `/search?q={query}` &mdash; Full-text search across titles and markdown text
* `/{url}?raw=true` &mdash; View raw Markdown text directly in browser
* `/reload` &mdash; Force-refreshes all discovered files and cache, then returns a 302 redirect to the application's home page (`/?reloaded=true`, prefixed by any servlet context path). The `Location` header stays origin-relative so request host headers cannot select an external redirect destination.
* `/content-assets/**` or `/assets/**` &mdash; Directly serves images, SVG, and attachments

---

## Project Structure

```
├── content/                              # Sample Jekyll site directory
│   ├── _config.yml                       # Hyde site configuration
│   ├── index.md                          # Home page
│   ├── about.md                          # About page
│   ├── _posts/                           # Jekyll post files
│   │   ├── 2024-01-15-welcome-to-hyde.md
│   │   ├── 2024-02-20-spring-boot-and-markdown.md
│   │   └── 2024-03-10-advanced-features.md
│   ├── docs/                             # Documentation subfolder
│   │   ├── getting-started.md
│   │   └── configuration.md
│   └── assets/images/                    # Static assets (images, SVGs)
│
├── src/main/java/net/ottleys/hyde/
│   ├── HydeApplication.java      # Spring Boot Application entrypoint
│   ├── config/
│   │   └── HydeProperties.java         # Type-safe configuration properties
│   ├── model/
│   │   ├── FrontMatter.java              # Parsed YAML metadata
│   │   ├── MarkdownDocument.java         # In-memory document model
│   │   ├── NavigationNode.java           # Hierarchy tree node
│   │   ├── SearchResult.java             # Search result with snippet
│   │   ├── SiteConfig.java               # Config from _config.yml
│   │   └── TocItem.java                  # Heading Table of Contents item
│   ├── service/
│   │   ├── HydeContentService.java     # File discovery, caching, search
│   │   ├── HydePathUtils.java          # URL normalization and link rewriting
│   │   └── MarkdownService.java          # CommonMark + Liquid + Front Matter parser
│   └── controller/
│       ├── PageController.java           # Dynamic page routing & rendering
│       └── ContentAssetController.java   # Safe static asset serving
│
└── src/main/resources/
    ├── application.yml
    └── templates/                        # Thymeleaf modern layouts
        ├── layout.html                   # Master layout, navbar, sidebar, theme
        ├── page.html                     # Rendered article + TOC
        ├── posts.html                    # Blog post list view
        ├── search.html                   # Search results view
        └── error/404.html                # Friendly 404 handler
```

---

## Running Automated Tests

```bash
mvn test
```

All 21 unit and integration tests verify:
* YAML front matter extraction and custom attribute parsing
* Liquid tag preprocessing (`{% highlight %}`, `{% post_url %}`, `{% link %}`)
* CommonMark GitHub Flavored Markdown (GFM tables, task list checkboxes)
* Relative link rewriting & image path resolution
* Date ordering of posts
* In-memory full-text search
* Dynamic live reload on file modification
* Static asset serving and directory traversal attack prevention (`../../`)
