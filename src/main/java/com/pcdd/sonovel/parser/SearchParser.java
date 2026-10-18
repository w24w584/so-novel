package com.pcdd.sonovel.parser;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.ConsoleTable;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.http.Header;
import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.core.HtmlExtractor;
import com.pcdd.sonovel.core.Source;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.ContentType;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.Rule.Book;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.utils.ChineseConverter;
import com.pcdd.sonovel.utils.CrawlUtils;
import com.pcdd.sonovel.utils.JsCaller;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.fusesource.jansi.AnsiRenderer.render;

/**
 * @author pcdd
 * Created at 2024/3/23
 */
public class SearchParser extends Source {

    private static final int TEXT_LIMIT_LENGTH = 30;
    private final OkHttpClient httpClient = HttpClientContext.get();

    public SearchParser(AppConfig config) {
        super(config);
    }

    @SneakyThrows
    public List<SearchResult> parse(String keyword) {
        Rule.Search r = this.rule.getSearch();

        if (r == null) {
            Console.log(render("<== 书源 {} 不支持搜索", "yellow"), config.getSourceId());
            return Collections.emptyList();
        }
        if (this.rule.isDisabled()) {
            Console.error(render("<== 书源 {} ({}) 已被禁用", "yellow"), this.rule.getId(), this.rule.getName());
            return Collections.emptyList();
        }

        Document document;
        try {
            String searchUrl = processUrl(r.getUrl(), keyword);
            URI uri = URI.create(searchUrl);
            if (!uri.isAbsolute()) {
                uri = URI.create(this.rule.getUrl()).resolve(uri);
                searchUrl = uri.toString();
            }
            String referer = uri.getScheme() + "://" + uri.getAuthority();
            Request.Builder builder = new Request.Builder()
                    .addHeader(Header.REFERER.toString(), referer)
                    .url(searchUrl);

            if (r.getHeaders() != null) {
                r.getHeaders().forEach(builder::addHeader);
            }

            if (StrUtil.isNotBlank(r.getCookies())) {
                builder.addHeader("Cookie", r.getCookies());
            }
            if ("post".equalsIgnoreCase(r.getMethod())) {
                builder = builder.post(CrawlUtils.buildData(r.getData(), keyword));
            }

            try (Response resp = CrawlUtils.request(httpClient, builder, r.getTimeout())) {
                String body = processResultWithJs(decodeBody(resp, r.getCharset()), r.getResult());
                document = Jsoup.parse(body, r.getBaseUri());
            }

            document = handleCloudflareBypass(document, searchUrl, "搜索页");

        } catch (Exception e) {
            Console.error(render("<== 书源 {} ({}) 搜索解析出错: {}", "red"),
                    this.rule.getId(), this.rule.getName(), e.getMessage());
            return Collections.emptyList();
        }

        List<SearchResult> firstPageResults = getSearchResults(document, r);
        // 搜索结果无分页
        if (StrUtil.isBlank(r.getNextPage())) {
            return firstPageResults;
        }
        // 注意，css 或 xpath 的查询结果必须为多个 a 元素
        Elements nextPageUrls = HtmlExtractor.select(document, r.getNextPage());
        // 只有一页时，底部可能没有分页菜单
        if (nextPageUrls.isEmpty()) {
            return firstPageResults;
        }
        // 分页搜索结果的 URL，不含首页
        Set<String> urls = new LinkedHashSet<>();
        // 一次性获取分页 URL，不考虑逐个点击下一页的情况
        for (Element e : nextPageUrls) {
            String href = e.absUrl("href");
            // 中文解码，便于调试
            urls.add(URLUtil.decode(href));
        }
        // 使用并行流处理分页 URL
        List<SearchResult> additionalResults = urls.parallelStream()
                .flatMap(url -> getSearchResults(fetchDocument(url, r), r).stream())
                .toList();
        // 合并，不去重（去重用 union）
        List<SearchResult> searchResults = CollUtil.unionAll(firstPageResults, additionalResults);
        int limit = config.getSearchLimit() == -1 ? Integer.MAX_VALUE : config.getSearchLimit();
        // TODO 优化，需要几条获取几条，而不是一次性获取然后截取
        return CollUtil.sub(searchResults, 0, limit);
    }

    @SneakyThrows
    private Document fetchDocument(String url, Rule.Search r) {
        try (Response resp = CrawlUtils.request(httpClient, url, r.getTimeout())) {
            String body = processResultWithJs(resp.peekBody(Long.MAX_VALUE).string(), r.getResult());
            return Jsoup.parse(body, r.getBaseUri());
        }
    }

    private List<SearchResult> getSearchResults(Document document, Rule.Search r) {
        List<SearchResult> list = new ArrayList<>();
        try {
            String resultSelector = stripJs(r.getResult());
            Elements resultEls = HtmlExtractor.select(document, resultSelector);

            // 部分书源完全匹配时会直接跳转到详情页（搜索结果为空 && 书名不为空），故需要构造搜索结果
            if (resultEls.isEmpty() && this.rule.getBook() != null
                    && StrUtil.isNotBlank(this.rule.getBook().getBookName())
                    && !HtmlExtractor.select(document, this.rule.getBook().getBookName()).isEmpty()) {
                String bookUrl = document.location();
                BookParser bookParser = new BookParser(config);
                Book book = bookParser.parse(bookUrl);

                if (StrUtil.isBlank(book.getBookName())) {
                    return Collections.emptyList();
                }

                SearchResult sr = SearchResult.builder()
                        .sourceId(this.rule.getId())
                        .sourceName(this.rule.getName())
                        .sourceGroup(this.rule.getSourceGroup())
                        .url(bookUrl)
                        .bookName(book.getBookName())
                        .author(book.getAuthor())
                        .latestChapter(book.getLatestChapter())
                        .lastUpdateTime(book.getLastUpdateTime())
                        .build();
                list.add(ChineseConverter.convert(sr, this.rule.getLanguage(), config.getLanguage()));
                Thread.sleep(CrawlUtils.randomInterval(config));
                return list;
            }
            // 只获取前 N 条搜索记录
            List<Element> limitResultEls = resultEls.stream()
                    .filter(e -> StrUtil.isNotEmpty(HtmlExtractor.extract(e, r.getBookName())))
                    .limit(config.getSearchLimit() == -1 ? Integer.MAX_VALUE : config.getSearchLimit())
                    .toList();

            for (Element el : limitResultEls) {
                // jsoup 不支持一次性获取属性的值
                String href = extractBookUrl(el, r);
                String bookName = HtmlExtractor.extract(el, r.getBookName());
                if (bookName.isEmpty()) continue;
                if (StrUtil.isBlank(href)) {
                    Console.error("<== 书源 {} ({}) 搜索结果“{}”没有提取到书籍详情链接，已跳过",
                            this.rule.getId(), this.rule.getName(), bookName);
                    continue;
                }

                SearchResult sr = SearchResult.builder()
                        .sourceId(this.rule.getId())
                        .sourceName(this.rule.getName())
                        .sourceGroup(this.rule.getSourceGroup())
                        .url(href)
                        .bookName(bookName)
                        // 以下为非必须属性
                        .author(HtmlExtractor.extract(el, r.getAuthor()))
                        .category(HtmlExtractor.extract(el, r.getCategory()))
                        .latestChapter(HtmlExtractor.extract(el, r.getLatestChapter()))
                        .lastUpdateTime(HtmlExtractor.extract(el, r.getLastUpdateTime()))
                        .status(HtmlExtractor.extract(el, r.getStatus()))
                        .wordCount(HtmlExtractor.extract(el, r.getWordCount()))
                        .build();

                list.add(ChineseConverter.convert(sr, this.rule.getLanguage(), config.getLanguage()));
            }
        } catch (Exception e) {
            Console.error(e);
            return Collections.emptyList();
        }

        return list;
    }

    public void printSearchResult(List<SearchResult> results) {
        Rule.Search r = this.rule.getSearch();
        if (r == null || CollUtil.isEmpty(results)) return;

        ConsoleTable consoleTable = ConsoleTable.create();
        // 根据首个结果判断哪些属性列存在，统一构造表头
        SearchResult first = results.getFirst();
        List<String> cols = new ArrayList<>();
        List<String> titles = ListUtil.toList("序号", "书名");
        if (addColumnIfNotEmpty(cols, r.getAuthor(), first.getAuthor())) titles.add("作者");
        if (addColumnIfNotEmpty(cols, r.getCategory(), first.getCategory())) titles.add("类别");
        if (addColumnIfNotEmpty(cols, r.getLatestChapter(), first.getLatestChapter())) titles.add("最新章节");
        if (addColumnIfNotEmpty(cols, r.getLastUpdateTime(), first.getLastUpdateTime())) titles.add("更新时间");
        if (addColumnIfNotEmpty(cols, r.getStatus(), first.getStatus())) titles.add("状态");
        if (addColumnIfNotEmpty(cols, r.getWordCount(), first.getWordCount())) titles.add("总字数");
        consoleTable.addHeader(ArrayUtil.toArray(titles, String.class));

        for (int i = 1; i <= results.size(); i++) {
            SearchResult sr = results.get(i - 1);
            List<String> row = ListUtil.toList(String.valueOf(i), sr.getBookName());
            addColumnIfNotEmpty(row, r.getAuthor(), sr.getAuthor());
            addColumnIfNotEmpty(row, r.getCategory(), sr.getCategory());
            addColumnIfNotEmpty(row, r.getLatestChapter(), StrUtil.subPre(sr.getLatestChapter(), TEXT_LIMIT_LENGTH));
            addColumnIfNotEmpty(row, r.getLastUpdateTime(), ReUtil.replaceAll(sr.getLastUpdateTime(), "\\d{2}:\\d{2}(:\\d{2})?", ""));
            addColumnIfNotEmpty(row, r.getStatus(), sr.getStatus());
            addColumnIfNotEmpty(row, r.getWordCount(), sr.getWordCount());
            consoleTable.addBody(ArrayUtil.toArray(row, String.class));
        }

        Console.table(consoleTable);
    }

    // 辅助方法：只有非空的情况下才添加列
    private boolean addColumnIfNotEmpty(List<String> list, String rule, String value) {
        if (StrUtil.isNotEmpty(rule)) {
            list.add(StrUtil.isNotEmpty(value) ? value : "");
            return true;
        }
        return false;
    }

    public static void printAggregateSearchResult(List<SearchResult> results) {
        ConsoleTable consoleTable = ConsoleTable.create().addHeader("序号", "书名", "作者", "最新章节", "最后更新时间", "书源");
        for (int i = 1; i <= results.size(); i++) {
            SearchResult sr = results.get(i - 1);

            if (sr.getLatestChapter() == null) {
                sr.setLatestChapter("/");
            } else {
                sr.setLatestChapter(StrUtil.subPre(sr.getLatestChapter(), TEXT_LIMIT_LENGTH));
            }
            if (sr.getLastUpdateTime() == null) {
                sr.setLastUpdateTime("/");
            }

            consoleTable.addBody(
                    String.valueOf(i),
                    sr.getBookName(),
                    sr.getAuthor(),
                    sr.getLatestChapter(),
                    ReUtil.replaceAll(sr.getLastUpdateTime(), "\\d{2}:\\d{2}(:\\d{2})?", ""),
                    String.valueOf(sr.getSourceId())
            );
        }
        Console.table(consoleTable);
    }

    // 若 url 含 @js:，则 JS 接收 keyword 返回完整 URL；否则直接格式化
    private String processUrl(String url, String keyword) {
        if (url != null && url.contains("@js:")) {
            String jsCode = StrUtil.subAfter(url, "@js:", false);
            return executeShareSearchJs(jsCode, keyword);
        }
        String searchTerm = this.rule.isShareSource()
                ? URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20")
                : keyword;
        return url.replace("%s", searchTerm);
    }

    /**
     * 解码搜索响应体：优先按页面/响应头声明的字符集解码（浏览器行为），缺失时回退书源声明的 charset，最后才 UTF-8。
     * 避免站点与书源标注不一致时（如书源标 gbk、页面实为 UTF-8）出现乱码。
     */
    @SneakyThrows
    private String decodeBody(Response resp, String charset) {
        byte[] bytes = resp.peekBody(Long.MAX_VALUE).bytes();
        // 1. 页面 <meta> 声明的 charset 最可靠
        Charset meta = detectMetaCharset(bytes);
        if (meta != null) return new String(bytes, meta);
        // 2. 响应头 Content-Type 声明的 charset
        okhttp3.MediaType mt = resp.body() == null ? null : resp.body().contentType();
        if (mt != null && mt.charset() != null) return new String(bytes, mt.charset());
        // 3. 书源声明的 charset
        if (charset != null && Charset.isSupported(charset)) return new String(bytes, Charset.forName(charset));
        // 4. 兜底 UTF-8
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 从响应体前几个字节中探测 <meta> 声明的字符集。
     */
    private Charset detectMetaCharset(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?i)<meta[^>]*charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-]+)",
                Pattern.DOTALL).matcher(head);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 从单个搜索结果元素中提取详情链接。
     * 兼容两种常见结构：书名元素自身带 href（如 p.bookname > a 的 h3/a），以及书名元素为普通元素、链接在子级 a 上
     * （如 <p class="bookname"><a href>书名</a></p>，此时对 p 取 href 拿不到）。
     */
    private String extractBookUrl(Element el, Rule.Search r) {
        String q = r.getBookName();
        String href = HtmlExtractor.extract(el, q, ContentType.ATTR_HREF);
        if (StrUtil.isNotBlank(href)) return href;

        // 优先在书名元素内部找链接；书名元素未命中时再在整个结果项(el=li)里找
        String nameSel = q;
        int at = q.indexOf('@');
        if (at > 0) nameSel = q.substring(0, at);
        Element link = null;
        try {
            Element nameEl = el.selectFirst(nameSel);
            if (nameEl != null) link = nameEl.selectFirst("a[href]");
        } catch (Exception ignored) {
        }
        if (link == null) link = el.selectFirst("a[href]");
        return link == null ? "" : link.absUrl("href");
    }

    @SneakyThrows
    private String executeShareSearchJs(String jsCode, String keyword) {
        String sourceUrl = this.rule.getUrl();
        String sourceKey = sourceUrl;
        String wrappedJs = """
                var url_result = null;
                var key = %s;
                var page = 1;
                var source = {
                    bookSourceUrl: %s,
                    key: %s,
                    getKey: function() { return %s; }
                };
                var java = {
                    _store: {},
                    put: function(k, v) { this._store[k] = v; },
                    get: function(k) { return this._store[k]; },
                    log: function() {}
                };
                var cookie = {
                    removeCookie: function() {},
                    getCookie: function() { return ''; }
                };
                var result = (function() {
                    %s
                })();
                if (java._store.url) {
                    url_result = java._store.url;
                } else if (result) {
                    url_result = result;
                }
                return url_result;
                """.formatted(
                JSONUtil.toJsonStr(keyword),
                JSONUtil.toJsonStr(sourceUrl),
                JSONUtil.toJsonStr(sourceKey),
                JSONUtil.toJsonStr(sourceKey),
                jsCode);
        try {
            return JsCaller.eval(wrappedJs);
        } catch (Exception e) {
            Console.error("<== 书源 {} ({}) searchUrl @js: 执行失败: {}",
                    this.rule.getId(), this.rule.getName(), e.getMessage());
            return null;
        }
    }

    // 若 result 含 @js:，则 JS 接收响应体并返回转换后的 HTML
    private static String processResultWithJs(String body, String result) {
        if (result != null && result.contains("@js:")) {
            return JsCaller.call(StrUtil.subAfter(result, "@js:", false), body);
        }
        return body;
    }

    // 剥离 result 中的 @js: 部分，仅保留 CSS/XPath 选择器
    private static String stripJs(String result) {
        if (result != null && result.contains("@js:")) {
            return StrUtil.subBefore(result, "@js:", false);
        }
        return result;
    }

}