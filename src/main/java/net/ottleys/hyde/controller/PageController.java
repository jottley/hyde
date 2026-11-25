package net.ottleys.hyde.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.view.RedirectView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ottleys.hyde.model.MarkdownDocument;
import net.ottleys.hyde.model.NavigationNode;
import net.ottleys.hyde.model.SearchResult;
import net.ottleys.hyde.model.SiteConfig;
import net.ottleys.hyde.service.HydeContentService;

@Controller
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".map"
    );

    private final HydeContentService contentService;

    public PageController(HydeContentService contentService) {
        this.contentService = contentService;
    }

    @ModelAttribute("siteConfig")
    public SiteConfig populateSiteConfig() {
        return contentService.getSiteConfig();
    }

    @ModelAttribute("navTree")
    public NavigationNode populateNavTree() {
        return contentService.getNavigationTree();
    }

    @ModelAttribute("recentPosts")
    public List<MarkdownDocument> populateRecentPosts() {
        return contentService.getAllPosts().stream().limit(5).toList();
    }

    @ModelAttribute("allTags")
    public Map<String, Long> populateAllTags() {
        return contentService.getAllTagsWithCounts();
    }

    @ModelAttribute("allCategories")
    public Map<String, Long> populateAllCategories() {
        return contentService.getAllCategoriesWithCounts();
    }

    /**
     * Home page: renders index.md or home dashboard if no index.md is present.
     */
    @GetMapping("/")
    public String index(
            @RequestParam(name = "raw", required = false, defaultValue = "false") boolean raw,
            Model model,
            HttpServletResponse response
    ) throws IOException {
        log.info("Request received for home page '/'");
        Optional<MarkdownDocument> indexDoc = contentService.getDocumentByUrl("/");
        if (indexDoc.isPresent()) {
            MarkdownDocument doc = indexDoc.get();
            log.info("Rendering home document: \"{}\" ({})", doc.getTitle(), doc.getRelativePath());
            if (raw) {
                return streamRawMarkdown(doc, response);
            }
            model.addAttribute("document", doc);
            model.addAttribute("isHome", true);
            model.addAttribute("allPosts", contentService.getAllPosts());
            return "page";
        }

        // Fallback home dashboard if no index.md exists
        log.info("No index.md found, rendering posts dashboard for home page");
        model.addAttribute("isHome", true);
        model.addAttribute("allPosts", contentService.getAllPosts());
        return "posts";
    }

    /**
     * Post listing page.
     */
    @GetMapping("/posts")
    public String listPosts(Model model) {
        model.addAttribute("posts", contentService.getAllPosts());
        model.addAttribute("pageTitle", "All Posts");
        model.addAttribute("pageSubtitle", "Chronological list of all published markdown articles");
        return "posts";
    }

    /**
     * Direct post view by slug.
     */
    @GetMapping("/posts/{*postSlug}")
    public String viewPost(
            @PathVariable String postSlug,
            @RequestParam(name = "raw", required = false, defaultValue = "false") boolean raw,
            Model model,
            HttpServletResponse response
    ) throws IOException {
        String cleanSlug = postSlug.startsWith("/") ? postSlug.substring(1) : postSlug;
        Optional<MarkdownDocument> doc = contentService.getDocumentByUrl("/posts/" + cleanSlug);
        if (doc.isEmpty()) {
            doc = contentService.getDocumentByUrl(cleanSlug);
        }

        if (doc.isPresent()) {
            if (raw) {
                return streamRawMarkdown(doc.get(), response);
            }
            model.addAttribute("document", doc.get());
            return "page";
        }

        model.addAttribute("attemptedUrl", "/posts/" + cleanSlug);
        return "error/404";
    }

    /**
     * Filter posts by tag.
     */
    @GetMapping("/tags/{tag}")
    public String filterByTag(@PathVariable String tag, Model model) {
        List<MarkdownDocument> posts = contentService.getPostsByTag(tag);
        model.addAttribute("posts", posts);
        model.addAttribute("selectedTag", tag);
        model.addAttribute("pageTitle", "Tag: #" + tag);
        model.addAttribute("pageSubtitle", "Articles tagged with \"" + tag + "\" (" + posts.size() + ")");
        return "posts";
    }

    /**
     * Filter posts by category.
     */
    @GetMapping("/categories/{category}")
    public String filterByCategory(@PathVariable String category, Model model) {
        List<MarkdownDocument> posts = contentService.getPostsByCategory(category);
        model.addAttribute("posts", posts);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("pageTitle", "Category: " + category);
        model.addAttribute("pageSubtitle", "Articles filed under \"" + category + "\" (" + posts.size() + ")");
        return "posts";
    }

    /**
     * Full-text search endpoint.
     */
    @GetMapping("/search")
    public String search(@RequestParam(name = "q", required = false) String query, Model model) {
        if (query != null && !query.isBlank()) {
            List<SearchResult> results = contentService.search(query);
            model.addAttribute("results", results);
            model.addAttribute("query", query);
        }
        return "search";
    }

    /**
     * Manual cache reload endpoint.
     */
    @GetMapping("/reload")
    public RedirectView reload() {
        contentService.reloadAll();
        RedirectView redirect = new RedirectView("/?reloaded=true", true);
        // Set Location directly instead of letting sendRedirect expand it using the request host.
        redirect.setStatusCode(HttpStatus.FOUND);
        return redirect;
    }

    /**
     * Explicit favicon endpoint to prevent catch-all routing loops and 404s.
     *
     * favicons are not currently supported, so this endpoint returns no content to prevent routing issues.
     */
    @GetMapping("/favicon.ico")
    @ResponseBody
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }

    /**
     * Catch-all route for any markdown page (e.g. /about, /docs/getting-started, /articles/advanced-features).
     */
    @GetMapping("/{*path}")
    public String dynamicPage(
            @PathVariable String path,
            @RequestParam(name = "raw", required = false, defaultValue = "false") boolean raw,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String cleanPath = path.startsWith("/") ? path : "/" + path;

        // Skip static file extensions that were not resolved by static handlers
        for (String ext : STATIC_EXTENSIONS) {
            if (cleanPath.toLowerCase().endsWith(ext)) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return null;
            }
        }

        log.debug("Resolving markdown page for path: {}", cleanPath);

        // Check if document exists
        Optional<MarkdownDocument> docOpt = contentService.getDocumentByUrl(cleanPath);
        if (docOpt.isEmpty()) {
            docOpt = contentService.getDocumentByUrl(cleanPath.substring(1));
        }

        if (docOpt.isPresent()) {
            MarkdownDocument doc = docOpt.get();
            log.info("Serving dynamic markdown: {} -> \"{}\"", cleanPath, doc.getTitle());
            if (raw) {
                return streamRawMarkdown(doc, response);
            }
            model.addAttribute("document", doc);
            return "page";
        }

        // If not found, show 404
        log.warn("Document not found for path: {}", cleanPath);
        model.addAttribute("attemptedUrl", cleanPath);
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "error/404";
    }

    private String streamRawMarkdown(MarkdownDocument doc, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(doc.getRawContent() != null ? doc.getRawContent() : "");
        response.getWriter().flush();
        return null;
    }
}
