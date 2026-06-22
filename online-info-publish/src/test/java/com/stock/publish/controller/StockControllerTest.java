package com.stock.publish.controller;

import com.stock.publish.entity.SyncStockInfo;
import com.stock.publish.service.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock
    private StockService stockService;

    @InjectMocks
    private StockController stockController;

    @Test
    void testSearchReturnsOk() throws Exception {
        SyncStockInfo stock = new SyncStockInfo();
        stock.setStockCode("600519");
        stock.setStockName("贵州茅台");
        stock.setYesterdayClose(new BigDecimal("1660.00"));
        stock.setStatus(0);

        when(stockService.search("600")).thenReturn(List.of(stock));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(stockController).build();
        mockMvc.perform(get("/stock/search").param("keyword", "600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].stockCode").value("600519"))
                .andExpect(jsonPath("$.data[0].stockName").value("贵州茅台"));
    }

    @Test
    void testSearchEmptyKeyword() throws Exception {
        when(stockService.search("")).thenReturn(List.of());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(stockController).build();
        mockMvc.perform(get("/stock/search").param("keyword", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
