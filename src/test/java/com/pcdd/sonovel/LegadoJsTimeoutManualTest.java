package com.pcdd.sonovel;

import com.pcdd.sonovel.utils.LegadoJsRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验证书源 JS 死循环会被看门狗中断，且中止后引擎仍可复用。
 * <p>
 * 需要 javet 原生库，请带平台 profile 运行：{@code mvn test -Pwindows-x64 -Dtest=LegadoJsTimeoutManualTest}
 */
class LegadoJsTimeoutManualTest {

    static {
        // 缩短超时，避免测试跑满 15s（常量在类初始化时读取，须先于 LegadoJsRuntime 加载设置）
        System.setProperty("sonovel.js-timeout-seconds", "2");
    }

    @Test
    void infiniteLoopShouldBeTerminatedAndEngineReusable() {
        // javet 原生库由平台 profile 引入（如 -Pwindows-x64）；本地默认构建没有时跳过，避免误报
        var probe = LegadoJsRuntime.evalChapterList(
                "['<a href=\"/c1\">第一章</a>']", "<html></html>", "https://example.com", "https://example.com", 10);
        assumeTrue(probe.size() == 1, "javet 原生库不可用（需带平台 profile 运行），跳过本测试");

        long start = System.currentTimeMillis();
        var items = LegadoJsRuntime.evalChapterList("while(true){}", "<html></html>", "https://example.com", "https://example.com", 10);
        long cost = System.currentTimeMillis() - start;
        System.out.println("超时中止 cost=" + cost + "ms items=" + items.size());
        assertTrue(items.isEmpty(), "死循环应返回空结果");
        assertTrue(cost < 12_000, "应在看门狗超时后被中断，实际耗时 " + cost + "ms");

        // 被中止的引擎归还池后必须仍可复用，否则一次死循环书源会把整个池拖坏
        var normal = LegadoJsRuntime.evalChapterList(
                "['<a href=\"/c1\">第一章</a>']", "<html></html>", "https://example.com", "https://example.com", 10);
        System.out.println("复用后 items=" + normal.size());
        assertEquals(1, normal.size(), "中止后引擎应能继续执行正常脚本");
    }
}
