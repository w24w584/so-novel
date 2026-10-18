package com.pcdd.sonovel.parser;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.ChapterRenderer;
import com.pcdd.sonovel.core.HtmlExtractor;
import com.pcdd.sonovel.core.Source;
import com.pcdd.sonovel.model.*;
import com.pcdd.sonovel.utils.ChineseConverter;
import com.pcdd.sonovel.utils.CrawlUtils;
import com.pcdd.sonovel.utils.LogUtils;
import lombok.SneakyThrows;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author pcdd
 * Created at 2024/3/27
 */
public class ChapterParser extends Source {

    private final ChapterRenderer chapterRenderer;

    public ChapterParser(AppConfig config) {
        super(config);
        this.chapterRenderer = new ChapterRenderer(config);
    }

    public Chapter parse(Chapter chapter) {
        if (rule.getChapter() == null) {
            Console.error("<== 书源 {} 缺少正文规则（可能为 JS 规则），跳过章节：{}", rule.getName(), chapter.getTitle());
            return null;
        }
        try {
            long interval = CrawlUtils.randomInterval(config);
            LogUtils.logByProgressbar(LogLevel.INFO, config.getEnableProgressbar(),
                    "正在下载:【{}】 间隔 {} ms",
                    chapter.getTitle(),
                    interval);

            String content = fetchContent(chapter.getUrl(), interval);
            chapter.setContent(content);

            // 确保简繁互转最后调用
            return ChineseConverter.convert(chapterRenderer.process(chapter), this.rule.getLanguage(), config.getLanguage());

        } catch (Exception e) {
            // 中断下载，不重试（Web 服务场景下不能 System.exit，否则会杀掉整个进程）
            if (config.getEnableRetry() == 0) {
                Console.error(e, "【{}】章节下载出错。因未启用重试，故中断下载", chapter.getTitle());
                return null;
            }
            Chapter retryChapter = retry(chapter, e);
            return retryChapter == null ? null : ChineseConverter.convert(retryChapter, this.rule.getLanguage(), config.getLanguage());
        }
    }

    private Chapter retry(Chapter chapter, Exception ex) {
        for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
            try {
                // 重试间隔递增
                long interval = CrawlUtils.randomInterval(config, true) * attempt;
                LogUtils.logByProgressbar(LogLevel.WARN, config.getEnableProgressbar(),
                        "【{}】下载失败，正在重试。重试次数: {}/{} 重试间隔: {} ms 原因: {}",
                        chapter.getTitle(), attempt, config.getMaxRetries(), interval, ex.getMessage());
                String content = fetchContent(chapter.getUrl(), interval);
                chapter.setContent(content);

                LogUtils.logByProgressbar(LogLevel.INFO, config.getEnableProgressbar(),
                        "✅ 重试成功: 【{}】", chapter.getTitle());
                return chapterRenderer.process(chapter);

            } catch (Exception e) {
                LogUtils.logByProgressbar(LogLevel.WARN, config.getEnableProgressbar(),
                        "第 {} 次重试失败: 【{}】 原因: {}", attempt, chapter.getTitle(), e.getMessage());
                // 最终失败时记录日志
                if (attempt == config.getMaxRetries()) {
                    LogUtils.error(e, "❌ 下载失败章节: 【{}】({})\t原因: {}", chapter.getTitle(), chapter.getUrl(), e.getMessage());
                    if (config.getEnableProgressbar() == 0) {
                        Console.error(e, "❌ 下载失败章节: 【{}】({})\t原因: {}", chapter.getTitle(), chapter.getUrl(), e.getMessage());
                    }
                }
            }
        }

        return null;
    }

    /**
     * 爬取正文内容
     *
     * @param url      章节 url
     * @param interval 爬取间隔（毫秒）
     */
    @SneakyThrows
    private String fetchContent(String url, long interval) {
        Rule.Chapter r = rule.getChapter();
        // 获取下一章的间隔
        Thread.sleep(interval);
        return StrUtil.isBlank(r.getNextPage())
                ? fetchSinglePageContent(url, r)
                : fetchPaginatedContent(url, interval, r);
    }

    @SneakyThrows
    private String fetchSinglePageContent(String url, Rule.Chapter r) {
        Document doc = fetchDocument(url, r.getTimeout(), r.getBaseUri());
        doc = handleCloudflareBypass(doc, url, "章节页");

        String content = HtmlExtractor.extract(doc, r.getContent(), ContentType.HTML);
        Assert.notEmpty(content, "正文内容为空:\n{}\n", doc);
        logIfSuspicious(url, content, r.getContent(), doc);
        return content;
    }

    @SneakyThrows
    private String fetchPaginatedContent(String startUrl, long interval, Rule.Chapter r) {
        String nextUrl = startUrl;
        StringBuilder contentBuilder = new StringBuilder();
        List<String> pageChain = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        while (true) {
            // 防御：部分书源的“下一页”规则会指回当前页，不加限制会死循环
            if (!visited.add(nextUrl)) {
                LogUtils.logByProgressbar(LogLevel.WARN, config.getEnableProgressbar(),
                        "[正文诊断] 章节 {} 出现重复翻页地址，中止: {}", startUrl, nextUrl);
                break;
            }

            Document doc = fetchDocument(nextUrl, r.getTimeout(), r.getBaseUri());
            doc = handleCloudflareBypass(doc, nextUrl, "章节页");

            String content = HtmlExtractor.extract(doc, r.getContent(), ContentType.HTML);
            Assert.notEmpty(content, "正文内容为空:\n{}\n", doc);
            logIfSuspicious(nextUrl, content, r.getContent(), doc);
            contentBuilder.append(content);
            pageChain.add(nextUrl + " [" + content.trim().length() + "字]");

            // 获取下一页按钮元素
            Elements nextEls = HtmlExtractor.select(doc, r.getNextPage());
            String candidateNext = resolveNextUrl(doc, nextEls, r);
            if (isLastPage(candidateNext, nextEls, r)) {
                break;
            }

            nextUrl = candidateNext;
            // 获取下一分页章节的间隔
            Thread.sleep(interval);
        }

        String result = contentBuilder.toString();
        if (result.trim().length() < 200) {
            LogUtils.logByProgressbar(LogLevel.WARN, config.getEnableProgressbar(),
                    "[正文翻页诊断] 章节 {} 最终正文仅 {} 字，翻页链共 {} 页: {}",
                    startUrl, result.trim().length(), pageChain.size(), pageChain);
        }
        return result;
    }

    /**
     * 正文过短时打诊断日志，打印命中节点数与 #content 容器原始 HTML，用于定位正文真实结构
     */
    private void logIfSuspicious(String url, String content, String selector, Document doc) {
        if (content == null || doc == null) return;
        String trimmed = content.trim();
        if (trimmed.length() >= 80) return;

        int nodes = HtmlExtractor.select(doc, selector).size();
        Element container = doc.selectFirst("#content");
        String html = container == null ? "" : container.html();
        LogUtils.logByProgressbar(LogLevel.INFO, config.getEnableProgressbar(),
                "[正文诊断] 章节 {} 命中 {} 个节点，正文仅 {} 字。选择器: {}，前200字: {}，[#content 片段]: {}",
                url, nodes, trimmed.length(), selector,
                StrUtil.brief(trimmed, 200), StrUtil.brief(html, 600));
    }

    private String resolveNextUrl(Document doc, Elements nextEls, Rule.Chapter r) {
        // 从 JS 获取下一页链接
        if (r.getNextPageInJs() != null) {
            return HtmlExtractor.extract(doc, r.getNextPageInJs(), ContentType.HTML);
        }
        if (nextEls.isEmpty()) {
            // 没有“下一页”按钮，通常是本章最后一页，属正常结束；
            // 若此处正文异常偏短，则由上层的正文过短诊断日志提示可能被限流
            LogUtils.logByProgressbar(LogLevel.INFO, config.getEnableProgressbar(),
                    "分页章节未找到下一页按钮，按本章最后一页处理。链接: {}", doc.baseUri());
            return null;
        }
        // 从按钮获取下一页链接
        return nextEls.first().absUrl("href");
    }

    private boolean isLastPage(String nextUrl, Elements nextEls, Rule.Chapter r) {
        if (nextUrl == null) {
            return true;
        }

        // 正则判断是否为章节最后一页
        boolean endByChapterRule = r.getNextChapterLink() != null && nextUrl.matches(r.getNextChapterLink());
        // 通用规则，大多数分页的 url 以 "_个位数字.html" 结尾。&& 部分网站会用“下一章”代替“下一页”
        boolean genericEnd = !nextUrl.matches(".*[-_]\\d\\.html") && nextEls.text().matches(".*(下一章|没有了|>>|书末页).*");

        return endByChapterRule || genericEnd;
    }

}
