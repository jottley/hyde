package net.ottleys.hyde.model;

/**
 * Represents an entry in the Table of Contents extracted from markdown headings.
 */
public record TocItem(int level, String text, String anchor) {
}
