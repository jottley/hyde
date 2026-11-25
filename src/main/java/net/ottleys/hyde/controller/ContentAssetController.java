package net.ottleys.hyde.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import net.ottleys.hyde.service.HydeContentService;

@RestController
public class ContentAssetController {

    private static final Logger log = LoggerFactory.getLogger(ContentAssetController.class);

    private final HydeContentService contentService;

    public ContentAssetController(HydeContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * Serves content assets (images, documents, styles) stored directly inside the Jekyll content directory.
     * Examples: /content-assets/assets/images/sample.svg or /assets/images/sample.svg
     */
    @GetMapping({"/content-assets/{*assetPath}", "/assets/{*assetPath}", "/images/{*assetPath}"})
    public ResponseEntity<Resource> serveAsset(@PathVariable String assetPath, HttpServletRequest request) {
        String rawUri = request.getRequestURI();
        if (rawUri == null || rawUri.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Fast rejection for path traversal or control characters in raw URI
        if (rawUri.contains("..") || rawUri.contains("\0") || rawUri.contains("\\")) {
            log.warn("Blocked path traversal attempt in asset URI: {}", rawUri);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Remove matrix parameters (e.g. ;jsessionid=...)
        String uri = rawUri;
        int semicolonIndex = uri.indexOf(';');
        if (semicolonIndex >= 0) {
            uri = uri.substring(0, semicolonIndex);
        }

        // Check decoded URI for percent-encoded path traversal attacks
        String decodedUri;
        try {
            decodedUri = URLDecoder.decode(uri, StandardCharsets.UTF_8);
            if (decodedUri.contains("..") || decodedUri.contains("\0") || decodedUri.contains("\\")) {
                log.warn("Blocked encoded path traversal attempt in asset URI: {}", rawUri);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid URL encoding in asset URI: {}", rawUri);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // Extract relative asset path using the decoded URI
        String relativePath;
        if (decodedUri.startsWith("/content-assets/")) {
            relativePath = decodedUri.substring("/content-assets/".length());
        } else if (decodedUri.startsWith("/")) {
            relativePath = decodedUri.substring(1);
        } else {
            relativePath = decodedUri;
        }

        while (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        relativePath = relativePath.trim();

        if (relativePath.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Optional<Path> resolvedPath = contentService.resolveAssetFile(relativePath);
        if (resolvedPath.isEmpty()) {
            log.warn("Asset not found or access denied: {}", relativePath);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Path file = resolvedPath.get();
        Resource resource = new FileSystemResource(file);

        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        log.info("Serving asset: {}", relativePath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(resource);
    }
}
