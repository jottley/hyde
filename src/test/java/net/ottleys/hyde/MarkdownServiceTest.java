package net.ottleys.hyde;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.ottleys.hyde.model.FrontMatter;
import net.ottleys.hyde.model.MarkdownDocument;
import net.ottleys.hyde.model.SiteConfig;
import net.ottleys.hyde.service.MarkdownService;

class MarkdownServiceTest {

    private MarkdownService markdownService;
    private SiteConfig siteConfig;

    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
        siteConfig = new SiteConfig();
        siteConfig.setTitle("Test Site");
        siteConfig.setAuthor("Test Author");
        siteConfig.setBaseUrl("/test");
    }

    @Test
    @DisplayName("Should parse YAML Front Matter and body accurately")
    void testParseYamlFrontMatter() {
        String content = """
                ---
                layout: post
                title: "My Awesome Post"
                date: 2024-05-10
                author: "Jane Doe"
                categories: [updates, tech]
                tags: [spring, markdown]
                permalink: /my-post/
                customField: "Custom Value"
                ---
                # Heading 1
                This is the post content.
                """;

        MarkdownService.FrontMatterAndBody split = markdownService.extractFrontMatter(content);
        FrontMatter fm = split.frontMatter();

        assertThat(fm.getTitle()).isEqualTo("My Awesome Post");
        assertThat(fm.getDate()).isEqualTo("2024-05-10");
        assertThat(fm.getAuthor()).isEqualTo("Jane Doe");
        assertThat(fm.getPermalink()).isEqualTo("/my-post/");
        assertThat(fm.getCategories()).containsExactly("updates", "tech");
        assertThat(fm.getTags()).containsExactly("spring", "markdown");
        assertThat(fm.getAttribute("customField")).isEqualTo("Custom Value");

        assertThat(split.body().trim()).isEqualTo("""
                # Heading 1
                This is the post content.""".trim());
    }

    @Test
    @DisplayName("Should preprocess Jekyll Liquid tags (highlight, post_url, variables, comments)")
    void testPreprocessLiquidTags() {
        FrontMatter fm = new FrontMatter();
        fm.setTitle("Page Title");
        fm.setAuthor("Alice");

        String input = """
                {% comment %}
                This is a hidden comment
                {% endcomment %}
                Hello from {{ page.author }} on {{ site.title }}!

                Check out {% post_url 2024-01-15-welcome %}.

                {% highlight java %}
                System.out.println("Hello");
                {% endhighlight %}
                """;

        String processed = markdownService.preprocessLiquid(input, fm, siteConfig, "page.md");

        assertThat(processed).doesNotContain("This is a hidden comment");
        assertThat(processed).contains("Hello from Alice on Test Site!");
        assertThat(processed).contains("/posts/2024-01-15-welcome");
        assertThat(processed).contains("```java\nSystem.out.println(\"Hello\");\n```");
    }

    @Test
    @DisplayName("Should extract Table of Contents from headings")
    void testExtractTableOfContents() {
        String content = """
                ---
                title: "TOC Test"
                ---
                # Introduction
                Some intro text.
                ## Getting Started
                Steps to start.
                ### Configuration
                Details on config.
                ## Conclusion
                Final thoughts.
                """;

        MarkdownDocument doc = markdownService.parseDocument("test.md", content, siteConfig);

        assertThat(doc.getTableOfContents()).hasSize(4);
        assertThat(doc.getTableOfContents().get(0).text()).isEqualTo("Introduction");
        assertThat(doc.getTableOfContents().get(0).level()).isEqualTo(1);
        assertThat(doc.getTableOfContents().get(1).text()).isEqualTo("Getting Started");
        assertThat(doc.getTableOfContents().get(1).level()).isEqualTo(2);
        assertThat(doc.getTableOfContents().get(2).text()).isEqualTo("Configuration");
        assertThat(doc.getTableOfContents().get(3).text()).isEqualTo("Conclusion");
    }

    @Test
    @DisplayName("Should rewrite relative links to application routes")
    void testLinkRewriting() {
        String content = """
                ---
                title: "Links Test"
                ---
                [About Page](about.md)
                [Deep Guide](docs/guide.md)
                [Post Link](_posts/2024-01-15-welcome.md)
                [External Link](https://spring.io)
                """;

        MarkdownDocument doc = markdownService.parseDocument("index.md", content, siteConfig);

        assertThat(doc.getRenderedHtml()).contains("href=\"/about\"");
        assertThat(doc.getRenderedHtml()).contains("href=\"/docs/guide\"");
        assertThat(doc.getRenderedHtml()).contains("href=\"/posts/2024-01-15-welcome\"");
        assertThat(doc.getRenderedHtml()).contains("href=\"https://spring.io\"");
        assertThat(doc.getRenderedHtml()).contains("target=\"_blank\"");
    }

    @Test
    @DisplayName("Should render GFM tables and task lists")
    void testGfmTablesAndTaskLists() {
        String content = """
                | Header A | Header B |
                | :--- | :--- |
                | Value 1 | Value 2 |

                - [x] Done item
                - [ ] Todo item
                """;

        MarkdownDocument doc = markdownService.parseDocument("doc.md", content, siteConfig);

        assertThat(doc.getRenderedHtml()).contains("<table>");
        assertThat(doc.getRenderedHtml()).contains("Header A</th>");
        assertThat(doc.getRenderedHtml()).contains("Value 1</td>");
        assertThat(doc.getRenderedHtml()).contains("type=\"checkbox\"");
    }
}
