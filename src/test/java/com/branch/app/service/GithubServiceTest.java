package com.branch.app.service;

import com.branch.app.exception.NotFoundException;
import com.branch.app.exception.RateLimitException;
import com.branch.app.model.GithubUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class GithubServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private MockRestServiceServer mockServer;
    private GithubService service;

    private static final String USER_JSON = """
            {
              "login": "octocat",
              "name": "The Octocat",
              "avatar_url": "https://avatars.githubusercontent.com/octocat",
              "location": "San Francisco",
              "email": "octocat@github.com",
              "url": "https://api.github.com/users/octocat",
              "created_at": "2011-01-25T18:44:36Z"
            }
            """;

    private static final String REPOS_JSON = """
            [
              {"name": "Hello-World", "url": "https://github.com/octocat/Hello-World"}
            ]
            """;

    private static final String REPOS_EMPTY_JSON = "[]";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        RestClient.Builder builder = RestClient.builder()
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                });

        mockServer = MockRestServiceServer.bindTo(builder).build();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new GithubService(builder, redisTemplate, "");
    }

    @Test
    void getUser_cacheHit_returnsCachedUser() {
        GithubUser cached = GithubUser.builder().userName("octocat").build();
        when(valueOps.get("github:user:octocat")).thenReturn(cached);

        GithubUser result = service.getUser("octocat");

        assertThat(result).isSameAs(cached);
        mockServer.verify();
    }

    @Test
    void getUser_cacheHitNotFound_throwsNotFoundException() {
        when(valueOps.get("github:user:nobody")).thenReturn("__NOT_FOUND__");

        assertThatThrownBy(() -> service.getUser("nobody"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("nobody");

        mockServer.verify();
    }

    @Test
    void getUser_cacheMiss_fetchesAndCachesUser() {
        when(valueOps.get("github:user:octocat")).thenReturn(null);

        mockServer.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.github.com/users/octocat/repos?per_page=100&page=1"))
                .andRespond(withSuccess(REPOS_JSON, MediaType.APPLICATION_JSON));

        GithubUser result = service.getUser("octocat");

        assertThat(result.getUserName()).isEqualTo("octocat");
        assertThat(result.getDisplayName()).isEqualTo("The Octocat");
        assertThat(result.getRepos()).hasSize(1);
        assertThat(result.getRepos().get(0).getName()).isEqualTo("Hello-World");
        verify(valueOps).set(eq("github:user:octocat"), eq(result), anyLong(), any(TimeUnit.class));
        mockServer.verify();
    }

    @Test
    void getUser_cacheMiss_githubReturns404_cachesNotFoundAndThrows() {
        when(valueOps.get("github:user:nobody")).thenReturn(null);

        mockServer.expect(requestTo("https://api.github.com/users/nobody"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.getUser("nobody"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("nobody");

        verify(valueOps).set(eq("github:user:nobody"), eq("__NOT_FOUND__"), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getUser_cacheMiss_rateLimited_throwsRateLimitException() {
        when(valueOps.get("github:user:octocat")).thenReturn(null);

        HttpHeaders rateLimitHeaders = new HttpHeaders();
        rateLimitHeaders.set("X-RateLimit-Remaining", "0");
        rateLimitHeaders.set("X-RateLimit-Reset", "9999999999");
        rateLimitHeaders.set("x-ratelimit-limit", "60");

        mockServer.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(rateLimitHeaders));

        assertThatThrownBy(() -> service.getUser("octocat"))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("60")
                .hasMessageContaining("Resets at");
    }

    @Test
    void getUser_cacheMiss_forbiddenButNotRateLimited_throwsOriginalException() {
        when(valueOps.get("github:user:octocat")).thenReturn(null);

        mockServer.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.getUser("octocat"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class)
                .isNotInstanceOf(RateLimitException.class);
    }

    @Test
    void getUser_cacheMiss_paginatesUntilPartialPage() {
        when(valueOps.get("github:user:octocat")).thenReturn(null);

        String page1Json = buildReposJson(100, 0);
        String page2Json = buildReposJson(50, 100);

        mockServer.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.github.com/users/octocat/repos?per_page=100&page=1"))
                .andRespond(withSuccess(page1Json, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.github.com/users/octocat/repos?per_page=100&page=2"))
                .andRespond(withSuccess(page2Json, MediaType.APPLICATION_JSON));

        GithubUser result = service.getUser("octocat");

        assertThat(result.getRepos()).hasSize(150);
        mockServer.verify();
    }

    @Test
    void getUser_cacheMiss_stopsOnEmptyPage() {
        when(valueOps.get("github:user:octocat")).thenReturn(null);

        String page1Json = buildReposJson(100, 0);

        mockServer.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.github.com/users/octocat/repos?per_page=100&page=1"))
                .andRespond(withSuccess(page1Json, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.github.com/users/octocat/repos?per_page=100&page=2"))
                .andRespond(withSuccess(REPOS_EMPTY_JSON, MediaType.APPLICATION_JSON));

        GithubUser result = service.getUser("octocat");

        assertThat(result.getRepos()).hasSize(100);
        mockServer.verify();
    }

    private String buildReposJson(int count, int startIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            int idx = startIndex + i;
            if (i > 0) sb.append(",");
            sb.append("""
                    {"name": "repo-%d", "url": "https://github.com/octocat/repo-%d"}
                    """.formatted(idx, idx));
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    void getUser_withPat_sendsAuthorizationHeader() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        RestClient.Builder builder = RestClient.builder()
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                });
        MockRestServiceServer patMockServer = MockRestServiceServer.bindTo(builder).build();
        GithubService patService = new GithubService(builder, redisTemplate, "test-pat-token");

        when(valueOps.get("github:user:octocat")).thenReturn(null);

        patMockServer.expect(requestTo("https://api.github.com/users/octocat"))
                .andExpect(header("Authorization", "Bearer test-pat-token"))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));
        patMockServer.expect(requestTo("https://api.github.com/users/octocat/repos?per_page=100&page=1"))
                .andRespond(withSuccess(REPOS_JSON, MediaType.APPLICATION_JSON));

        patService.getUser("octocat");

        patMockServer.verify();
    }
}
