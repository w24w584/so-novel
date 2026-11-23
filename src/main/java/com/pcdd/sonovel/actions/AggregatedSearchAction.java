package com.pcdd.sonovel.actions;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import com.pcdd.sonovel.core.Source;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.handler.SearchResultsHandler;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parser.SearchParser;
import com.pcdd.sonovel.utils.EnvUtils;
import com.pcdd.sonovel.utils.SourceUtils;
import com.pcdd.sonovel.utils.VirtualThreadLimiter;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.fusesource.jansi.AnsiRenderer.render;

/**
 * 聚合搜索，从全部书源搜索
 *
 * @author pcdd
 * Created at 2025/3/26
 */
@AllArgsConstructor
public class AggregatedSearchAction {

    public void execute() {
        Scanner sc = Console.scanner();
        Console.print(render("==> 请输入书名或作者（尽量输完整）: ", "green"));
        String kw = sc.nextLine().strip();
        if (kw.isEmpty()) return;

        List<SearchResult> results = getSearchResults(kw);

        if (CollUtil.isEmpty(results)) {
            Console.log(render("聚合搜索结果为空！", "yellow"));
            return;
        }

        SearchParser.printAggregateSearchResult(results);

        new DownloadAction().execute(results);
    }

    @SneakyThrows
    public static List<SearchResult> getSearchResults(String kw) {
        return getSearchResults(kw, Set.of());
    }

    @SneakyThrows
    public static List<SearchResult> getSearchResults(String kw, Set<String> shareSourceIds) {
        Console.log("<== 搜索关键字 “{}”", kw);
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Source> searchableSources = SourceUtils.getSearchableSources(shareSourceIds);
        CountDownLatch latch = new CountDownLatch(searchableSources.size());
        AtomicInteger failed = new AtomicInteger();
        boolean dev = EnvUtils.isDev();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Source source : searchableSources) {
                executor.execute(() -> {
                    try {
                        SearchParser parser = new SearchParser(source.config);
                        List<SearchResult> res = parser.parse(kw);
                        if (parser.isParseFailed()) {
                            failed.incrementAndGet();
                        }
                        if (CollUtil.isNotEmpty(res)) {
                            Rule rule = source.rule;
                            Console.log("<== 书源 {} ({})\t搜索到 {} 条记录", rule.getId(), rule.getName(), res.size());
                            results.addAll(res);
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        if (dev) {
                            Console.error("搜索源 {} 异常：{}", source.rule.getName(), e.getMessage());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            Console.log("<== 聚合搜索完成：成功 {} / 失败 {} / 共 {} 个书源", searchableSources.size() - failed.get(), failed.get(), searchableSources.size());
            return SearchResultsHandler.filterAndSort(results, kw);
        }
    }

    /**
     * 流式搜索进度回调
     */
    @FunctionalInterface
    public interface SearchProgress {
        void onProgress(int done, int total, int found, int failed);
    }

    /**
     * 流式聚合搜索：限制并发保证稳定性，按完成顺序通过 onPage 推送各书源结果（可并行回调，需线程安全），
     * 并通过 onProgress 上报扫描进度（已扫描书源数 / 总数 / 已命中条数 / 失败书源数）。
     * 全部书源都会执行到结束，除非 {@code cancelled} 返回 true（例如 Web 端已断开连接）。
     */
    @SneakyThrows
    public static List<SearchResult> streamSearch(String kw, Set<String> shareSourceIds, Set<Integer> mainSel,
                                                  boolean precision,
                                                  Consumer<List<SearchResult>> onPage,
                                                  SearchProgress onProgress) {
        return streamSearch(kw, shareSourceIds, mainSel, precision, onPage, onProgress, () -> false);
    }

    @SneakyThrows
    public static List<SearchResult> streamSearch(String kw, Set<String> shareSourceIds, Set<Integer> mainSel,
                                                  boolean precision,
                                                  Consumer<List<SearchResult>> onPage,
                                                  SearchProgress onProgress,
                                                  BooleanSupplier cancelled) {
        Console.log("<== 搜索关键字 “{}”", kw);
        List<Source> searchableSources = SourceUtils.getSearchableSources(shareSourceIds, mainSel);
        int total = searchableSources.size();
        List<SearchResult> all = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger searched = new AtomicInteger();
        AtomicInteger found = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        boolean dev = EnvUtils.isDev();
        int concurrency = AppConfigLoader.APP_CONFIG.getConcurrency();
        if (concurrency <= 0) concurrency = 20;
        // 实际派发出去的书源数：取消后剩余的不算，日志里按它统计成功率
        int dispatched = 0;

        try (VirtualThreadLimiter limiter = new VirtualThreadLimiter(concurrency)) {
            for (Source source : searchableSources) {
                // 客户端已经断开时不再派发新任务，避免用户点过「停止」后后台还跑完上千个书源
                if (cancelled.getAsBoolean()) {
                    Console.log("<== 搜索已中断，剩余 {} 个书源不再派发", total - dispatched);
                    break;
                }
                Runnable task = () -> {
                    if (cancelled.getAsBoolean()) return;
                    List<SearchResult> raw = null;
                    try {
                        SearchParser parser = new SearchParser(source.config);
                        raw = parser.parse(kw);
                        if (parser.isParseFailed()) {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        // 失效站/反爬站很多，普通运行只计数，详细原因仅在开发模式打印，避免刷屏
                        if (dev) {
                            Console.error("搜索源 {} 异常：{}", source.rule.getName(), e.getMessage());
                        }
                    } finally {
                        List<SearchResult> res = precision
                                ? SearchResultsHandler.filterContains(raw, kw)
                                : raw;
                        if (res != null && !res.isEmpty()) {
                            synchronized (all) {
                                all.addAll(res);
                            }
                            found.addAndGet(res.size());
                            if (onPage != null) onPage.accept(res);
                        }
                        if (onProgress != null) {
                            onProgress.onProgress(searched.incrementAndGet(), total, found.get(), failed.get());
                        }
                    }
                };
                dispatched++;
                // 用 trySubmitAndWait 跟并发上限同步派发：否则上千个任务会被瞬间投完，
                // 取消时只能等它们全部排队跑完，起不到“停止”的作用
                if (!limiter.trySubmitAndWait(task, cancelled)) {
                    Console.log("<== 搜索已中断，剩余 {} 个书源不再派发", total - dispatched);
                    dispatched--; // 这条并未真正提交
                    break;
                }
            }
        }

        // 成功口径按“实际派发”算：取消后剩余书源没执行，不应计成成功
        Console.log("<== 聚合搜索完成：成功 {} / 失败 {} / 共 {} 个书源（已派发 {}），命中 {} 条",
                dispatched - failed.get(), failed.get(), total, dispatched, found.get());
        return SearchResultsHandler.filterAndSort(all, kw);
    }

}