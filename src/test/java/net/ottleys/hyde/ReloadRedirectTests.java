package net.ottleys.hyde;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import net.ottleys.hyde.service.HydeContentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReloadRedirectTests {

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private HydeContentService contentService;

    @BeforeEach
    void clearStartupInvocations() {
        clearInvocations(contentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Host: localhost",
            "Host: 5962869729472723353.owasp.org",
            "Host: localhost\r\nForwarded: host=5962869729472723353.owasp.org;proto=https",
            "Host: localhost\r\nX-Forwarded-Host: 5962869729472723353.owasp.org\r\n"
                    + "X-Forwarded-Proto: https\r\nX-Forwarded-Port: 443"
    })
    void reloadKeepsLocationRelativeRegardlessOfRequestHeaders(String requestHeaders) throws Exception {
        // Use real HTTP: MockMvc does not perform Tomcat's sendRedirect URL expansion.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            String request = "GET /reload?redirect=https://5962869729472723353.owasp.org HTTP/1.1\r\n"
                    + requestHeaders + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(reader.readLine()).startsWith("HTTP/1.1 302");
            List<String> headers = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
            }
            assertThat(headers).contains("Location: /?reloaded=true");
            assertThat(headers).noneMatch(header -> header.contains("5962869729472723353.owasp.org"));
        }
        verify(contentService).reloadAll();
    }
}
