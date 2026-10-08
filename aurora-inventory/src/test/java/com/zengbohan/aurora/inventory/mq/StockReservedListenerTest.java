package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.inventory.mq.StockReservedListener.StockReservedEvent;
import com.zengbohan.aurora.inventory.stock.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StockReservedListenerTest {

    private StockService stockService;
    private StockReservedListener listener;

    @BeforeEach
    void setUp() {
        stockService = mock(StockService.class);
        listener = new StockReservedListener(stockService);
    }

    @Test
    void newPayloadCarriesOrderId() {
        listener.onMessage(new StockReservedEvent("1001", 1001L, 7L, 1));
        verify(stockService).applyReservedEvent("1001", 1001L, 7L, 1);
    }

    @Test
    void legacyPayloadWithoutOrderIdFallsBackToMessageId() {
        // 升级窗口内的旧消息缺 orderId，历史生产者固定 messageId = orderId
        listener.onMessage(new StockReservedEvent("1002", null, 7L, 1));
        verify(stockService).applyReservedEvent("1002", 1002L, 7L, 1);
    }

    @Test
    void unresolvableLegacyPayloadIsDroppedNotRetried() {
        // 既无 orderId、messageId 又非数字：丢弃而不是死信
        listener.onMessage(new StockReservedEvent("garbage", null, 7L, 1));
        verify(stockService, never()).applyReservedEvent(anyString(), anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }
}
