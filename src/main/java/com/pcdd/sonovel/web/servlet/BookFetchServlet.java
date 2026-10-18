package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Crawler;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.utils.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Set;

public class BookFetchServlet extends HttpServlet {

    private static final Set<String> ALLOWED_FORMATS = Set.of("epub", "txt", "html", "pdf");
    private static final Set<String> ALLOWED_LANGUAGES = Set.of("zh-cn", "zh-tw", "zh-hant");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            String bookUrl = req.getParameter("url");
            if (StrUtil.isBlank(bookUrl)) {
                RespUtils.writeError(resp, 400, "书籍详情链接为空，请重新搜索；若仍出现，请检查对应书源的搜索书名规则是否选中了带链接的元素");
                return;
            }
            String format = req.getParameter("format");
            String language = req.getParameter("language");
            String concurrencyStr = req.getParameter("concurrency");
            Rule rule = SourceUtils.getRule(bookUrl);
            if (rule.isSearchOnly()) {
                RespUtils.writeError(resp, 400, StrUtil.blankToDefault(rule.getSearchOnlyReason(),
                        "该分享书源不支持下载，请更换书源") + "（书源：" + rule.getName() + "）");
                return;
            }
            int id = rule.getId();

            if (StrUtil.isNotBlank(format) && !ALLOWED_FORMATS.contains(format.toLowerCase())) {
                RespUtils.writeError(resp, 400, "不支持的下载格式: " + format + "，可选R: epub, txt, html, pdf");
                return;
            }

            if (StrUtil.isNotBlank(language) && !ALLOWED_LANGUAGES.contains(language.toLowerCase())) {
                RespUtils.writeError(resp, 400, "不支持的语言: " + language + "，可选: zh-CN, zh-TW, zh-Hant");
                return;
            }

            Integer concurrency = null;
            if (StrUtil.isNotBlank(concurrencyStr)) {
                concurrency = Integer.parseInt(concurrencyStr);
                int configConcurrency = AppConfigLoader.APP_CONFIG.getConcurrency();
                int maxAllowed = configConcurrency > 0 ? configConcurrency : 50;
                if (concurrency < 1 || concurrency > maxAllowed) {
                    RespUtils.writeError(resp, 400, "并发数须在 1~" + maxAllowed + " 之间");
                    return;
                }
            }

            SearchResult sr = SearchResult.builder()
                    .sourceId(id)
                    .url(bookUrl)
                    // 分享书源的详情页书名规则可能缺失，带上搜索结果中的书名/作者兜底
                    .bookName(req.getParameter("bookName"))
                    .author(req.getParameter("author"))
                    .build();

            double totalTimeSeconds = downloadFileToServer(sr, format, language, concurrency);
            if (totalTimeSeconds == 0) {
                RespUtils.writeError(resp, 500, "源站章节目录为空，中止下载");
            }

        } catch (Exception e) {
            RespUtils.writeError(resp, 500, e.getMessage());
        }
    }

    private double downloadFileToServer(SearchResult sr, String format, String language, Integer concurrency) {
        AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
        cfg.setSourceId(sr.getSourceId());

        if (StrUtil.isNotBlank(format)) {
            cfg.setExtName(format.toLowerCase());
        }
        if (StrUtil.isNotBlank(language)) {
            cfg.setLanguage(language);
        }
        if (concurrency != null) {
            cfg.setConcurrency(concurrency);
        }

        Console.log("<== 正在解析章节目录...");

        return new Crawler(cfg).crawl(sr.getUrl(), sr.getBookName(), sr.getAuthor());
    }

}