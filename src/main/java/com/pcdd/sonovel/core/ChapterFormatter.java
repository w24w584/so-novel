package com.pcdd.sonovel.core;

import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.utils.HtmlUtils;
import lombok.AllArgsConstructor;

/**
 * @author pcdd
 * Created at 2024/12/4
 */
@AllArgsConstructor
public class ChapterFormatter {

    private final AppConfig config;

    /**
     * 格式化正文排版
     */
    public String format(String content) {
        Rule.Chapter r = new Source(config).rule.getChapter();
        // 这里的 content 不应被 cleanBlank（不能为  <divclass="xxx">），否则 clearAllAttributes 无效
        content = HtmlUtils.clearAllAttributes(content);

        // 标签闭合，例如：<tag>段落内容</tag>
        if (r.isParagraphTagClosed()) {
            // 非 <p> 闭合标签（例如 <span>段落</span>）替换为 <p>
            return content.replaceAll("<(?!p\\b)([^>]+)>(.*?)</\\1>", "<p>$2</p>");
        }

        // 未配置分段标签时，正文若已含 <p> 结构则原样返回；否则整段包一层 <p>。
        // 避免 split(null) 抛空指针把正文全部丢掉。
        String paragraphTag = r.getParagraphTag();
        if (StrUtil.isBlank(paragraphTag)) {
            if (!content.contains("<p>") && !content.contains("<p ")) {
                return "<p>" + content + "</p>";
            }
            return content;
        }

        // 标签不闭合，例如：段落1<br><br>段落2
        StringBuilder contentBuilder = new StringBuilder();
        for (String line : content.split(paragraphTag)) {
            if (!line.isBlank()) {
                contentBuilder.append("<p>").append(line).append("</p>");
            }
        }

        return contentBuilder.toString();
    }

}