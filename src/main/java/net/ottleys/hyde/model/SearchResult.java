package net.ottleys.hyde.model;

/**
 * Result item for search queries.
 */
public record SearchResult(
        MarkdownDocument document,
        String matchedSnippet,
        int score
) {
}
