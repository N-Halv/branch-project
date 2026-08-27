package com.branch.app.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @Mock
    private FilterChain chain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        filter = new RateLimitFilter(redisTemplate);
    }

    @Test
    void requestBelowLimit_allowsThrough() throws Exception {
        when(valueOps.increment("ratelimit:ip:127.0.0.1")).thenReturn(50L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("100");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("50");
    }

    @Test
    void requestAtLimit_allowsThrough() throws Exception {
        when(valueOps.increment("ratelimit:ip:127.0.0.1")).thenReturn(100L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void requestExceedsLimit_returns429AndBlocksChain() throws Exception {
        when(valueOps.increment("ratelimit:ip:127.0.0.1")).thenReturn(101L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("Too many requests");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("100");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void firstRequest_setsWindowExpiry() throws Exception {
        when(valueOps.increment("ratelimit:ip:127.0.0.1")).thenReturn(1L);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        verify(redisTemplate).expire("ratelimit:ip:127.0.0.1", 30, TimeUnit.MINUTES);
    }

    @Test
    void subsequentRequest_doesNotResetExpiry() throws Exception {
        when(valueOps.increment("ratelimit:ip:127.0.0.1")).thenReturn(50L);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        verify(redisTemplate, never()).expire(any(), anyLong(), any());
    }

    @Test
    void xForwardedFor_usesFirstIp() throws Exception {
        when(valueOps.increment("ratelimit:ip:10.0.0.1")).thenReturn(1L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(valueOps).increment("ratelimit:ip:10.0.0.1");
    }

    @Test
    void redisUnavailable_allowsRequestThroughWithNoRateLimitHeaders() throws Exception {
        when(valueOps.increment(any())).thenThrow(new RuntimeException("Connection refused"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNull();
    }
}
