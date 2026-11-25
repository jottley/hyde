package net.ottleys.hyde.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Node in the navigation tree representing a folder or a markdown document.
 */
public class NavigationNode {

    private String name;
    private String path;
    private String url;
    private boolean directory;
    private List<NavigationNode> children = new ArrayList<>();

    public NavigationNode() {
    }

    public NavigationNode(String name, String path, String url, boolean directory) {
        this.name = name;
        this.path = path;
        this.url = url;
        this.directory = directory;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    public List<NavigationNode> getChildren() {
        return children;
    }

    public void setChildren(List<NavigationNode> children) {
        this.children = children != null ? children : new ArrayList<>();
    }

    public void addChild(NavigationNode child) {
        this.children.add(child);
    }
}
