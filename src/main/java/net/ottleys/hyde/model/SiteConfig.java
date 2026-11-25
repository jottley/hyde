package net.ottleys.hyde.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Site-level configuration parsed from Jekyll's _config.yml.
 */
public class SiteConfig {

    private String title;
    private String description;
    private String author;
    private String baseUrl = "";
    private Map<String, Object> raw = new HashMap<>();

    public SiteConfig() {
        //NOOP
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    public void setRaw(Map<String, Object> raw) {
        this.raw = raw != null ? raw : new HashMap<>();
    }
}
