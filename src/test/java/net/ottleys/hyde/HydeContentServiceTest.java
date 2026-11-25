package net.ottleys.hyde;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.ottleys.hyde.config.HydeProperties;
import net.ottleys.hyde.model.MarkdownDocument;
import net.ottleys.hyde.model.SearchResult;
import net.ottleys.hyde.service.HydeContentService;
import net.ottleys.hyde.service.MarkdownService;

class HydeContentServiceTest {

    @TempDir
    Path tempContentDir;

    private HydeProperties properties;
    private MarkdownService markdownService;
    private HydeContentService contentService;

    @BeforeEach
    void setUp() throws IOException {
        properties = new HydeProperties();
        properties.setContentPath(tempContentDir.toString());
        properties.setCacheEnabled(true);

        markdownService = new MarkdownService();

        // Populate tempContentDir with test files
        Files.createDirectories(tempContentDir.resolve("_posts"));
        Files.createDirectories(tempContentDir.resolve("docs"));
        Files.createDirectories(tempContentDir.resolve("assets/images"));

        // _config.yml
        Files.writeString(tempContentDir.resolve("_config.yml"), """
                title: "Test Hyde Site"
                author: "Test Author"
                """);

        // index.md
        Files.writeString(tempContentDir.resolve("index.md"), """
                ---
                title: "Home Page"
                tags: [home]
                ---
                # Home
                Welcome home.
                """);

        // about.md
        Files.writeString(tempContentDir.resolve("about.md"), """
                ---
                title: "About Us"
                permalink: /custom-about/
                ---
                # About
                All about us.
                """);

        // _posts/2024-01-10-first-post.md
        Files.writeString(tempContentDir.resolve("_posts/2024-01-10-first-post.md"), """
                ---
                title: "First Post"
                date: 2024-01-10
                tags: [java, spring]
                categories: [tech]
                ---
                # First Post
                Content of first post.
                """);

        // _posts/2024-03-15-second-post.md
        Files.writeString(tempContentDir.resolve("_posts/2024-03-15-second-post.md"), """
                ---
                title: "Second Post"
                date: 2024-03-15
                tags: [spring, devops]
                categories: [tech]
                ---
                # Second Post
                Content of second post with secret keyword unicorn.
                """);

        // docs/guide.md
        Files.writeString(tempContentDir.resolve("docs/guide.md"), """
                ---
                title: "User Guide"
                tags: [guide]
                ---
                # Guide
                Guide content.
                """);

        // assets/images/logo.png
        Files.writeString(tempContentDir.resolve("assets/images/logo.png"), "FAKE_PNG_BYTES");

        contentService = new HydeContentService(properties, markdownService);
        contentService.init();
    }

    @Test
    @DisplayName("Should load _config.yml metadata")
    void testConfigLoaded() {
        assertThat(contentService.getSiteConfig().getTitle()).isEqualTo("Test Hyde Site");
        assertThat(contentService.getSiteConfig().getAuthor()).isEqualTo("Test Author");
    }

    @Test
    @DisplayName("Should resolve documents by canonical URL and custom permalink")
    void testUrlResolution() {
        Optional<MarkdownDocument> home = contentService.getDocumentByUrl("/");
        assertThat(home).isPresent();
        assertThat(home.get().getTitle()).isEqualTo("Home Page");

        // Custom permalink from front matter
        Optional<MarkdownDocument> about = contentService.getDocumentByUrl("/custom-about");
        assertThat(about).isPresent();
        assertThat(about.get().getTitle()).isEqualTo("About Us");

        // Post URL
        Optional<MarkdownDocument> post = contentService.getDocumentByUrl("/posts/2024-03-15-second-post");
        assertThat(post).isPresent();
        assertThat(post.get().getTitle()).isEqualTo("Second Post");
    }

    @Test
    @DisplayName("Should sort posts by date descending")
    void testPostsOrdering() {
        List<MarkdownDocument> posts = contentService.getAllPosts();
        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).getTitle()).isEqualTo("Second Post");
        assertThat(posts.get(0).getPostDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(posts.get(1).getTitle()).isEqualTo("First Post");
        assertThat(posts.get(1).getPostDate()).isEqualTo(LocalDate.of(2024, 1, 10));
    }

    @Test
    @DisplayName("Should filter posts by tag and category")
    void testFiltering() {
        List<MarkdownDocument> springPosts = contentService.getPostsByTag("spring");
        assertThat(springPosts).hasSize(2);

        List<MarkdownDocument> javaPosts = contentService.getPostsByTag("java");
        assertThat(javaPosts).hasSize(1);
        assertThat(javaPosts.get(0).getTitle()).isEqualTo("First Post");

        List<MarkdownDocument> techPosts = contentService.getPostsByCategory("tech");
        assertThat(techPosts).hasSize(2);

        Map<String, Long> tagCounts = contentService.getAllTagsWithCounts();
        assertThat(tagCounts.get("spring")).isEqualTo(2L);
        assertThat(tagCounts.get("java")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should execute full-text search and match keywords in content and titles")
    void testSearch() {
        List<SearchResult> results = contentService.search("unicorn");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).document().getTitle()).isEqualTo("Second Post");
        assertThat(results.get(0).matchedSnippet()).contains("unicorn");
    }

    @Test
    @DisplayName("Should dynamically reload document when file is modified on disk")
    void testDynamicLiveReload() throws Exception {
        Path postFile = tempContentDir.resolve("_posts/2024-03-15-second-post.md");

        // Initial check
        Optional<MarkdownDocument> docBefore = contentService.getDocumentByUrl("/posts/2024-03-15-second-post");
        assertThat(docBefore).isPresent();
        assertThat(docBefore.get().getRenderedHtml()).doesNotContain("Dynamically updated at runtime!");

        // Update file on disk (simulate live editing)
        // Sleep slightly to guarantee different filesystem timestamp
        Thread.sleep(50);
        Files.writeString(postFile, """
                ---
                title: "Second Post (Modified)"
                date: 2024-03-15
                tags: [spring]
                ---
                # Second Post
                Dynamically updated at runtime!
                """);

        // Request again without restarting the server
        Optional<MarkdownDocument> docAfter = contentService.getDocumentByUrl("/posts/2024-03-15-second-post");
        assertThat(docAfter).isPresent();
        assertThat(docAfter.get().getTitle()).isEqualTo("Second Post (Modified)");
        assertThat(docAfter.get().getRenderedHtml()).contains("Dynamically updated at runtime!");
    }

    @Test
    @DisplayName("Should resolve asset file safely and reject path traversal attempts")
    void testAssetPathTraversalProtection() {
        // Valid asset
        Optional<Path> validAsset = contentService.resolveAssetFile("assets/images/logo.png");
        assertThat(validAsset).isPresent();
        assertThat(Files.exists(validAsset.get())).isTrue();

        // Path traversal attack attempt
        Optional<Path> illegalAsset1 = contentService.resolveAssetFile("../../etc/passwd");
        assertThat(illegalAsset1).isEmpty();

        Optional<Path> illegalAsset2 = contentService.resolveAssetFile("../pom.xml");
        assertThat(illegalAsset2).isEmpty();

        // Encoded traversal attempt
        Optional<Path> illegalAsset3 = contentService.resolveAssetFile("%2e%2e/pom.xml");
        assertThat(illegalAsset3).isEmpty();

        // Windows-style traversal attempt
        Optional<Path> illegalAsset4 = contentService.resolveAssetFile("..\\..\\pom.xml");
        assertThat(illegalAsset4).isEmpty();

        // Null byte injection attempt
        Optional<Path> illegalAsset5 = contentService.resolveAssetFile("assets/images/logo.png\0.txt");
        assertThat(illegalAsset5).isEmpty();
    }
}
