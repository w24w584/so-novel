package com.pcdd.sonovel;

import com.pcdd.sonovel.utils.VirtualThreadLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证“取消后不再派发”的节流机制：派发方跟着并发上限走，取消信号能被及时观察到
 */
class VirtualThreadLimiterCancelTest {

    @Test
    void stopsDispatchingWhenCancelled() throws Exception {
        AtomicInteger started = new AtomicInteger();
        AtomicInteger submitted = new AtomicInteger();
        // 已有 4 个任务开始执行时视为“用户点了停止”
        boolean[] cancelFlag = {false};

        try (VirtualThreadLimiter limiter = new VirtualThreadLimiter(2)) {
            for (int i = 1; i <= 20; i++) {
                if (!limiter.trySubmitAndWait(() -> {
                    int n = started.incrementAndGet();
                    if (n >= 4) cancelFlag[0] = true;
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                       }
                }, () -> cancelFlag[0])) {
                    break;
                }
                submitted.incrementAndGet();
            }
        }

        // 取消只应放过少量在途任务，不应把 20 个全跑掉
        assertTrue(started.get() <= 10, "取消后仍执行了过多任务: " + started.get());
        assertTrue(submitted.get() < 20, "取消后仍派发了全部任务: " + submitted.get());
        assertEquals(started.get(), submitted.get());
    }
}
