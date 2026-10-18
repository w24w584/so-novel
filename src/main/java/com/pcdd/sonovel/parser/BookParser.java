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

/**
 * @author pcdd
 * Created at 2024/3/17
 */
public class BookParser extends Source {

    public BookParser(AppConfig config) {
        super(config);
    }

    @SneakyThrows
    public Book parse(String url) {
        Book r = this.rule.getBook();
        Assert.notNull(r, "书源缺少详情页规则，无法解析书籍信息：{}", this.rule.getName());
        Document document = fetchDocument(url, r.getTimeout(), r.getBaseUri());
        document = handleCloudflareBypass(document, url, "详情页");

        String bookName = HtmlExtractor.extract(document, r.getBookName());
        String author = HtmlExtractor.extract(document, r.getAuthor());
        Assert.notBlank(bookName, "详情页书名不能为空！DOM:\n{}\n", document);
        String defaultCoverUrl = HtmlExtractor.extract(document, r.getCoverUrl());

        // 部分书源的 meta 格式不标准，需自定义 meta 书源规则
        Book book = new Book();
        book.setUrl(url);
        book.setBookName(bookName);
        // 分享书源常无作者信息，缺失时允许为空，不能因此中断整本下载
        book.setAuthor(StrUtil.nullToEmpty(author).replace("作者：", ""));
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

}
