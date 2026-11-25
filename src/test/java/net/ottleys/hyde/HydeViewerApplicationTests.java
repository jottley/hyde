package net.ottleys.hyde;

import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HydeViewerApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET / should render home page markdown dynamically")
    void testGetHomePage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome to Hyde: A Jekyll Dynamic Viewer")))
                .andExpect(content().string(containsString("Recent Posts")));
    }

    @Test
    @DisplayName("GET /about should render about.md markdown")
    void testGetAboutPage() throws Exception {
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("About This Application")))
                .andExpect(content().string(containsString("Technology Stack")));
    }

    @Test
    @DisplayName("GET /about?raw=true should return raw unparsed markdown text")
    void testGetRawMarkdown() throws Exception {
        mockMvc.perform(get("/about?raw=true"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("# About This Application")));
    }

    @Test
    @DisplayName("GET /posts should list published Jekyll posts")
    void testGetPostsPage() throws Exception {
        mockMvc.perform(get("/posts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome to Hyde: A Jekyll Dynamic Viewer")))
                .andExpect(content().string(containsString("Deep Dive: Dynamic Markdown Processing")));
    }

    @Test
    @DisplayName("GET /posts/2024-01-15-welcome-to-hyde should render specific post")
    void testGetPostBySlug() throws Exception {
        mockMvc.perform(get("/posts/2024-01-15-welcome-to-hyde"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome to the first post in our Hyde-powered site!")));
    }

    @Test
    @DisplayName("GET /tags/spring-boot should filter posts by tag")
    void testGetPostsByTag() throws Exception {
        mockMvc.perform(get("/tags/spring-boot"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tag: #spring-boot")));
    }

    @Test
    @DisplayName("GET /search?q=Dynamic should return search results")
    void testSearch() throws Exception {
        mockMvc.perform(get("/search?q=Dynamic"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Found")))
                .andExpect(content().string(containsString("result(s) for")));
    }

    @Test
    @DisplayName("GET /content-assets/assets/images/sample.svg should serve static asset")
    void testServeStaticAsset() throws Exception {
        mockMvc.perform(get("/content-assets/assets/images/sample.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(containsString("<svg")));
    }

    @Test
    @DisplayName("GET /content-assets with path traversal attempts should return 404")
    void testServeAssetPathTraversalBlocked() throws Exception {
        mockMvc.perform(get("/content-assets/../pom.xml"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/content-assets/%2e%2e/pom.xml"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/content-assets/assets/..%2f..%2fpom.xml"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/assets/../_config.yml"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /reload should redirect within the application's context path")
    void testReloadWithContextPath() throws Exception {
        mockMvc.perform(get("/viewer/reload").contextPath("/viewer"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/viewer/?reloaded=true"));
    }

    @Test
    @DisplayName("GET /favicon.ico should return 204 No Content without routing loop")
    void testFavicon() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /non-existent-document should return 404")
    void testNotFound() throws Exception {
        mockMvc.perform(get("/non-existent-document"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("404")))
                .andExpect(content().string(containsString("Document Not Found")));
    }
}
