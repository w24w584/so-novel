package com.pcdd.sonovel.web.servlet;

import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.utils.ShareBookSourceLoader;
import com.pcdd.sonovel.utils.ShareBookSourceLoader.ProgressEvent;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手动增量读取额外书源，通过 SSE 逐条推送进度给前端。
 */
public class ShareSourceReloadServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");

        AsyncContext async = req.startAsync();
        async.setTimeout(0);

        ServletOutputStream out = resp.getOutputStream();
        out.write("retry: 10000\n".getBytes(StandardCharsets.UTF_8));
        out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        async.start(() -> {
            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);
            AtomicInteger total = new AtomicInteger(0);
            try {
                List<Map<String, Object>> grouped =
                        ShareBookSourceLoader.reloadGrouped(event -> {
                            try {
                                if (event.total() > 0) total.set(event.total());
                                if (event.start()) {
                                    send(out, progressJson(event));
                                    return;
                                }
                                if (event.ok()) {
                                    success.incrementAndGet();
                                } else {
                                    skipped.incrementAndGet();
                                }
                                send(out, progressJson(event));
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        });

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("data", grouped);
                done.put("success", success.get());
                done.put("skipped", skipped.get());
                done.put("total", total.get());
                send(out, JSONUtil.toJsonStr(done));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("type", "error");
                err.put("message", String.valueOf(e.getMessage()));
                try {
                    send(out, JSONUtil.toJsonStr(err));
                } catch (IOException ignored) {
                }
            } finally {
                async.complete();
            }
        });
    }

    private String progressJson(ProgressEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "progress");
        m.put("index", event.index());
        m.put("total", event.total());
        m.put("fileName", event.fileName());
        m.put("sourceName", event.sourceName());
        m.put("ok", event.ok());
        m.put("message", event.message());
        return JSONUtil.toJsonStr(m);
    }

    private void send(ServletOutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}