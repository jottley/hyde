package net.ottleys.hyde.service;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility functions for resolving Jekyll paths, filenames, slugs, and dates.
 */
public final class HydePathUtils {
    private static final Logger log = LoggerFactory.getLogger(HydePathUtils.class);

    // Matches Jekyll post file naming convention: YYYY-MM-DD-title.md
    private static final Pattern POST_FILENAME_PATTERN =
            Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})-(.+)\\.(md|markdown)$", Pattern.CASE_INSENSITIVE);

    private HydePathUtils() {
    }

    /**
     * Determines whether a relative file path belongs to Jekyll posts.
     */
    public static boolean isPostFile(String relativePath) {
        if (relativePath == null) return false;
        String normalized = relativePath.replace('\\', '/');
        return normalized.startsWith("_posts/") || normalized.contains("/_posts/");
    }

    /**
     * Extracts date from post filename (e.g. 2024-03-15-hello-world.md).
     */
    public static LocalDate extractPostDate(String filename) {
        if (filename == null) return null;
        Matcher matcher = POST_FILENAME_PATTERN.matcher(filename);
        if (matcher.matches()) {
            try {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                return LocalDate.of(year, month, day);
            } catch (NumberFormatException | DateTimeException exception) {
                log.warn("Failed to extract post date from filename {}: {}", filename, exception.getMessage());
            }
        }
        return null;
    }

    /**
     * Extracts slug from post filename without extension (e.g. "2024-03-15-hello-world").
     */
    public static String extractPostSlug(String filename) {
        if (filename == null) return "";
        return filename.replaceAll("\\.(md|markdown)$", "");
    }

    /**
     * Computes the canonical browser URL for a given relative path.
     * Examples:
     * - "index.md" -> "/"
     * - "about.md" -> "/about"
     * - "docs/getting-started.md" -> "/docs/getting-started"
     * - "_posts/2024-03-15-welcome.md" -> "/posts/2024-03-15-welcome"
     */
    public static String computeCanonicalUrl(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "/";
        }
        String p = relativePath.replace('\\', '/');

        // Handle posts
        if (p.startsWith("_posts/")) {
            String filename = p.substring("_posts/".length());
            String slug = extractPostSlug(filename);
            return "/posts/" + slug;
        }

        // Handle root index
        if (p.equalsIgnoreCase("index.md") || p.equalsIgnoreCase("index.markdown")) {
            return "/";
        }

        // Handle subfolder index (e.g. "docs/index.md" -> "/docs")
        if (p.endsWith("/index.md") || p.endsWith("/index.markdown")) {
            String dir = p.substring(0, p.lastIndexOf("/index."));
            return dir.startsWith("/") ? dir : "/" + dir;
        }

        // Handle normal page
        String url = p.replaceAll("\\.(md|markdown)$", "");
        return url.startsWith("/") ? url : "/" + url;
    }

    /**
     * Normalizes a URL path for lookup (removes leading/trailing slashes, lowercase, etc.).
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank() || url.equals("/")) {
            return "/";
        }
        String cleaned = url.trim();
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        if (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    /**
     * Resolves a relative link from the current document context to a canonical web URL.
     */
    public static String resolveLink(String currentDocRelativePath, String linkHref) {
        if (linkHref == null || linkHref.isBlank()) {
            return "#";
        }

        // Keep anchor or external URLs intact
        if (linkHref.startsWith("#") ||
                linkHref.startsWith("http://") ||
                linkHref.startsWith("https://") ||
                linkHref.startsWith("mailto:") ||
                linkHref.startsWith("tel:")) {
            return linkHref;
        }

        // Clean query/fragment if needed
        String fragment = "";
        int hashIdx = linkHref.indexOf('#');
        String pathPart = linkHref;
        if (hashIdx >= 0) {
            fragment = linkHref.substring(hashIdx);
            pathPart = linkHref.substring(0, hashIdx);
        }

        if (pathPart.isEmpty()) {
            return fragment;
        }

        // If it refers to _posts/
        if (pathPart.contains("_posts/")) {
            int idx = pathPart.indexOf("_posts/");
            String postFile = pathPart.substring(idx + "_posts/".length());
            return "/posts/" + extractPostSlug(postFile) + fragment;
        }

        // If absolute from site root
        if (pathPart.startsWith("/")) {
            String cleaned = pathPart.replaceAll("\\.(md|markdown|html)$", "");
            if (cleaned.isEmpty()) cleaned = "/";
            return cleaned + fragment;
        }

        // If relative to current doc's directory
        Path currentDir = Paths.get(currentDocRelativePath).getParent();
        Path resolvedPath;
        if (currentDir != null) {
            resolvedPath = currentDir.resolve(pathPart).normalize();
        } else {
            resolvedPath = Paths.get(pathPart).normalize();
        }

        String targetStr = resolvedPath.toString().replace('\\', '/');
        // If it resolved back to posts
        if (targetStr.startsWith("_posts/")) {
            String filename = targetStr.substring("_posts/".length());
            return "/posts/" + extractPostSlug(filename) + fragment;
        }

        String finalUrl = targetStr.replaceAll("\\.(md|markdown|html)$", "");
        if (finalUrl.isEmpty() || finalUrl.equals("index")) {
            finalUrl = "/";
        } else if (!finalUrl.startsWith("/")) {
            finalUrl = "/" + finalUrl;
        }

        return finalUrl + fragment;
    }
}
