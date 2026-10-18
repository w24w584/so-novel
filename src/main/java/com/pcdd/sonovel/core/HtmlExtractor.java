package com.pcdd.sonovel.core;

import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.dsl.DslBootstrap;
import com.pcdd.sonovel.dsl.core.StepEngine;
import com.pcdd.sonovel.model.ContentType;
import lombok.experimental.UtilityClass;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * @author pcdd
 * Created at 2026/7/1
 */
@UtilityClass
public class HtmlExtractor {

    /**
     * DSL 执行引擎
     */
    private final StepEngine DSL_ENGINE = DslBootstrap.createEngine();

    /**
     * 执行 DSL 并返回处理结果，等价于 func(input)
     *
     * @param dsl 格式：xxx@js:code;@java:code
     */
    public String executeDsl(String dsl, String input) {
        return DSL_ENGINE.run(dsl, input);
    }

    /**
     * 根据 CSS Selector 或 XPath 查询元素
     * <p>
     * 等价于 document.select(query) | document.selectXpath(query)
     */
    public Elements select(Element el, String query) {
        if (el == null || StrUtil.isBlank(query)) return new Elements();
        String selector = query.trim();

        // XPath：原样走 XPath，仅剥离末尾用于提取内容的属性后缀，保留内部 [@attr] 语法
        boolean isXPath = selector.startsWith("/") || selector.startsWith("(");
        if (isXPath) {
            selector = selector.replaceAll("@(textNodes|text|html|href|src|content|value)\\s*$", "");
            while (selector.endsWith("/") && selector.length() > 1) {
                selector = selector.substring(0, selector.length() - 1);
            }
            if (selector.isEmpty()) return new Elements();
            return el.selectXpath(selector);
        }

        // CSS：以 @ 分隔符提取选择器与属性后缀
        selector = StrUtil.subBefore(selector, "@", false);
        return el.select(selector);
    }

    public String extract(Element el, String query) {
        return extract(el, query, getContentType(query));
    }

    /**
     * 查询元素并提取内容，同时支持 DSL 后置处理
     * <p>
     * 执行流程：
     * 1. 解析 selector（CSS/XPath）
     * 2. 提取内容（text/html/attr）
     * 3. 交由 DSL 引擎进行二次处理
     */
    public String extract(Element el, String query, ContentType contentType) {
        if (el == null || StrUtil.isBlank(query) || contentType == null) {
            return null;
        }

        Elements elements = select(el, query);
        if (elements.isEmpty()) return "";


        String raw = elements.size() == 1
                ? getContent(elements.first(), contentType)
                : getContent(elements, contentType);

        // DSL 二次处理（如 JS / Java / 自定义脚本）
        return executeDsl(query, raw);
    }

    /**
     * 获取元素内容 + 可选 DSL 处理，等价于 func(element.(text|html|attr)())
     * <p>
     * 执行流程：
     * 1. 已有 Element
     * 2. 提取内容（text/html/attr）
     * 3. 交由 DSL 引擎进行二次处理
     */
    public String extractContent(Element el, String query, ContentType contentType) {
        // 先获取元素的内容
        String value = getContent(el, contentType);
        // 如果 query 包含 @js、@java，调用它
        return StrUtil.isNotEmpty(query) ? executeDsl(query, value) : value;
    }

    private ContentType getContentType(String query) {
        if (StrUtil.isBlank(query)) return null;
        if (query.contains("@href")) return ContentType.ATTR_HREF;
        if (query.contains("@src")) return ContentType.ATTR_SRC;
        // 仅按结尾后缀匹配，避免误伤 XPath 中的 [@value="..."] 谓词
        if (query.endsWith("@content")) return ContentType.ATTR_CONTENT;
        if (query.endsWith("@value")) return ContentType.ATTR_VALUE;
        if (query.startsWith("meta[")) return ContentType.ATTR_CONTENT;
        return ContentType.TEXT;
    }

    /**
     * 提取元素内容（统一入口）
     */
    private String getContent(Object obj, ContentType type) {
        if (obj instanceof Element el) {
            return switch (type) {
                case TEXT -> el.text();
                case HTML -> el.html();
                case ATTR_SRC, ATTR_HREF -> el.absUrl(type.getValue());
                // 切勿改为 absUrl
                case ATTR_CONTENT, ATTR_VALUE -> el.attr(type.getValue());
            };
        }

        if (obj instanceof Elements els) {
            return switch (type) {
                case TEXT -> els.text();
                case HTML -> els.html();
                // 与单元素分支保持一致：多个匹配时也需返回绝对地址，
                // 否则 Elements.attr 只会给出相对地址，导致分页链接被 Validator.isUrl 判为非法而中断翻页
                case ATTR_SRC, ATTR_HREF -> els.isEmpty() ? "" : els.first().absUrl(type.getValue());
                // 切勿改为 absUrl
                case ATTR_CONTENT, ATTR_VALUE -> els.attr(type.getValue());
            };
        }

        return "";
    }

}