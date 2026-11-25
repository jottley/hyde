package net.ottleys.hyde.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hyde")
public class HydeProperties {

    /**
     * Path to the Jekyll site root directory.
     * Can be absolute or relative to the working directory.
     */
    private String contentPath = "./content";

    /**
     * Default site title used when _config.yml does not specify one.
     */
    private String siteTitle = "Hyde: A Jekyll Dynamic Viewer";

    /**
     * Default site subtitle / description.
     */
    private String siteDescription = "Hyde: A Dynamic runtime markdown viewer for Jekyll sites in Spring Boot";

    /**
     * Default site author.
     */
    private String defaultAuthor = "Site Author";

    /**
     * Base URL for the site (default is empty for root).
     */
    private String baseUrl = "";

    /**
     * Whether to cache parsed markdown documents in memory.
     * Even with caching enabled, files are checked for last-modified changes dynamically.
     */
    private boolean cacheEnabled = true;

    /**
     * Maximum excerpt character length for post cards.
     */
    private int excerptLength = 220;

    public String getContentPath() {
        return contentPath;
    }

    public void setContentPath(String contentPath) {
        this.contentPath = contentPath;
    }

    public Path getContentDirectoryPath() {
        return Paths.get(contentPath).toAbsolutePath().normalize();
    }

    public String getSiteTitle() {
        return siteTitle;
    }

    public void setSiteTitle(String siteTitle) {
        this.siteTitle = siteTitle;
    }

    public String getSiteDescription() {
        return siteDescription;
    }

    public void setSiteDescription(String siteDescription) {
        this.siteDescription = siteDescription;
    }

    public String getDefaultAuthor() {
        return defaultAuthor;
    }

    public void setDefaultAuthor(String defaultAuthor) {
        this.defaultAuthor = defaultAuthor;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public int getExcerptLength() {
        return excerptLength;
    }

    public void setExcerptLength(int excerptLength) {
        this.excerptLength = excerptLength;
    }
}
