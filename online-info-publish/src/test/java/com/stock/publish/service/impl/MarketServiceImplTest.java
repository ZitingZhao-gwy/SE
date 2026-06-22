package com.stock.publish.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stock.publish.calculation.TopTraderEngine;
import com.stock.publish.dto.QuoteDTO;
import com.stock.publish.entity.Kline5mData;
import com.stock.publish.entity.SyncStockInfo;
import com.stock.publish.interceptor.UserContext;
import com.stock.publish.mapper.Kline5mDataMapper;
import com.stock.publish.mapper.SyncStockInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketServiceImplTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedissonClient redissonClient;
    @Mock private SyncStockInfoMapper stockInfoMapper;
    @Mock private Kline5mDataMapper kline5mDataMapper;
    @Mock private TopTraderEngine topTraderEngine;
    @Mock private RLock rLock;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ListOperations<String, String> listOps;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private MarketServiceImpl marketService;
    private MockedStatic<UserContext> mockedUserContext;

    @BeforeEach
    void setUp() {
        mockedUserContext = Mockito.mockStatic(UserContext.class);
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.STANDARD);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);

        marketService = new MarketServiceImpl(redisTemplate, redissonClient,
                stockInfoMapper, kline5mDataMapper, topTraderEngine, objectMapper);
    }

    @AfterEach
    void tearDown() {
        mockedUserContext.close();
    }

    // ==================== getQuote 测试 ====================

    @Test
    void testGetQuoteCacheHit() throws Exception {
        QuoteDTO cached = buildTestQuote();
        String json = objectMapper.writeValueAsString(cached);
        when(valueOps.get("quote:600519")).thenReturn(json);

        QuoteDTO result = marketService.getQuote("600519");

        assertNotNull(result);
        assertEquals("600519", result.getStockCode());
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void testGetQuoteCacheMissWithLock() throws Exception {
        when(valueOps.get("quote:600519")).thenReturn(null, null);
        when(redissonClient.getLock("lock:quote:600519")).thenReturn(rLock);
        when(rLock.tryLock(3L, 10L, TimeUnit.SECONDS)).thenReturn(true);

        mockDefaultStock();

        QuoteDTO result = marketService.getQuote("600519");

        assertNotNull(result);
        assertEquals("600519", result.getStockCode());
        verify(valueOps, times(1)).set(eq("quote:600519"), anyString(), eq(5L), eq(TimeUnit.SECONDS));
    }

    // ==================== maskByRole 测试 ====================

    @Test
    void testMaskByRoleGuest() throws Exception {
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.GUEST);
        when(valueOps.get("quote:600519")).thenReturn(null, null);
        when(redissonClient.getLock("lock:quote:600519")).thenReturn(rLock);
        when(rLock.tryLock(3L, 10L, TimeUnit.SECONDS)).thenReturn(true);
        mockDefaultStock();

        QuoteDTO result = marketService.getQuote("600519");

        assertNotNull(result);
        assertNull(result.getBidPrice());
        assertNull(result.getAskPrice());
        assertNull(result.getBidVolume());
        assertNull(result.getAskVolume());
    }

    @Test
    void testMaskByRoleStandard() throws Exception {
        QuoteDTO cached = buildTestQuote();
        String json = objectMapper.writeValueAsString(cached);
        when(valueOps.get("quote:600519")).thenReturn(json);

        QuoteDTO result = marketService.getQuote("600519");

        assertNotNull(result);
        assertNotNull(result.getBidPrice());
        assertEquals(new BigDecimal("1679.99"), result.getBidPrice());
    }

    // ==================== buildQuote 测试 ====================

    @Test
    void testBuildQuoteFromDb() throws Exception {
        when(valueOps.get("quote:600519")).thenReturn(null, null);
        when(redissonClient.getLock("lock:quote:600519")).thenReturn(rLock);
        when(rLock.tryLock(3L, 10L, TimeUnit.SECONDS)).thenReturn(true);
        mockDefaultStock();

        QuoteDTO result = marketService.getQuote("600519");

        assertNotNull(result);
        assertEquals("600519", result.getStockCode());
        assertEquals(new BigDecimal("1660.00"), result.getLastPrice());
        assertEquals("+0.00%", result.getChangeRate());
    }

    @Test
    void testBuildQuoteUnknownStock() throws Exception {
        when(valueOps.get("quote:999999")).thenReturn(null, null);
        when(redissonClient.getLock("lock:quote:999999")).thenReturn(rLock);
        when(rLock.tryLock(3L, 10L, TimeUnit.SECONDS)).thenReturn(true);
        when(stockInfoMapper.selectById("999999")).thenReturn(null);

        QuoteDTO result = marketService.getQuote("999999");

        assertNull(result);
    }

    // ==================== refreshQuotes 测试 ====================

    @Test
    void testRefreshQuotesWritesRedis() {
        mockDefaultStock();
        when(stockInfoMapper.selectById("000001")).thenReturn(null);

        marketService.refreshQuotes();

        verify(valueOps, times(1)).set(eq("quote:600519"), anyString(), eq(5L), eq(TimeUnit.SECONDS));
        verify(listOps, atLeastOnce()).rightPush(eq("tick:600519"), anyString());
    }

    // ==================== aggregate5mKline 测试 ====================

    @Test
    void testAggregate5mKline() throws Exception {
        String tickJson1 = "{\"stockCode\":\"600519\",\"timestamp\":\"2026-06-18T09:30:00\",\"buyerAccount\":\"A\",\"sellerAccount\":\"B\",\"price\":100.00,\"quantity\":1000}";
        String tickJson2 = "{\"stockCode\":\"600519\",\"timestamp\":\"2026-06-18T09:31:00\",\"buyerAccount\":\"C\",\"sellerAccount\":\"D\",\"price\":102.00,\"quantity\":2000}";
        String tickJson3 = "{\"stockCode\":\"600519\",\"timestamp\":\"2026-06-18T09:32:00\",\"buyerAccount\":\"E\",\"sellerAccount\":\"F\",\"price\":98.00,\"quantity\":1500}";

        when(listOps.range("tick:600519", 0, -1)).thenReturn(List.of(tickJson1, tickJson2, tickJson3));
        when(listOps.range("tick:000001", 0, -1)).thenReturn(List.of());

        marketService.aggregate5mKline();

        ArgumentCaptor<Kline5mData> captor = ArgumentCaptor.forClass(Kline5mData.class);
        verify(kline5mDataMapper, times(1)).insert(captor.capture());
        Kline5mData k = captor.getValue();
        assertEquals("600519", k.getStockCode());
        assertEquals(0, k.getOpenPrice().compareTo(new BigDecimal("100.00")));
        assertEquals(0, k.getClosePrice().compareTo(new BigDecimal("98.00")));
        assertEquals(0, k.getHighPrice().compareTo(new BigDecimal("102.00")));
        assertEquals(0, k.getLowPrice().compareTo(new BigDecimal("98.00")));
        assertEquals(4500L, k.getVolume());
        verify(redisTemplate).delete("tick:600519");
    }

    // ==================== helpers ====================

    private void mockDefaultStock() {
        SyncStockInfo info = new SyncStockInfo();
        info.setStockCode("600519");
        info.setStockName("贵州茅台");
        info.setYesterdayClose(new BigDecimal("1660.00"));
        info.setStatus(0);
        when(stockInfoMapper.selectById("600519")).thenReturn(info);
    }

    private QuoteDTO buildTestQuote() {
        QuoteDTO q = new QuoteDTO();
        q.setStockCode("600519");
        q.setStockName("贵州茅台");
        q.setLastPrice(new BigDecimal("1680.00"));
        q.setChangeRate("+1.20%");
        q.setBidPrice(new BigDecimal("1679.99"));
        q.setAskPrice(new BigDecimal("1680.01"));
        q.setBidVolume(15000L);
        q.setAskVolume(12000L);
        return q;
    }
}
