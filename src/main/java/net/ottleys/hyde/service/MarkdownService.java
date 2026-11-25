package net.ottleys.hyde.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.image.attributes.ImageAttributesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProviderFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.yaml.snakeyaml.Yaml;

import net.ottleys.hyde.model.FrontMatter;
import net.ottleys.hyde.model.MarkdownDocument;
import net.ottleys.hyde.model.SiteConfig;
import net.ottleys.hyde.model.TocItem;

@Service
public class MarkdownService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownService.class);

    private static final Pattern FRONT_MATTER_PATTERN =
            Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);

    // Liquid tags patterns
    private static final Pattern LIQUID_HIGHLIGHT =
            Pattern.compile("\\{%\\s*highlight\\s+([a-zA-Z0-9_+#-]+)(?:\\s+linenos)?\\s*%\\}(.*?)\\{%\\s*endhighlight\\s*%\\}", Pattern.DOTALL);

    private static final Pattern LIQUID_COMMENT =
            Pattern.compile("\\{%\\s*comment\\s*%\\}.*?\\{%\\s*endcomment\\s*%\\}", Pattern.DOTALL);

    private static final Pattern LIQUID_RAW =
            Pattern.compile("\\{%\\s*raw\\s*%\\}(.*?)\\{%\\s*endraw\\s*%\\}", Pattern.DOTALL);

    private static final Pattern LIQUID_POST_URL =
            Pattern.compile("\\{%\\s*post_url\\s+([a-zA-Z0-9_.-]+)\\s*%\\}");

    private static final Pattern LIQUID_LINK =
            Pattern.compile("\\{%\\s*link\\s+([^%]+?)\\s*%\\}");

    private static final Pattern LIQUID_PAGE_VAR =
            Pattern.compile("\\{\\{\\s*page\\.([a-zA-Z0-9_.-]+)\\s*\\}\\}");

    private static final Pattern LIQUID_SITE_VAR =
            Pattern.compile("\\{\\{\\s*site\\.([a-zA-Z0-9_.-]+)\\s*\\}\\}");

    private final Parser parser;
    private final List<Extension> extensions;

    public MarkdownService() {
        this.extensions = Arrays.asList(
                YamlFrontMatterExtension.create(),
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                HeadingAnchorExtension.create(),
                TaskListItemsExtension.create(),
                ImageAttributesExtension.create()
        );

        this.parser = Parser.builder()
                .extensions(extensions)
                .build();
    }

    /**
     * Parses raw markdown content into a structured MarkdownDocument.
     *
     * @param relativePath Relative path to file (e.g. "_posts/2024-01-15-hello.md")
     * @param fileContent  Full text of the markdown file
     * @param siteConfig   Global site configuration for Liquid variable interpolation
     * @return fully parsed MarkdownDocument
     */
    public MarkdownDocument parseDocument(String relativePath, String fileContent, SiteConfig siteConfig) {
        MarkdownDocument doc = new MarkdownDocument();
        doc.setRelativePath(relativePath);

        // 1. Separate YAML Front Matter from Markdown body
        FrontMatterAndBody split = extractFrontMatter(fileContent);
        FrontMatter frontMatter = split.frontMatter();
        String markdownBody = split.body();

        doc.setFrontMatter(frontMatter);
        doc.setRawContent(markdownBody);

        // 2. Determine post status & date
        boolean isPost = HydePathUtils.isPostFile(relativePath);
        doc.setPost(isPost);

        LocalDate postDate = null;
        if (isPost) {
            String filename = Paths.get(relativePath).getFileName().toString();
            postDate = HydePathUtils.extractPostDate(filename);
        }
        if (postDate == null && frontMatter.getDate() != null) {
            try {
                // Try parsing standard YYYY-MM-DD from front matter date
                String rawDate = frontMatter.getDate().trim();
                if (rawDate.length() >= 10) {
                    postDate = LocalDate.parse(rawDate.substring(0, 10));
                }
            } catch (Exception ignored) {
            }
        }
        doc.setPostDate(postDate);

        // 3. Compute URL (respecting front matter permalink if specified)
        if (frontMatter.getPermalink() != null && !frontMatter.getPermalink().isBlank()) {
            doc.setUrl(HydePathUtils.normalizeUrl(frontMatter.getPermalink()));
        } else {
            doc.setUrl(HydePathUtils.computeCanonicalUrl(relativePath));
        }

        // 4. Preprocess Liquid tags
        String preprocessedMarkdown = preprocessLiquid(markdownBody, frontMatter, siteConfig, relativePath);

        // 5. Parse Markdown AST
        Node ast = parser.parse(preprocessedMarkdown);

        // 6. Extract Table of Contents and Title fallback
        HeadingVisitor headingVisitor = new HeadingVisitor();
        ast.accept(headingVisitor);
        doc.setTableOfContents(headingVisitor.getTocItems());

        // Determine title
        if (frontMatter.getTitle() != null && !frontMatter.getTitle().isBlank()) {
            doc.setTitle(frontMatter.getTitle());
        } else if (headingVisitor.getFirstH1Text() != null) {
            doc.setTitle(headingVisitor.getFirstH1Text());
        } else {
            // Filename fallback
            String filename = Paths.get(relativePath).getFileName().toString();
            String slug = HydePathUtils.extractPostSlug(filename);
            doc.setTitle(formatSlugToTitle(slug));
        }

        // 7. Calculate reading time & excerpt
        doc.setReadingTimeMinutes(calculateReadingTime(markdownBody));
        doc.setExcerpt(generateExcerpt(markdownBody, frontMatter));

        // 8. Render HTML with link rewriting & image path resolution
        HtmlRenderer renderer = createRenderer(relativePath);
        String html = renderer.render(ast);
        doc.setRenderedHtml(html);

        return doc;
    }

    /**
     * Splits YAML front matter from markdown body using SnakeYAML for complete type fidelity.
     */
    public FrontMatterAndBody extractFrontMatter(String content) {
        if (content == null || content.isBlank()) {
            return new FrontMatterAndBody(new FrontMatter(), "");
        }

        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.find()) {
            String yamlContent = matcher.group(1);
            String body = content.substring(matcher.end());
            FrontMatter fm = parseYamlFrontMatter(yamlContent);
            return new FrontMatterAndBody(fm, body);
        }

        return new FrontMatterAndBody(new FrontMatter(), content);
    }

    @SuppressWarnings("unchecked")
    private FrontMatter parseYamlFrontMatter(String yamlContent) {
        FrontMatter fm = new FrontMatter();
        if (yamlContent == null || yamlContent.isBlank()) {
            return fm;
        }

        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(yamlContent);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> raw = (Map<String, Object>) map;
                fm.setAttributes(raw);

                if (raw.get("title") != null) {
                    fm.setTitle(HtmlUtils.htmlUnescape(String.valueOf(raw.get("title"))));
                }
                if (raw.get("date") != null) {
                    Object d = raw.get("date");
                    if (d instanceof java.util.Date dateObj) {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        fm.setDate(sdf.format(dateObj));
                    } else {
                        fm.setDate(String.valueOf(d));
                    }
                }
                if (raw.get("layout") != null) {
                    fm.setLayout(String.valueOf(raw.get("layout")));
                }
                if (raw.get("permalink") != null) {
                    fm.setPermalink(String.valueOf(raw.get("permalink")));
                }
                if (raw.get("author") != null) {
                    fm.setAuthor(String.valueOf(raw.get("author")));
                }
                if (raw.get("description") != null) {
                    fm.setDescription(String.valueOf(raw.get("description")));
                }

                // Parse tags (can be YAML list or comma/space-separated string)
                fm.setTags(extractStringList(raw.get("tags")));

                // Parse categories (can be YAML list or space-separated string)
                List<String> categories = extractStringList(raw.get("categories"));
                if (categories.isEmpty() && raw.get("category") != null) {
                    categories = extractStringList(raw.get("category"));
                }
                fm.setCategories(categories);
            }
        } catch (Exception e) {
            log.warn("Failed to parse YAML front matter: {}", e.getMessage());
        }

        return fm;
    }

    private List<String> extractStringList(Object obj) {
        if (obj == null) return new ArrayList<>();
        if (obj instanceof Collection<?> coll) {
            List<String> list = new ArrayList<>();
            for (Object item : coll) {
                if (item != null) list.add(String.valueOf(item).trim());
            }
            return list;
        }
        if (obj instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return new ArrayList<>();
            String[] parts = trimmed.split("[,\\s]+");
            return Arrays.stream(parts)
                    .map(part -> part == null ? "" : part.trim())
                    .filter(p -> !p.isEmpty())
                    .toList();
        }
        return List.of(String.valueOf(obj));
    }

    /**
     * Preprocesses Jekyll-specific Liquid template tags into standard Markdown or HTML.
     */
    public String preprocessLiquid(String markdown, FrontMatter fm, SiteConfig siteConfig, String relativePath) {
        if (markdown == null) return "";
        String text = markdown;

        // 1. Comments: {% comment %} ... {% endcomment %} -> remove
        text = LIQUID_COMMENT.matcher(text).replaceAll("");

        // 2. Raw blocks: {% raw %} ... {% endraw %} -> content inside
        text = LIQUID_RAW.matcher(text).replaceAll("$1");

        // 3. Highlight blocks: {% highlight java %} ... {% endhighlight %} -> ```java ... ```
        Matcher highlightMatcher = LIQUID_HIGHLIGHT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (highlightMatcher.find()) {
            String lang = highlightMatcher.group(1);
            String code = highlightMatcher.group(2);
            String replacement = "```" + lang + "\n" + code.stripLeading() + (code.endsWith("\n") ? "" : "\n") + "```";
            highlightMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        highlightMatcher.appendTail(sb);
        text = sb.toString();

        // 4. Post URL: {% post_url 2024-01-15-welcome %}
        Matcher postUrlMatcher = LIQUID_POST_URL.matcher(text);
        sb = new StringBuilder();
        while (postUrlMatcher.find()) {
            String postName = postUrlMatcher.group(1);
            String cleanSlug = HydePathUtils.extractPostSlug(postName);
            postUrlMatcher.appendReplacement(sb, Matcher.quoteReplacement("/posts/" + cleanSlug));
        }
        postUrlMatcher.appendTail(sb);
        text = sb.toString();

        // 5. Link tag: {% link docs/guide.md %}
        Matcher linkMatcher = LIQUID_LINK.matcher(text);
        sb = new StringBuilder();
        while (linkMatcher.find()) {
            String linkTarget = linkMatcher.group(1).trim();
            String resolved = HydePathUtils.resolveLink(relativePath, linkTarget);
            linkMatcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        linkMatcher.appendTail(sb);
        text = sb.toString();

        // 6. Page variables: {{ page.title }}, {{ page.author }}, etc.
        Matcher pageVarMatcher = LIQUID_PAGE_VAR.matcher(text);
        sb = new StringBuilder();
        while (pageVarMatcher.find()) {
            String varName = pageVarMatcher.group(1);
            String val = getPageVariable(varName, fm);
            pageVarMatcher.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        pageVarMatcher.appendTail(sb);
        text = sb.toString();

        // 7. Site variables: {{ site.title }}, {{ site.baseurl }}, etc.
        Matcher siteVarMatcher = LIQUID_SITE_VAR.matcher(text);
        sb = new StringBuilder();
        while (siteVarMatcher.find()) {
            String varName = siteVarMatcher.group(1);
            String val = getSiteVariable(varName, siteConfig);
            siteVarMatcher.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        siteVarMatcher.appendTail(sb);
        text = sb.toString();

        return text;
    }

    private String getPageVariable(String varName, FrontMatter fm) {
        if (fm == null) return "";
        return switch (varName.toLowerCase(Locale.ROOT)) {
            case "title" -> fm.getTitle() != null ? fm.getTitle() : "";
            case "date" -> fm.getDate() != null ? fm.getDate() : "";
            case "author" -> fm.getAuthor() != null ? fm.getAuthor() : "";
            case "layout" -> fm.getLayout() != null ? fm.getLayout() : "";
            case "url", "permalink" -> fm.getPermalink() != null ? fm.getPermalink() : "";
            default -> {
                Object obj = fm.getAttribute(varName);
                yield obj != null ? String.valueOf(obj) : "";
            }
        };
    }

    private String getSiteVariable(String varName, SiteConfig siteConfig) {
        if (siteConfig == null) return "";
        return switch (varName.toLowerCase(Locale.ROOT)) {
            case "title" -> siteConfig.getTitle() != null ? siteConfig.getTitle() : "";
            case "description" -> siteConfig.getDescription() != null ? siteConfig.getDescription() : "";
            case "author" -> siteConfig.getAuthor() != null ? siteConfig.getAuthor() : "";
            case "baseurl", "base_url" -> siteConfig.getBaseUrl() != null ? siteConfig.getBaseUrl() : "";
            default -> {
                Object obj = siteConfig.getRaw().get(varName);
                yield obj != null ? String.valueOf(obj) : "";
            }
        };
    }

    /**
     * Builds an HTML renderer with link and image resolution attribute providers.
     */
    private HtmlRenderer createRenderer(String currentDocRelativePath) {
        AttributeProviderFactory attributeProviderFactory = context -> (node, tagName, attributes) -> {
                if (node instanceof Link) {
                    String href = attributes.get("href");
                    if (href != null) {
                        if (href.startsWith("http://") || href.startsWith("https://")) {
                            attributes.put("target", "_blank");
                            attributes.put("rel", "noopener noreferrer");
                        } else {
                            String resolved = HydePathUtils.resolveLink(currentDocRelativePath, href);
                            attributes.put("href", resolved);
                        }
                    }
                } else if (node instanceof Image) {
                    String src = attributes.get("src");
                    if (src != null && !src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("data:")) {
                        // Relative image link
                        String resolvedImage = resolveImageSrc(currentDocRelativePath, src);
                        attributes.put("src", resolvedImage);
                        attributes.put("loading", "lazy");
                        attributes.put("class", "img-fluid rounded");
                    }
                }
        };

        return HtmlRenderer.builder()
                .extensions(extensions)
                .attributeProviderFactory(attributeProviderFactory)
                .build();
    }

    private String resolveImageSrc(String currentDocRelativePath, String src) {
        if (src.startsWith("/")) {
            return "/content-assets" + src;
        }
        Path docParent = Paths.get(currentDocRelativePath).getParent();
        Path resolved;
        if (docParent != null) {
            resolved = docParent.resolve(src).normalize();
        } else {
            resolved = Paths.get(src).normalize();
        }
        String path = resolved.toString().replace('\\', '/');
        return "/content-assets/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    private int calculateReadingTime(String markdown) {
        if (markdown == null || markdown.isBlank()) return 1;
        String[] words = markdown.trim().split("\\s+");
        return Math.max(1, (int) Math.ceil(words.length / 200.0));
    }

    private String generateExcerpt(String markdown, FrontMatter fm) {
        if (fm != null && fm.getDescription() != null && !fm.getDescription().isBlank()) {
            return fm.getDescription();
        }
        if (markdown == null || markdown.isBlank()) return "";

        // Check for Jekyll excerpt separator <!--more-->
        int moreIndex = markdown.indexOf("<!--more-->");
        if (moreIndex < 0) {
            moreIndex = markdown.indexOf("<!-- more -->");
        }
        String source = moreIndex >= 0 ? markdown.substring(0, moreIndex) : markdown;

        // Strip code fences, headers, links, and markdown formatting
        String stripped = source.replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("`.*?`", "")
                .replaceAll("#+\\s*", "")
                .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
                .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                .replaceAll("[*_~`>]", "")
                .replaceAll("<[^>]*>", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (stripped.length() > 220) {
            return stripped.substring(0, 217) + "...";
        }
        return stripped;
    }

    private String formatSlugToTitle(String slug) {
        if (slug == null || slug.isEmpty()) return "Untitled";
        String s = slug.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * AST Visitor for extracting Headings (for Table of Contents and fallback document title).
     */
    private static class HeadingVisitor extends org.commonmark.node.AbstractVisitor {
        private final List<TocItem> tocItems = new ArrayList<>();
        private String firstH1Text = null;

        public List<TocItem> getTocItems() {
            return tocItems;
        }

        public String getFirstH1Text() {
            return firstH1Text;
        }

        @Override
        public void visit(Heading heading) {
            String text = extractText(heading);
            if (heading.getLevel() == 1 && firstH1Text == null) {
                firstH1Text = text;
            }
            if (heading.getLevel() >= 1 && heading.getLevel() <= 4) {
                // Slugify heading text for anchor link
                String anchor = text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s-]", "")
                        .replaceAll("\\s+", "-");
                tocItems.add(new TocItem(heading.getLevel(), text, anchor));
            }
            visitChildren(heading);
        }

        private String extractText(Node node) {
            StringBuilder sb = new StringBuilder();
            Node child = node.getFirstChild();
            while (child != null) {
                if (child instanceof Text textNode) {
                    sb.append(textNode.getLiteral());
                } else {
                    sb.append(extractText(child));
                }
                child = child.getNext();
            }
            return sb.toString().trim();
        }
    }

    public record FrontMatterAndBody(FrontMatter frontMatter, String body) {}
}
