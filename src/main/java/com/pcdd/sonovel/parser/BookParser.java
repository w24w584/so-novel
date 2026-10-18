package com.pcdd.sonovel.parser;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.HtmlExtractor;
import com.pcdd.sonovel.core.Source;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Rule.Book;
import com.pcdd.sonovel.utils.ChineseConverter;
import com.pcdd.sonovel.utils.CoverUpdater;
import lombok.SneakyThrows;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pcdd
 * Created at 2024/3/17
 */
public class BookParser extends Source {

    /** 页面元信息中的 “作者:xxx”，兜底识别作者 */
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("作者\\s*[:：]\\s*([^,，;；|/<>\\s]{1,20})");

    public BookParser(AppConfig config) {
        super(config);
    }

    public Book parse(String url) {
        return parse(url, null, null);
    }

    /**
     * 解析详情页
     *
     * @param defaultBookName 搜索结果中的书名，详情页书名规则缺失或失效时兜底
     * @param defaultAuthor   搜索结果中的作者，详情页作者规则缺失或失效时兜底
     */
    @SneakyThrows
    public Book parse(String url, String defaultBookName, String defaultAuthor) {
        Book r = this.rule.getBook();
        Assert.notNull(r, "书源缺少详情页规则，无法解析书籍信息：{}", this.rule.getName());
        Document document = fetchDocument(url, r.getTimeout(), r.getBaseUri());
        document = handleCloudflareBypass(document, url, "详情页");

        String bookName = HtmlExtractor.extract(document, r.getBookName());
        String author = HtmlExtractor.extract(document, r.getAuthor());
        // 分享书源的 ruleBookInfo 常为空或选择器失效（如目录以 JS 规则提供的书源），
        // 此时依次退回搜索结果中的书名（legado 语义）与页面自动识别，避免整本下载中断
        if (StrUtil.isBlank(bookName)) {
            bookName = StrUtil.blankToDefault(defaultBookName, detectBookName(document));
        }
        if (StrUtil.isBlank(author)) {
            author = StrUtil.blankToDefault(defaultAuthor, detectAuthor(document));
        }
        Assert.notBlank(bookName, """
                详情页书名不能为空！
                1. 检查 config.ini 的 source-id 是否与书籍详情页所属书源一致
                2. 该分享书源的详情页书名规则可能已失效
                DOM:
                {}
                """, document);
        String defaultCoverUrl = HtmlExtractor.extract(document, r.getCoverUrl());

        // 部分书源的 meta 格式不标准，需自定义 meta 书源规则
        Book book = new Book();
        book.setUrl(url);
        book.setBookName(bookName);
        // 分享书源常无作者信息，缺失时允许为空，不能因此中断整本下载
        book.setAuthor(StrUtil.nullToEmpty(author).replace("作者：", "").replace("作者:", "").trim());
        book.setIntro(StrUtil.cleanBlank(HtmlExtractor.extract(document, r.getIntro())));
        // 代理 IP 容易被起点等网站屏蔽，故使用源站封面
        book.setCoverUrl(this.rule.isNeedProxy() ? defaultCoverUrl : CoverUpdater.fetchCover(book, defaultCoverUrl));
        book.setCategory(HtmlExtractor.extract(document, r.getCategory()));
        book.setLatestChapter(HtmlExtractor.extract(document, r.getLatestChapter()));
        book.setLatestChapterUrl(HtmlExtractor.extract(document, r.getLatestChapterUrl()));
        book.setLastUpdateTime(HtmlExtractor.extract(document, r.getLastUpdateTime()).replaceAll("(更新时间|最后更新)：", ""));
        book.setStatus(HtmlExtractor.extract(document, r.getStatus()));

        return ChineseConverter.convert(book, this.rule.getLanguage(), config.getLanguage());
    }

    /**
     * 详情页书名规则失效时，从页面元信息自动识别书名：og 元信息 → h1 → keywords → title
     */
    private String detectBookName(Document document) {
        String meta = metaContent(document, "meta[property=og:novel:book_name]", "meta[property=og:book_name]",
                "meta[name=book_name]", "meta[name=og:novel:book_name]");
        if (StrUtil.isNotBlank(meta)) return meta;

        Element h1 = document.selectFirst("h1");
        if (h1 != null && StrUtil.isNotBlank(h1.text())) return h1.text().trim();

        // 站点常把 “书名,作者” 写在 keywords 里
        String keywords = StrUtil.subBefore(metaContent(document, "meta[name=keywords]").replace("，", ","), ",", false).trim();
        if (StrUtil.isNotBlank(keywords) && keywords.length() <= 30) return keywords;

        String title = StrUtil.nullToEmpty(document.title()).trim();
        if (title.isEmpty()) return "";
        // 书名通常在标题最前面，去掉站点名（分隔符之后的内容）与常见后缀
        for (String separator : new String[]{"_", " - ", "—", "|", "：", "【"}) {
            int index = title.indexOf(separator);
            if (index > 0) title = title.substring(0, index);
        }
        title = title.replaceAll("[《》]", "").trim();
        return title.replaceAll("(最新章节列表|最新章节|全文阅读|在线阅读|无弹窗|免费阅读|小说)+$", "").trim();
    }

    /**
     * 详情页作者规则失效时，从页面元信息自动识别作者：og 元信息 → description/keywords 中的“作者:”
     */
    private String detectAuthor(Document document) {
        String meta = metaContent(document, "meta[property=og:novel:author]", "meta[name=author]");
        // 部分站点写作 “作者：xxx”
        if (StrUtil.isNotBlank(meta)) return meta.replace("作者：", "").replace("作者:", "").trim();

        for (String text : new String[]{metaContent(document, "meta[name=description]"),
                metaContent(document, "meta[name=keywords]")}) {
            Matcher matcher = AUTHOR_PATTERN.matcher(text);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return "";
    }

    private String metaContent(Document document, String... selectors) {
        for (String selector : selectors) {
            Element el = document.selectFirst(selector);
            if (el != null && StrUtil.isNotBlank(el.attr("content"))) {
                return el.attr("content").trim();
            }
        }
        return "";
    }

}
