package net.ottleys.hyde.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the YAML front matter parsed from the top of a Jekyll Markdown file.
 */
public class FrontMatter {

    private String title;
    private String date;
    private String layout;
    private String permalink;
    private String author;
    private String description;
    private List<String> tags = new ArrayList<>();
    private List<String> categories = new ArrayList<>();
    private Map<String, Object> attributes = new HashMap<>();

    public FrontMatter() {
        //NOOP
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getLayout() {
        return layout;
    }

    public void setLayout(String layout) {
        this.layout = layout;
    }

    public String getPermalink() {
        return permalink;
    }

    public void setPermalink(String permalink) {
        this.permalink = permalink;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories != null ? categories : new ArrayList<>();
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public String toString() {
        return "FrontMatter{" +
                "title='" + title + '\'' +
                ", date='" + date + '\'' +
                ", layout='" + layout + '\'' +
                ", permalink='" + permalink + '\'' +
                ", author='" + author + '\'' +
                ", tags=" + tags +
                ", categories=" + categories +
                '}';
    }
}
