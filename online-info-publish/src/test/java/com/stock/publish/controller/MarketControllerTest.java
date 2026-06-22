package com.stock.publish.controller;

import com.stock.publish.calculation.KLineAggregator;
import com.stock.publish.calculation.TopTraderEngine;
import com.stock.publish.dto.QuoteDTO;
import com.stock.publish.interceptor.UserContext;
import com.stock.publish.service.MarketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MarketControllerTest {

    private MockMvc mockMvc;

    @Mock
    private MarketService marketService;

    @Mock
    private KLineAggregator kLineAggregator;

    @Mock
    private TopTraderEngine topTraderEngine;

    @InjectMocks
    private MarketController marketController;

    private MockedStatic<UserContext> mockedUserContext;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(marketController).build();
        mockedUserContext = Mockito.mockStatic(UserContext.class);
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.STANDARD);
    }

    @AfterEach
    void tearDown() {
        mockedUserContext.close();
    }

    // ==================== B3: kline 测试 ====================

    @Test
    void testKlineGuestAccess() throws Exception {
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.GUEST);

        mockMvc.perform(get("/market/kline")
                        .param("stockCode", "600519")
                        .param("period", "1D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("游客无权查看K线图"));
    }

    @Test
    void testKlineStandardAccess1W() throws Exception {
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.STANDARD);

        mockMvc.perform(get("/market/kline")
                        .param("stockCode", "600519")
                        .param("period", "1W"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("升级VIP解锁周/月/年K线"));
    }

    @Test
    void testKlineVipAccess() throws Exception {
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.PREMIUM_VIP);
        when(kLineAggregator.getKLineData(anyString(), anyString(), any(), any()))
                .thenReturn(new ArrayList<>());

        mockMvc.perform(get("/market/kline")
                        .param("stockCode", "600519")
                        .param("period", "5M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== B2: quote 测试 ====================

    @Test
    void testQuoteFound() throws Exception {
        QuoteDTO quote = new QuoteDTO();
        quote.setStockCode("600519");
        quote.setStockName("贵州茅台");
        quote.setLastPrice(new BigDecimal("1680.00"));
        quote.setChangeRate("+1.20%");

        when(marketService.getQuote("600519")).thenReturn(quote);

        mockMvc.perform(get("/market/quote/600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stockCode").value("600519"))
                .andExpect(jsonPath("$.data.lastPrice").value(1680.00))
                .andExpect(jsonPath("$.data.changeRate").value("+1.20%"));
    }

    @Test
    void testQuoteNotFound() throws Exception {
        when(marketService.getQuote("999999")).thenReturn(null);

        mockMvc.perform(get("/market/quote/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("股票未找到"));
    }

    @Test
    void testQuoteGuestMasked() throws Exception {
        mockedUserContext.reset();
        mockedUserContext.when(UserContext::getRole).thenReturn(UserContext.UserRole.GUEST);

        QuoteDTO quote = new QuoteDTO();
        quote.setStockCode("600519");
        quote.setStockName("贵州茅台");
        quote.setLastPrice(new BigDecimal("1680.00"));
        quote.setTopBuyer(null);
        quote.setTopSeller(null);

        when(marketService.getQuote("600519")).thenReturn(quote);

        mockMvc.perform(get("/market/quote/600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topBuyer").doesNotExist())
                .andExpect(jsonPath("$.data.topSeller").doesNotExist());
    }
}
