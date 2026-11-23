package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.actions.AggregatedSearchAction;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AggregatedSearchServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String name = req.getParameter("kw");
        String searchLimitStr = req.getParameter("searchLimit");
        Set<String> shareSourceIds = parseIds(req.getParameterValues("shareSourceIds"));
        respond(resp, name, searchLimitStr, shareSourceIds);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String name;
        Set<String> shareSourceIds;
        Set<Integer> mainSourceIds;
        String searchLimitStr;
        boolean precision;
        try {
            JSONObject body = JSONUtil.parseObj(readBody(req));
            name = body.getStr("kw");
            searchLimitStr = body.getStr("searchLimit");
            precision = Boolean.TRUE.equals(body.getBool("precision", false));
            List<String> ids = body.getJSONArray("shareSourceIds") == null
                    ? List.of() : body.getJSONArray("shareSourceIds").toList(String.class);
            shareSourceIds = parseIds(ids.toArray(new String[0]));
            // 前端显式传入的 main 勾选集合；缺失时表示“默认全部 main 参与”
            mainSourceIds = body.getJSONArray("mainSourceIds") == null
                    ? null
                    : body.getJSONArray("mainSourceIds").toList(Integer.class)
                        .stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            RespUtils.writeError(resp, 400, "请求参数格式错误");
            return;
        }
        if (StrUtil.isBlank(name)) {
            RespUtils.writeError(resp, 400, "搜索关键字不能为空");
            return;
        }

        int maxResults = resolveMaxResults(searchLimitStr);

        // SSE 流式推送，前端按事件逐页展示
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");
        resp.setCharacterEncoding("UTF-8");

        Object writeLock = new Object();
        AtomicBoolean closed = new AtomicBoolean(false);

        // 聚合搜索可能持续数十秒，不能让请求线程一直阻塞：转异步 + 虚拟线程，
        // 这样并发用户再多也不会耗尽 Jetty 的请求线程
        AsyncContext async = req.startAsync();
        async.setTimeout(TimeUnit.MINUTES.toMillis(10));
        ServletOutputStream output;
        try {
            output = resp.getOutputStream();
        } catch (IOException e) {
            async.complete();
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                List<SearchResult> finalResults = AggregatedSearchAction.streamSearch(
                        name, shareSourceIds, mainSourceIds, precision,
                        batch -> sendSse(output, writeLock, closed, pageEvent(batch)),
                        (done, total, found, failed) ->
                                sendSse(output, writeLock, closed, progressEvent(done, total, found, failed)));

                sendSse(output, writeLock, closed, doneEvent(finalResults, maxResults));
            } catch (Exception e) {
                sendSse(output, writeLock, closed, errorEvent(e.getMessage()));
            } finally {
                if (!closed.get()) {
                    try {
                        output.flush();
                    } catch (IOException ignored) {
                    }
                }
                async.complete();
            }
        });
    }

    /**
     * 解析客户端传入的 limit 并夹在配置文件上限内，未传入或非法时返回 null
     */
    private Integer parseClientLimit(String searchLimitStr) {
        if (StrUtil.isBlank(searchLimitStr)) return null;
        try {
            int clientLimit = Integer.parseInt(searchLimitStr);
            int configLimit = AppConfigLoader.APP_CONFIG.getSearchLimit();
            // 客户端限制不可超过配置文件限制
            if (configLimit > 0 && clientLimit > configLimit) {
                clientLimit = configLimit;
            }
            return clientLimit;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int resolveMaxResults(String searchLimitStr) {
        Integer clientLimit = parseClientLimit(searchLimitStr);
        if (clientLimit != null && clientLimit > 0) return clientLimit;
        int configLimit = AppConfigLoader.APP_CONFIG.getSearchLimit();
        return configLimit > 0 ? configLimit : 0;
    }

    private void respond(HttpServletResponse resp, String name, String searchLimitStr, Set<String> shareSourceIds) {
        List<SearchResult> results = AggregatedSearchAction.getSearchResults(name, shareSourceIds);

        Integer clientLimit = parseClientLimit(searchLimitStr);
        if (clientLimit != null && clientLimit > 0 && clientLimit < results.size()) {
            results = results.subList(0, clientLimit);
        }

        RespUtils.writeJson(resp, results);
    }

    private Set<String> parseIds(String[] values) {
        if (values == null) return Set.of();
        return java.util.Arrays.stream(values)
                .filter(id -> id != null && id.contains("#"))
                .collect(Collectors.toSet());
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void sendSse(ServletOutputStream out, Object lock, AtomicBoolean closed, String json) {
        synchronized (lock) {
            if (closed.get()) return;
            try {
                out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                closed.set(true);
            }
        }
    }

    private String pageEvent(List<SearchResult> batch) {
        JSONObject evt = new JSONObject();
        evt.set("type", "stream_page");
        evt.set("results", batch);
        return evt.toString();
    }

    private String doneEvent(List<SearchResult> finalResults, int maxResults) {
        JSONObject evt = new JSONObject();
        evt.set("type", "stream_done");
        if (maxResults > 0 && finalResults.size() > maxResults) {
            finalResults = finalResults.subList(0, maxResults);
        }
        evt.set("results", finalResults);
        return evt.toString();
    }

    private String errorEvent(String message) {
        JSONObject evt = new JSONObject();
        evt.set("type", "stream_error");
        evt.set("message", String.valueOf(message));
        return evt.toString();
    }

    private String progressEvent(int done, int total, int found, int failed) {
        JSONObject evt = new JSONObject();
        evt.set("type", "stream_progress");
        evt.set("done", done);
        evt.set("total", total);
        evt.set("found", found);
        evt.set("failed", failed);
        return evt.toString();
    }

}