package com.stock.publish.calculation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.stock.publish.dto.KLineDTO;
import com.stock.publish.entity.Kline5mData;
import com.stock.publish.mapper.Kline5mDataMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class KLineAggregatorTest {

    @Mock
    private Kline5mDataMapper kline5mDataMapper;

    @InjectMocks
    private KLineAggregator kLineAggregator;

    @Test
    void testGetKLineData_Aggregate1D_Success() {
        List<Kline5mData> mockEntities = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 14, 9, 30);
        
        for (int i = 0; i < 48; i++) {
            Kline5mData data = new Kline5mData();
            data.setStockCode("600519");
            data.setPeriodStartTime(baseTime.plusMinutes(i * 5L));
            data.setOpenPrice(BigDecimal.valueOf(100 + i)); 
            data.setClosePrice(BigDecimal.valueOf(101 + i)); // 最后一条收盘价应为 148
            data.setHighPrice(BigDecimal.valueOf(105 + i));  // 最大值应为 152
            data.setLowPrice(BigDecimal.valueOf(95 + i));    // 最小值应为 95
            data.setVolume(1000L); // 总成交量应为 48 * 1000 = 48000
            mockEntities.add(data);
        }

        when(kline5mDataMapper.selectList(any(Wrapper.class))).thenReturn(mockEntities);

        List<KLineDTO> result = kLineAggregator.getKLineData(
                "600519", "1D", baseTime, baseTime.plusDays(1)
        );

        // 断言验证聚合逻辑
        assertEquals(1, result.size(), "48条5M数据聚合成1D，结果应只有1条");
        KLineDTO merged = result.get(0);
        
        assertEquals("2026-06-14 09:30", merged.getTime(), "时间格式应严格遵守说明书精确到分");
        
        assertEquals(BigDecimal.valueOf(100), merged.getOpen(), "开盘价取第一条");
        assertEquals(BigDecimal.valueOf(148), merged.getClose(), "收盘价取最后一条");
        assertEquals(BigDecimal.valueOf(152), merged.getHigh(), "最高价应为区间最大值");
        assertEquals(BigDecimal.valueOf(95), merged.getLow(), "最低价应为区间最小值");
        assertEquals(48000L, merged.getVolume(), "成交量必须累加");
    }

    @Test
    void testGetKLineData_MACDFirstElement_IsZero() {
        List<Kline5mData> mockEntities = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();
        for (int i = 0; i < 2; i++) {
            Kline5mData data = new Kline5mData();
            data.setPeriodStartTime(baseTime.plusMinutes(i * 5L));
            data.setOpenPrice(BigDecimal.TEN);
            data.setClosePrice(BigDecimal.TEN);
            data.setHighPrice(BigDecimal.TEN);
            data.setLowPrice(BigDecimal.TEN);
            data.setVolume(100L);
            mockEntities.add(data);
        }

        when(kline5mDataMapper.selectList(any())).thenReturn(mockEntities);

        List<KLineDTO> result = kLineAggregator.getKLineData("600519", "5M", baseTime, baseTime.plusDays(1));

        KLineDTO firstLine = result.get(0);
        assertEquals(BigDecimal.ZERO, firstLine.getDif());
        assertEquals(BigDecimal.ZERO, firstLine.getDea());
        assertEquals(BigDecimal.ZERO, firstLine.getMacdBar());
    }
}