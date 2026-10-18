package com.pcdd.sonovel.core;

import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.dsl.DslBootstrap;
import com.pcdd.sonovel.dsl.core.StepEngine;
import com.pcdd.sonovel.model.ContentType;
import com.pcdd.sonovel.utils.EnvUtils;
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
        // legado 的 ##正则##替换 属于取值后的后处理，查询时应先剥离
        String selector = baseQuery(query.trim());
        if (selector.isEmpty()) return new Elements();

        // XPath：原样走 XPath，仅剥离末尾用于提取内容的属性后缀，保留内部 [@attr] 语法
        boolean isXPath = selector.startsWith("/") || selector.startsWith("(");
        try {
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
        } catch (Exception e) {
            // 单个字段的选择器不合法时不阻断整个解析（与 legado 一致，该字段取空值）
            if (EnvUtils.isDev()) {
                Console.error("<== 选择器解析失败: {} 原因: {}", selector, e.getMessage());
            }
            return new Elements();
        }
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

        return extractValue(elements.first(), elements, query, contentType);
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
        return extractValue(el, null, query, contentType);
    }

    /**
     * 统一取值：提取内容（text/html/attr）→ legado 后处理 → DSL 二次处理
     */
    private String extractValue(Element el, Elements elements, String query, ContentType contentType) {
        boolean urlType = contentType == ContentType.ATTR_HREF || contentType == ContentType.ATTR_SRC;
        boolean hasTransform = query != null && query.contains("##");

        String value;
        if (hasTransform && urlType) {
            // legado 先对原始属性值做 ##正则##替换，再把结果解析为绝对地址
            // （如 ##^##https://host 用于给相对地址补全域名）
            value = el.attr(contentType.getValue());
        } else if (elements == null || elements.size() == 1) {
            value = getContent(el, contentType);
        } else {
            value = getContent(elements, contentType);
        }

        value = postProcess(query, value, contentType);
        if (hasTransform && urlType) {
            value = resolveUrl(el.baseUri(), value);
        }
        return StrUtil.isNotEmpty(query) ? executeDsl(baseQuery(query), value) : value;
    }

    /**
     * legado 规则后处理：{@code ##正则##替换}（{@code replaceAll} 语义，替换为空表示删除匹配），
     * 以及 URL 取值上附带的 legado 请求参数（{@code ,{...}}）需从 URL 中剥离。
     */
    private String postProcess(String query, String value, ContentType contentType) {
        if (StrUtil.isEmpty(query) || value == null) return value;
        String result = value;

        int first = query.indexOf("##");
        if (first >= 0) {
            // 形如 选择器##正则##替换：替换部分到下一个 ## 为止（与 legado 的 split("##")[1]/[2] 一致）
            String rest = query.substring(first + 2);
            int second = rest.indexOf("##");
            String pattern = second < 0 ? rest : rest.substring(0, second);
            String replacement = "";
            if (second >= 0) {
                String after = rest.substring(second + 2);
                int third = after.indexOf("##");
                replacement = third < 0 ? after : after.substring(0, third);
            }
            try {
                result = result.replaceAll(pattern, replacement);
            } catch (Exception ignored) {
                // 非法正则时保持原值
            }
        }

        if (contentType == ContentType.ATTR_HREF || contentType == ContentType.ATTR_SRC) {
            // 例：a.1@href##$##,{'webView': true} -> 去掉请求参数只保留 URL
            result = result.replaceAll(",\\s*\\{.*}\\s*$", "").trim();
        }
        return result;
    }

    /** 把可能为相对路径的地址解析为绝对地址（失败时原样返回） */
    private String resolveUrl(String baseUri, String url) {
        if (StrUtil.isBlank(url) || StrUtil.isBlank(baseUri)) return url;
        try {
            return new java.net.URL(new java.net.URL(baseUri), url).toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** 剥离 legado 的 ##正则##替换 后处理，仅保留选择器本身 */
    private String baseQuery(String query) {
        if (query == null) return "";
        int idx = query.indexOf("##");
        return idx < 0 ? query.trim() : query.substring(0, idx).trim();
    }

    private ContentType getContentType(String query) {
        if (StrUtil.isBlank(query)) return null;
        query = baseQuery(query);
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