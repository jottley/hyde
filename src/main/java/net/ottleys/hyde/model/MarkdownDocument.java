package net.ottleys.hyde.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory representation of a parsed Jekyll Markdown document.
 */
public class MarkdownDocument {

    private String relativePath;
    private String url;
    private String title;
    private FrontMatter frontMatter = new FrontMatter();
    private String rawContent;
    private String renderedHtml;
    private String excerpt;
    private boolean post;
    private LocalDate postDate;
    private int readingTimeMinutes = 1;
    private List<TocItem> tableOfContents = new ArrayList<>();
    private Instant lastModified;
    private long fileSize;

    public MarkdownDocument() {
        //NOOP
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public FrontMatter getFrontMatter() {
        return frontMatter;
    }

    public void setFrontMatter(FrontMatter frontMatter) {
        this.frontMatter = frontMatter != null ? frontMatter : new FrontMatter();
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getRenderedHtml() {
        return renderedHtml;
    }

    public void setRenderedHtml(String renderedHtml) {
        this.renderedHtml = renderedHtml;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public boolean isPost() {
        return post;
    }

    public void setPost(boolean post) {
        this.post = post;
    }

    public LocalDate getPostDate() {
        return postDate;
    }

    public void setPostDate(LocalDate postDate) {
        this.postDate = postDate;
    }

    public int getReadingTimeMinutes() {
        return readingTimeMinutes;
    }

    public void setReadingTimeMinutes(int readingTimeMinutes) {
        this.readingTimeMinutes = Math.max(1, readingTimeMinutes);
    }

    public List<TocItem> getTableOfContents() {
        return tableOfContents;
    }

    public void setTableOfContents(List<TocItem> tableOfContents) {
        this.tableOfContents = tableOfContents != null ? tableOfContents : new ArrayList<>();
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
}
