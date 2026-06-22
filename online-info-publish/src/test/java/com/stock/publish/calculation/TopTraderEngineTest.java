package com.stock.publish.calculation;

import com.stock.publish.dto.TopTraderDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TopTraderEngineTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private TopTraderEngine topTraderEngine;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void testGetTopBuyer_WithData_ReturnsMaxAccount() {
        Map<Object, Object> mockData = new HashMap<>();
        mockData.put("AccountA", "50000");
        mockData.put("AccountB", "120000"); // 这个应该是 Top 1
        mockData.put("AccountC", "80000");

        when(hashOperations.entries(anyString())).thenReturn(mockData);

        TopTraderDTO result = topTraderEngine.getTopBuyer("600519");

        assertNotNull(result, "结果不应为空");
        assertEquals("AccountB", result.getAccount(), "买入量最大的账户应该是 AccountB");
        assertEquals(120000L, result.getQty(), "买入量应该是 120000");
    }

    @Test
    void testGetTopBuyer_EmptyData_ReturnsNull() {
        when(hashOperations.entries(anyString())).thenReturn(new HashMap<>());

        TopTraderDTO result = topTraderEngine.getTopBuyer("000000");

        assertNull(result, "无数据时应安全返回 null");
    }
}