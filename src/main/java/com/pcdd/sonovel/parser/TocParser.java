package com.pcdd.sonovel.parser;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.pcdd.sonovel.core.HtmlExtractor;
import com.pcdd.sonovel.core.Source;
import com.pcdd.sonovel.model.*;
import com.pcdd.sonovel.utils.CrawlUtils;
import com.pcdd.sonovel.utils.LegadoJsRuntime;
import com.pcdd.sonovel.utils.VirtualThreadLimiter;
import lombok.SneakyThrows;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pcdd.sonovel.model.ContentType.ATTR_HREF;
import static com.pcdd.sonovel.model.ContentType.ATTR_VALUE;

public class TocParser extends Source {

    /** 目录自动翻页的页数上限与总耗时预算，防止站点异常时把整个下载拖到不可控 */
    private static final int MAX_PAGINATION_PAGES = 500;
    private static final long PAGINATION_BUDGET_MS = 90_000L;

    public TocParser(AppConfig config) {
        super(config);
    }

    /**
     * 解析全部章节
     */
    public List<Chapter> parseAll(String url) {
        return parse(url, 1, Integer.MAX_VALUE);
    }

    /**
     * 解析指定范围章节
     *
     * @param url 详情页
     */
    @SneakyThrows
    public List<Chapter> parse(String url, int start, int end) {
        Rule.Toc ruleToc = this.rule.getToc();
        if (ruleToc == null) {
            Console.error("<== 书源 {} 缺少目录规则，无法解析章节目录", this.rule.getName());
            return new ArrayList<>();
        }
        Rule.Book ruleBook = this.rule.getBook();
        Integer timeout = ruleBook != null ? ruleBook.getTimeout() : ruleToc.getTimeout();
        String bookUrlPattern = ruleBook == null ? null : StrUtil.subBefore(ruleBook.getUrl(), "@js:", false);
        String id = null;
        if (StrUtil.isNotBlank(bookUrlPattern)) {
            try {
                Matcher matcher = Pattern.compile(bookUrlPattern).matcher(url);
                if (matcher.groupCount() > 0 && matcher.find()) {
                    id = matcher.group(1);
                }
            } catch (Exception e) {
                // 分享书源会用源主页 URL 充当该书规则，含 ?/+/() 等字符时不是合法正则，忽略即可
                Console.log("<== 书源 {} 的 URL 模板不是合法正则，跳过 id 提取: {}", this.rule.getName(), bookUrlPattern);
            }
        }

        if (id != null) {
            ruleToc.setBaseUri(ruleToc.getBaseUri().formatted(id));
        }
        if (StrUtil.isNotEmpty(ruleToc.getUrl())) {
            url = ruleToc.getUrl().formatted(id);
        }

        // 分享书源：章节列表常位于独立的“目录页”(tocUrl，如 a#AllChapterList2@href、text.查看目录@href)，先从详情页解析出该链接作为目录页
        boolean resolvedCatalog = false;
        if (rule.isShareSource() && StrUtil.isBlank(ruleToc.getUrl())
                && ruleBook != null && StrUtil.isNotBlank(ruleBook.getLatestChapterUrl())) {
            Document detailsDoc = fetchDocument(url, ruleToc.getTimeout(), ruleToc.getBaseUri());
            detailsDoc = handleCloudflareBypass(detailsDoc, url, "目录页");
            String catalogHref = resolveDocHref(detailsDoc, ruleBook.getLatestChapterUrl());
            if (StrUtil.isNotBlank(catalogHref)) {
                Console.log("<== 详情页解析到目录页链接: {}", catalogHref);
                url = catalogHref;
                resolvedCatalog = true;
            } else {
                Console.error("<== 详情页未按选择器 {} 解析到目录页链接", ruleBook.getLatestChapterUrl());
            }
        }

        Set<String> urls = CollUtil.newLinkedHashSet(url);

        if (StrUtil.isBlank(ruleToc.getJsList())) {
            // JS 目录规则会自行处理翻页（如 JSONP 接口一次返回全部章节），故跳过下面两种分页补充
            if (StrUtil.isNotBlank(pageSelector(ruleToc))) {
                Document document = fetchDocument(url, timeout, ruleToc.getBaseUri());
                document = handleCloudflareBypass(document, url, "目录页");
                extractPaginationUrls(urls, document, ruleToc);
            } else {
                // 规则未配置分页时，自动跟随“下一页/下页/尾页”链接补充分页（兼容分享书源 nextTocUrl 为空的情况）
                collectGenericPagination(urls, url, ruleToc);
            }
        }

        List<Chapter> toc = parseToc(urls, start, end, ruleToc);

        // 通用兜底：所选页面解析不到任何章节，且未走 tocUrl，则尝试在详情页上自动找“目录/查看目录”链接跟随再解析一次
        if (CollUtil.isEmpty(toc) && StrUtil.isBlank(ruleToc.getUrl()) && !resolvedCatalog) {
            Document detailsDoc = fetchDocument(url, ruleToc.getTimeout(), ruleToc.getBaseUri());
            detailsDoc = handleCloudflareBypass(detailsDoc, url, "目录页");
            String catalogHref = findCatalogLink(detailsDoc);
            if (StrUtil.isNotBlank(catalogHref)) {
                Console.log("<== 详情页上自动找到目录页链接: {}", catalogHref);
                Set<String> catalogUrls = CollUtil.newLinkedHashSet(catalogHref);
                if (StrUtil.isNotBlank(pageSelector(ruleToc))) {
                    Document catalogDoc = fetchDocument(catalogHref, ruleToc.getTimeout(), ruleToc.getBaseUri());
                    extractPaginationUrls(catalogUrls, handleCloudflareBypass(catalogDoc, catalogHref, "目录页"), ruleToc);
                } else {
                    collectGenericPagination(catalogUrls, catalogHref, ruleToc);
                }
                toc = parseToc(catalogUrls, start, end, ruleToc);
            }
        }

        return toc;
    }

    /**
     * 规则未配置分页时，自动跟随目录页上的“下一页/下页/尾页”链接递归补齐全部目录分页。
     */
    @SneakyThrows
    private void collectGenericPagination(Set<String> urls, String firstUrl, Rule.Toc r) {
        Set<String> visited = new HashSet<>();
        String current = firstUrl;
        long deadline = System.currentTimeMillis() + PAGINATION_BUDGET_MS;
        for (int i = 0; i < MAX_PAGINATION_PAGES; i++) {
            if (System.currentTimeMillis() > deadline) {
                Console.log("<== 目录自动翻页超过 {}s 预算，停止继续翻页: {}",
                        PAGINATION_BUDGET_MS / 1000, firstUrl);
                return;
            }
            Document doc = fetchDocument(current, r.getTimeout(), r.getBaseUri());
            doc = handleCloudflareBypass(doc, current, "目录页");
            String next = findNextPageUrl(doc);
            if (StrUtil.isBlank(next)) return;
            // 页码需递增且未访问过，避免把“下一章/尾页跳转”误当目录翻页或陷入循环
            if (pageNo(next) <= pageNo(current) || !visited.add(next)) return;
            urls.add(next);
            current = next;
            Thread.sleep(CrawlUtils.randomInterval(config));
        }
    }

    private String findNextPageUrl(Document doc) {
        // 优先用 rel=next 语义标记，跨站最稳定
        Element relNext = doc.selectFirst("a[rel=next][href], link[rel=next][href]");
        if (relNext != null && StrUtil.isNotBlank(relNext.absUrl("href"))) {
            return relNext.absUrl("href");
        }
        // 其次按常见中文文案兜底
        for (Element a : doc.select("a[href]")) {
            String t = a.text().trim();
            if (t.contains("下一页") || t.contains("下页") || t.contains("尾页")
                    || t.contains("末页") || t.contains("后页")) {
                return a.absUrl("href");
            }
        }
        return "";
    }

    /**
     * 提取 URL 中的页码；无法识别时返回 0。
     */
    private int pageNo(String url) {
        if (url == null) return 0;
        int n = 0;
        Matcher m1 = Pattern.compile("[-/_\\.](\\d+)\\.html?$").matcher(url);
        if (m1.find()) n = Math.max(n, Integer.parseInt(m1.group(1)));
        Matcher m2 = Pattern.compile("(?:[?&]|/)(?:page|p)=?/?(\\d+)").matcher(url);
        if (m2.find()) n = Math.max(n, Integer.parseInt(m2.group(1)));
        return n;
    }

    /**
     * 按规则选择器从文档中解析 href，兼容 legado 的 text.标签@href 写法（按链接文字定位）。
     */
    private String resolveDocHref(Document doc, String selector) {
        if (doc == null || StrUtil.isBlank(selector)) return "";
        String s = selector.trim();
        Matcher m = Pattern.compile("^text\\.(.+?)@(href)$").matcher(s);
        if (m.find()) {
            String label = m.group(1).trim();
            Elements es = doc.selectXpath("//a[normalize-space(.)=\"" + label + "\"]");
            if (es.isEmpty()) es = doc.selectXpath("//a[contains(normalize-space(.),\"" + label + "\")]");
            return es.isEmpty() ? "" : es.first().absUrl("href");
        }
        return HtmlExtractor.extract(doc, s, ATTR_HREF);
    }

    /**
     * 详情页上自动推断“目录/查看目录”等链接（处理 tocUrl 为空的选择器失效场景）。
     */
    private String findCatalogLink(Document doc) {
        String[] labels = {"章节目录", "查看目录", "全部章节", "查看全部章节", "章节列表", "目录", "开始阅读"};
        for (String label : labels) {
            for (Element a : doc.select("a")) {
                String t = a.text().trim();
                if (label.equals(t) || t.endsWith(label)) {
                    String href = a.absUrl("href");
                    if (StrUtil.isNotBlank(href)) return href;
                }
            }
        }
        return "";
    }

    /** 目录分页选择器：分享书源用 nextPageUrl（来自 JSON 的 nextTocUrl），老规则回退到 nextPage */
    private String pageSelector(Rule.Toc r) {
        if (r == null) return "";
        if (StrUtil.isNotBlank(r.getNextPageUrl())) return r.getNextPageUrl();
        return StrUtil.nullToEmpty(r.getNextPage());
    }

    @SneakyThrows
    private void extractPaginationUrls(Set<String> urls, Document document, Rule.Toc r) {
        String pageSel = pageSelector(r);
        Elements elements = HtmlExtractor.select(document, pageSel);
        // 下拉菜单一次性获取所有分页 URL
        if (CollUtil.isNotEmpty(elements) && elements.hasAttr(ATTR_VALUE.getValue())) {
            String attrKey = elements.eachAttr(ATTR_HREF.getValue()).isEmpty()
                    ? ATTR_VALUE.getValue()
                    : ATTR_HREF.getValue();

            List<String> list = elements.stream()
                    .map(el -> el.absUrl(attrKey))
                    .toList();

            // 不能用 addAll：需保证后加入元素覆盖前面已有元素并保持顺序（个别书源 toc.url 不一定是首个 option）
            for (String s : list) {
                urls.remove(s);
                urls.add(s);
            }
            return;
        }
        // 下一页按钮递归获取（覆盖率极低，分页目录基本都通过下拉菜单一次性获取）
        Set<String> visited = new HashSet<>();
        String current = document.location();
        while (visited.size() < 500) {
            String href = HtmlExtractor.extract(document, pageSel, ATTR_HREF);
            String nextUrl = StrUtil.isNotEmpty(href) ? href
                    : HtmlExtractor.extract(document, pageSel, ATTR_VALUE);
            if (StrUtil.isBlank(nextUrl) || !Validator.isUrl(nextUrl)) break;
            // 页码需递增且未访问过，避免 contains 宽匹配误跟“下一页/尾页”或陷入循环
            if (pageNo(nextUrl) <= pageNo(current) || !visited.add(nextUrl)) break;
            if (!urls.add(nextUrl)) break;
            current = nextUrl;

            document = fetchDocument(nextUrl, r.getTimeout(), r.getBaseUri());
            document = handleCloudflareBypass(document, nextUrl, "目录页");
            Thread.sleep(CrawlUtils.randomInterval(config));
        }
    }

    private List<Element> extractElements(Document document, Rule.Toc r, String pageUrl) {
        // 目录规则依赖 JS（legado <js>/@js:）时，由 JS 运行时产出章节项
        if (StrUtil.isNotBlank(r.getJsList())) {
            return extractJsElements(document, r, pageUrl);
        }
        // 处理 ul
        if (StrUtil.isNotEmpty(r.getList())) {
            String tocHtml = HtmlExtractor.extract(document, r.getList(), ContentType.HTML);
            Document tocDoc = Jsoup.parse(tocHtml);
            return HtmlExtractor.select(tocDoc, r.getItem());
        }
        // 处理 ul > li > a
        return HtmlExtractor.select(document, r.getItem());
    }

    /**
     * 执行分享书源的目录 JS 规则（如 JSONP 目录接口），把返回的元素片段解析为章节项。
     */
    private List<Element> extractJsElements(Document document, Rule.Toc r, String pageUrl) {
        String base = StrUtil.emptyToDefault(pageUrl, r.getBaseUri());
        List<LegadoJsRuntime.TocItem> items = LegadoJsRuntime.evalChapterList(
                r.getJsList(), document.html(), base, r.getBaseUri(), r.getTimeout());

        List<Element> elements = new ArrayList<>();
        for (LegadoJsRuntime.TocItem item : items) {
            // 元素片段直接解析；仅给出 text/href 时构造等价链接，复用统一的章节提取逻辑
            String html = StrUtil.isNotBlank(item.html())
                    ? item.html()
                    : "<a href=\"" + HtmlUtil.escape(item.href()) + "\">" + HtmlUtil.escape(item.text()) + "</a>";
            Document fragment = Jsoup.parseBodyFragment(html, base);
            elements.addAll(fragment.body().children());
        }
        if (elements.isEmpty()) {
            Console.error("<== 目录 JS 规则未返回章节项（源: {}）", r.getBaseUri());
        }
        return elements;
    }

    private List<Chapter> parseToc(Set<String> urls, int start, int end, Rule.Toc r) {
        List<String> urlList = new ArrayList<>(urls);
        Map<Integer, List<Element>> pageElements = new ConcurrentHashMap<>();

        try (var limiter = new VirtualThreadLimiter(5)) {
            for (int i = 0; i < urlList.size(); i++) {
                int pageIndex = i;
                String pageUrl = urlList.get(i);
                limiter.submit(() -> {
                    try {
                        Document doc = fetchDocument(pageUrl, r.getTimeout(), r.getBaseUri());
                        doc = handleCloudflareBypass(doc, pageUrl, "目录页");
                        List<Element> els = extractElements(doc, r, pageUrl);
                        if (els.isEmpty()) {
                            els = harvestChapterLinks(doc);
                            if (!els.isEmpty()) {
                                Console.log("<== {} 自动识别章节链接 {} 条", pageUrl, els.size());
                            } else {
                                Console.error("<== 目录页 {} 未匹配到任何章节项（选择器: {}）", pageUrl, r.getItem());
                                dumpPageLinks(doc, pageUrl);
                            }
                        } else {
                            Console.log("<== 目录页 {} 匹配到 {} 个章节项", pageUrl, els.size());
                        }
                        pageElements.put(pageIndex, els);
                    } catch (Exception e) {
                        Console.error("目录页解析失败: {} - {}", pageUrl, e.getMessage());
                    }
                });
            }
        }

        List<Chapter> toc = new TocList();
        int orderNumber = 1;

        for (int i = 0; i < urlList.size(); i++) {
            List<Element> elements = pageElements.get(i);
            if (elements == null) continue;

            int minIndex = Math.min(end, elements.size());
            if (r.isDesc()) {
                for (int j = minIndex - 1; j >= start - 1; j--) addChapter(elements.get(j), toc, orderNumber++, r);
            } else {
                for (int j = start - 1; j < minIndex; j++) addChapter(elements.get(j), toc, orderNumber++, r);
            }
        }

        return toc;
    }

    /**
     * 兜底识别：当规则选择器失效时，按“/书号/章节号.html”（两段数字路径）特征自动收集章节链接。
     * 采用“按书号分组、取链接数最多的书号组”的方式，天然忽略分页(page-N)、book/、txt/ 等导航链接，
     * 即使目录处于分页页（URL 形如 /书号/page-N.html）也能正确识别。
     */
    private List<Element> harvestChapterLinks(Document doc) {
        List<Element> out = new ArrayList<>();
        if (doc == null) return out;
        Map<String, List<Element>> byBook = new LinkedHashMap<>();
        Map<String, Set<String>> seen = new HashMap<>();
        Pattern p = Pattern.compile(".*/(\\d+)/(\\d+)\\.html?$");
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            Matcher m = p.matcher(href);
            if (m.matches()) {
                String book = m.group(1);
                if (seen.computeIfAbsent(book, k -> new HashSet<>()).add(m.group(2))) {
                    byBook.computeIfAbsent(book, k -> new ArrayList<>()).add(a);
                }
            }
        }
        // 选取章节链接最多的书号组，并过滤掉仅 1~2 条（避免轮播/猜你喜欢误收）
        return byBook.values().stream()
                .filter(list -> list.size() >= 2)
                .max(Comparator.comparingInt(List::size))
                .orElse(out);
    }

    private void addChapter(Element el, List<Chapter> toc, int order, Rule.Toc r) {
        toc.add(Chapter.builder()
                .title(el.text())
                .url(extractChapterUrl(el, r.getNextPage()))
                .order(order)
                .build());
    }

    /**
     * 提取章节链接：兼容目录项自身带 href（super<a>），以及链接在子级 a 上（li>a）的情况。
     */
    private String extractChapterUrl(Element el, String query) {
        String href = HtmlExtractor.extractContent(el, query, ATTR_HREF);
        if (StrUtil.isNotBlank(href)) return href;
        Element a = el.selectFirst("a[href]");
        return a == null ? "" : a.absUrl("href");
    }

    /**
     * 目录页未匹配到时，打印页面中所有看起来像"章节链接"的元素、以及各分组的容器，用于定位真实 DOM 结构。
     */
    private void dumpPageLinks(Document doc, String pageUrl) {
        String base = this.rule.getUrl();
        String basePath = "";
        int slash = pageUrl.indexOf('/', pageUrl.indexOf("://") + 3);
        if (slash > 0) basePath = pageUrl.substring(0, slash);

        // 1. 打印所有超链接（文本 + 地址），过滤掉明显非章节的站点导航
        List<String> anchors = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            String text = a.text().trim();
            if (StrUtil.isBlank(text)) continue;
            if (text.matches("(?s).*\\d{3,}.*") || href.contains("/chapter") || href.startsWith(basePath + "/")
                    || href.matches(".*/\\d+(\\.html)?/?")) {
                anchors.add(text + "  =>  " + href);
            }
            if (anchors.size() >= 40) break;
        }
        Console.log("<== [诊断] {} 的候选章节链接 {} 条：{}", pageUrl, anchors.size(), String.join(" | ", anchors));

        // 2. 打印常见的章节列表容器（ul/dl/div），帮助确认章节所在父级
        List<String> groups = new ArrayList<>();
        for (Element el : doc.select("ul, dl, div")) {
            List<Element> links = el.select("a");
            long chaps = links.stream().filter(a -> a.text().matches(".*章.*|.*(?:节|回|话|卷).*")).count();
            String cls = StrUtil.emptyToDefault(el.className(), el.tagName());
            if (links.size() >= 5 && (chaps >= 3 || links.size() >= 20)) {
                groups.add(cls + "[" + links.size() + " 链接]");
            }
            if (groups.size() >= 8) break;
        }
        Console.log("<== [诊断] 可能的章节容器：{}", String.join(", ", groups));

        // 3. 打印第一个"第?节/第?章"链接的父级 HTML，确认章节项的真实包裹标签
        Element firstChapter = doc.select("a[href]").stream()
                .filter(a -> a.text().matches(".*(章|节|回|话|卷).*"))
                .findFirst()
                .orElse(null);
        if (firstChapter != null) {
            Element par = firstChapter;
            for (int i = 0; i < 3 && par.parent() != null; i++) par = par.parent();
            Console.log("<== [诊断] 章节项父级结构: {}", StrUtil.sub(par.outerHtml(), 0, 1500));
        }
    }

}