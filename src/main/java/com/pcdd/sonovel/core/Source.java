package com.pcdd.sonovel.core;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.utils.CrawlUtils;
import com.pcdd.sonovel.utils.SourceUtils;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author pcdd
 * Created at 2024/3/27
 */
public class Source {

    public final Rule rule;
    public final AppConfig config;

    // 配置文件读取书源 id
    public Source(AppConfig config) {
        this(SourceUtils.getRule(config.getSourceId()), config);
    }

    public Source(int sourceId) {
        this(SourceUtils.getRule(sourceId), null);
    }

    public Source(Rule rule, AppConfig config) {
        this.rule = rule;
        // 使用配置文件中的配置，若为空则使用默认配置
        this.config = Opt.ofNullable(config).orElse(AppConfigLoader.APP_CONFIG);
        applyCrawlSettings(rule.getCrawl());
    }

    private void applyCrawlSettings(Rule.Crawl crawl) {
        if (crawl == null) return;
        Opt.ofNullable(crawl.getConcurrency()).ifPresent(this.config::setConcurrency);
        Opt.ofNullable(crawl.getMinInterval()).ifPresent(this.config::setMinInterval);
        Opt.ofNullable(crawl.getMaxInterval()).ifPresent(this.config::setMaxInterval);
        Opt.ofNullable(crawl.getMaxAttempts()).ifPresent(this.config::setMaxRetries);
        Opt.ofNullable(crawl.getRetryMinInterval()).ifPresent(this.config::setRetryMinInterval);
        Opt.ofNullable(crawl.getRetryMaxInterval()).ifPresent(this.config::setRetryMaxInterval);
    }

    /**
     * 请求 URL 并按需自动探测编码解析为 Document
     *
     * @param baseUri 用于计算相对链接的绝对地址
     */
    protected Document fetchDocument(String url, int timeout, String baseUri) throws IOException {
        try (Response resp = CrawlUtils.request(HttpClientContext.get(), url, timeout);
             InputStream is = resp.body().byteStream()) {
            return Jsoup.parse(is, null, baseUri);
        }
    }

    /**
     * 检测并尝试绕过 Cloudflare 真人验证
     *
     * @param pageLabel 页面类型（详情页/目录页/章节页/搜索页），仅用于日志
     */
    protected Document handleCloudflareBypass(Document document, String url, String pageLabel) {
        if (!CrawlUtils.hasCf(document)) return document;

        Assert.isTrue(StrUtil.isNotEmpty(config.getCfBypass()),
                "🤖 检测到%s {} 存在 Cloudflare 真人验证，但未设置 cf-bypass 配置项，故跳过", pageLabel, url);
        Console.log("🤖 检测到%s {} 存在 Cloudflare 真人验证，正在尝试绕过...", pageLabel, url);

        String html = HttpUtil.get("%s/html?url=%s".formatted(config.getCfBypass(), url));
        return Jsoup.parse(html);
    }

}
