package com.pcdd.sonovel.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.model.Rule;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class ShareBookSourceLoader {

    private final Pattern SHARE_FILE = Pattern.compile("shareBookSource\\.\\d+\\.json");
    private final Pattern REQUEST_OPTIONS = Pattern.compile(",\\s*(\\{.*})\\s*$", Pattern.DOTALL);

    private static volatile List<ShareFile> cachedFiles;
    /** 加载时的书源文件指纹（文件名+最后修改时间），用于检测磁盘 JSON 变更后自动失效缓存 */
    private static volatile String cachedFingerprint;

    /** 读取后的规则缓存，避免每次搜索都重新解析磁盘上的全部额外书源文件。 */
    private List<ShareFile> getFiles() {
        List<ShareFile> files = cachedFiles;
        String fresh = filesFingerprint();
        if (files == null || !java.util.Objects.equals(fresh, cachedFingerprint)) {
            synchronized (ShareBookSourceLoader.class) {
                String recheck = filesFingerprint();
                if (cachedFiles == null || !java.util.Objects.equals(recheck, cachedFingerprint)) {
                    cachedFingerprint = recheck;
                    cachedFiles = loadFiles();
                }
                files = cachedFiles;
            }
        }
        return files;
    }

    /** 计算所有分享书源文件的指纹：文件名:最后修改时间 拼接。文件改动/增删都会使指纹变化。 */
    private String filesFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (File file : discoverShareFiles()) {
            sb.append(file.getName()).append(':')
                    .append(FileUtil.lastModifiedTime(file)).append(';');
        }
        return sb.toString();
    }

    private List<ShareFile> loadFiles() {
        File directory = resolveRulesDirectory();
        if (EnvUtils.isDev()) {
            Console.log("[书源] 开始加载分享书源，实际目录: {}（工作目录: {}）",
                    directory == null ? "null" : directory.getAbsolutePath(),
                    new File("").getAbsolutePath());
        }
        if (directory == null) {
            return List.of();
        }

        return discoverShareFiles().stream()
                .map(file -> new ShareFile(file.getName(), readSources(file)))
                .toList();
    }

    public List<Map<String, Object>> listSourcesGrouped() {
        List<Map<String, Object>> grouped = new ArrayList<>();
        for (ShareFile file : getFiles()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("file", file.name());
            List<Map<String, Object>> sourcesInFile = new ArrayList<>();
            for (int i = 0; i < file.sources().size(); i++) {
                Rule s = file.sources().get(i);
                sourcesInFile.add(buildSourceItem(file.name(), i, s));
            }
            group.put("sources", sourcesInFile);
            grouped.add(group);
        }
        return grouped;
    }

    /**
     * 构造前端书源条目。{@code index} 为文件内下标；内置(main)书源传入其规则 id。
     */
    public Map<String, Object> buildSourceItem(String fileName, int index, Rule s) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceId", fileName + "#" + index);
        item.put("file", fileName);
        item.put("indexInFile", index);
        item.put("name", s.getName());
        item.put("url", s.getUrl());
        item.put("disabled", s.isDisabled());
        if (s.isSearchOnly()) {
            item.put("searchOnly", true);
            item.put("searchOnlyReason", s.getSearchOnlyReason());
        }
        if (StrUtil.isNotBlank(s.getDisabledReason())) {
            item.put("disabledReason", s.getDisabledReason());
        }
        if (StrUtil.isNotBlank(s.getComment())) {
            item.put("comment", s.getComment());
        }
        return item;
    }

    public List<Rule> loadAll(int baseRuleCount) {
        return loadSelected(Set.of(), baseRuleCount, true);
    }

    public List<Rule> loadSelected(Set<String> selectedSourceIds, int baseRuleCount) {
        return loadSelected(selectedSourceIds, baseRuleCount, false);
    }

    private List<Rule> loadSelected(Set<String> selectedSourceIds, int baseRuleCount, boolean includeAll) {
        List<Rule> result = new ArrayList<>();
        int sourceIndex = 0;
        for (ShareFile file : getFiles()) {
            for (int i = 0; i < file.sources().size(); i++) {
                Rule source = file.sources().get(i);
                sourceIndex++;
                if (source.isDisabled()) continue;
                // 缓存里的是"书源定义"，多个并发搜索/下载会共用同一实例；
                // 这里必须拷贝一份再改写 id/sourceGroup，否则会产生跨请求的相互覆盖
                source = copyOf(source);
                source.setId(baseRuleCount + sourceIndex);
                source.setSourceGroup(file.name());
                String sourceId = file.name() + "#" + i;
                if (includeAll || selectedSourceIds.contains(sourceId)) {
                    result.add(source);
                }
            }
        }
        return result;
    }

    /** 深拷贝一条书源规则（含各子规则与请求头），保证调用方可安全改写而不影响缓存 */
    private Rule copyOf(Rule src) {
        Rule copy = new Rule();
        BeanUtil.copyProperties(src, copy);
        if (src.getSearch() != null) {
            Rule.Search search = new Rule.Search();
            BeanUtil.copyProperties(src.getSearch(), search);
            if (src.getSearch().getHeaders() != null) {
                search.setHeaders(new LinkedHashMap<>(src.getSearch().getHeaders()));
            }
            copy.setSearch(search);
        }
        if (src.getBook() != null) {
            Rule.Book book = new Rule.Book();
            BeanUtil.copyProperties(src.getBook(), book);
            copy.setBook(book);
        }
        if (src.getToc() != null) {
            Rule.Toc toc = new Rule.Toc();
            BeanUtil.copyProperties(src.getToc(), toc);
            copy.setToc(toc);
        }
        if (src.getChapter() != null) {
            Rule.Chapter chapter = new Rule.Chapter();
            BeanUtil.copyProperties(src.getChapter(), chapter);
            copy.setChapter(chapter);
        }
        return copy;
    }

    public Rule findById(int id, int baseRuleCount) {
        if (id <= baseRuleCount) return null;
        return loadAll(baseRuleCount).stream().filter(source -> source.getId() == id).findFirst().orElse(null);
    }

    public Rule findByUrl(String url, int baseRuleCount) {
        // 取最长的匹配前缀：https://a.com 与 https://a.com.cn 这类源共存时，避免被短前缀的书源抢先命中
        return loadAll(baseRuleCount).stream()
                .filter(source -> StrUtil.isNotBlank(source.getUrl()) && StrUtil.startWith(url, source.getUrl()))
                .max(Comparator.comparingInt((Rule source) -> source.getUrl().length()))
                .orElse(null);
    }

    private File resolveRulesDirectory() {
        List<String> candidates = new ArrayList<>();
        candidates.add("rules");
        candidates.add("bundle/rules");
        candidates.add("runtime/../rules");
        candidates.add("dist/SoNovel/rules");
        candidates.add("dist/rules");

        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    private List<Rule> readSources(File file) {
        JSONArray items = readJsonItems(file);

        List<Rule> result = new ArrayList<>();
        if (items == null) return result;
        for (Object value : items) {
            if (!(value instanceof JSONObject item)) continue;
            Rule source;
            try {
                source = toRule(item);
            } catch (Exception e) {
                // 单个书源解析异常不能拖垮整份书源列表，仍保留为禁用条目供展示
                source = buildRuleFallback(item, "书源解析异常：(" + e.getClass().getSimpleName() + ") " + e.getMessage());
            }
            if (source == null) {
                // toRule 未返回可用规则时也保留条目，保证该书源在设置页可见
                source = buildRuleFallback(item, "缺少可用的书源规则");
            }
            result.add(source);
        }
        return result;
    }

    private Rule buildRuleFallback(JSONObject item, String reason) {
        return markDisabled(buildSkeletonFallback(item), reason);
    }

    private JSONArray parseLenient(File file) {
        JSONArray result = new JSONArray();
        try {
            String text = FileUtil.readString(file, CharsetUtil.CHARSET_UTF_8);
            Pattern pattern = Pattern.compile(
                    "\"bookSourceName\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"[\\s\\S]{0,8000}?\"bookSourceUrl\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                JSONObject item = new JSONObject();
                item.set("bookSourceName", matcher.group(1));
                item.set("bookSourceUrl", matcher.group(2));
                item.set("bookSourceType", 0);
                item.set("enabled", true);
                result.add(item);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Rule toRule(JSONObject item) {
        Rule skeleton = buildSkeleton(item);
        boolean skeletonUsable = skeleton != null;

        if (item.getInt("bookSourceType", 0) != 0) {
            return markDisabled(skeletonUsable ? skeleton : buildSkeletonFallback(item), "不支持的书源类型（非0）");
        }
        if (!item.getBool("enabled", true)) {
            return markDisabled(skeletonUsable ? skeleton : buildSkeletonFallback(item), "原始配置已禁用");
        }

        JSONObject search = item.getJSONObject("ruleSearch");
        String searchUrl = item.getStr("searchUrl");
        if (search == null || StrUtil.isBlank(searchUrl)
                || !StrUtil.isAllNotBlank(search == null ? null : search.getStr("bookList"),
                search == null ? null : search.getStr("bookUrl"),
                search == null ? null : search.getStr("name"))) {
            return markDisabled(skeletonUsable ? skeleton : buildSkeletonFallback(item), "缺少有效搜索规则");
        }

        if (search.getStr("bookList").contains("<js>") || search.getStr("bookUrl").contains("<js>")) {
            return markDisabled(skeletonUsable ? skeleton : buildSkeletonFallback(item), "搜索规则包含不支持的 <js> 代码");
        }

        Rule rule = skeleton;
        if (rule == null) {
            return buildSkeletonFallback(item);
        }
        rule.setShareSource(true);

        Rule.Search searchRule = new Rule.Search();
        boolean hasAtJs = searchUrl.contains("@js:");
        boolean hasAngleBracketJs = searchUrl.trim().startsWith("<js>");

        if (hasAtJs || hasAngleBracketJs) {
            if (hasAngleBracketJs) {
                // 缺 </js> 收尾时 subBetween 返回 null，需显式判空，否则会拼出 "@js:null" 这种无效规则
                String inlineJs = StrUtil.subBetween(searchUrl, "<js>", "</js>");
                if (StrUtil.isBlank(inlineJs)) {
                    rule.setDisabled(true);
                    rule.setDisabledReason("搜索规则 <js> 代码不完整（缺少 </js>）");
                    return rule;
                }
                searchUrl = "@js:" + inlineJs;
            }
            searchRule.setUrl(searchUrl);
            searchRule.setMethod("get");
        } else {
            ParsedRequest request = parseRequest(searchUrl, rule.getUrl());
            if (request == null) {
                rule.setDisabled(true);
                rule.setDisabledReason("搜索链接无法解析（缺少 {{key}} 模板或格式不支持）");
                return rule;
            }
            searchRule.setUrl(request.url());
            searchRule.setMethod(request.method());
            searchRule.setData(request.data());
            searchRule.setCharset(request.charset());
        }

        searchRule.setHeaders(readHeaders(item.getStr("header")));
        searchRule.setResult(toSelector(search.getStr("bookList")));
        searchRule.setBookUrl(toSelector(search.getStr("bookUrl")));
        searchRule.setBookName(toSelector(search.getStr("name")));
        searchRule.setAuthor(toSelector(search.getStr("author")));
        searchRule.setCategory(toSelector(search.getStr("kind")));
        searchRule.setLatestChapter(toSelector(search.getStr("lastChapter")));
        searchRule.setLastUpdateTime(toSelector(search.getStr("lastUpdateTime")));
        searchRule.setStatus(toSelector(search.getStr("status")));
        searchRule.setWordCount(toSelector(search.getStr("wordCount")));
        searchRule.setBaseUri(rule.getUrl());
        rule.setSearch(searchRule);

        JSONObject book = item.getJSONObject("ruleBookInfo");
        if (book != null) {
            Rule.Book bookRule = new Rule.Book();
            bookRule.setBaseUri(rule.getUrl());
            bookRule.setUrl(rule.getUrl());
            bookRule.setBookName(toSelector(book.getStr("name")));
            bookRule.setAuthor(toSelector(book.getStr("author")));
            bookRule.setIntro(toSelector(book.getStr("intro")));
            bookRule.setCategory(toSelector(book.getStr("kind")));
            bookRule.setCoverUrl(toSelector(book.getStr("coverUrl")));
            bookRule.setLatestChapter(toSelector(book.getStr("lastChapter")));
            bookRule.setLatestChapterUrl(toSelector(book.getStr("tocUrl")));
            rule.setBook(bookRule);
        }

        List<String> noDownloadReasons = new ArrayList<>();

        JSONObject toc = item.getJSONObject("ruleToc");
        String chapterList = toc == null ? null : firstSelector(toc.get("chapterList"));
        String tocJs = extractJsCode(chapterList);
        if (toc != null && StrUtil.isNotBlank(tocJs)) {
            // 目录规则依赖 JS：交给 LegadoJsRuntime 执行（如 JSONP 目录接口）
            Rule.Toc tocRule = new Rule.Toc();
            tocRule.setBaseUri(rule.getUrl());
            tocRule.setJsList(tocJs);
            tocRule.setDesc(false);
            rule.setToc(tocRule);
        } else if (toc != null && StrUtil.isNotBlank(chapterList) && !chapterList.contains("<js>")) {
            Rule.Toc tocRule = new Rule.Toc();
            tocRule.setBaseUri(rule.getUrl());
            tocRule.setItem(toSelector(chapterList));
            tocRule.setNextPageUrl(toSelector(resolvePagingSelector(toc)));
            tocRule.setDesc(false);
            rule.setToc(tocRule);
        } else {
            noDownloadReasons.add("缺少目录规则");
        }

        JSONObject content = item.getJSONObject("ruleContent");
        String contentRule = content == null ? null : firstSelector(content.get("content"));
        if (content != null && StrUtil.isNotBlank(contentRule) && !contentRule.contains("<js>")) {
            Rule.Chapter chapterRule = new Rule.Chapter();
            chapterRule.setBaseUri(rule.getUrl());
            chapterRule.setContent(toSelector(contentRule));
            String nextContent = resolvePaging(content, "nextContentUrl");
            if (StrUtil.isNotBlank(nextContent)) {
                chapterRule.setNextPage(toSelector(nextContent));
            }
            rule.setChapter(chapterRule);
        } else if (content != null && StrUtil.isNotBlank(contentRule) && contentRule.contains("<js>")) {
            noDownloadReasons.add("正文规则含 JS");
        } else {
            noDownloadReasons.add("缺少正文规则");
        }

        // 搜索可用但目录/正文无法转换时，标记为仅可搜索，避免下载时NPE或产出空文件
        if (!noDownloadReasons.isEmpty()) {
            rule.setSearchOnly(true);
            rule.setSearchOnlyReason("该分享书源" + String.join("、", noDownloadReasons) + "，暂不支持下载");
        }
        return rule;
    }

    private Rule buildSkeleton(JSONObject item) {
        String baseUrl = getBaseUrl(item.getStr("bookSourceUrl"));
        String name = item.getStr("bookSourceName");
        if (StrUtil.isBlank(name) || StrUtil.isBlank(baseUrl)) return null;

        Rule rule = new Rule();
        rule.setName(name);
        rule.setComment(item.getStr("bookSourceComment"));
        rule.setUrl(baseUrl);
        return rule;
    }

    private Rule buildSkeletonFallback(JSONObject item) {
        Rule rule = buildSkeleton(item);
        if (rule == null) {
            rule = new Rule();
            rule.setName(StrUtil.blankToDefault(item.getStr("bookSourceName"), "(未知名称)"));
            rule.setUrl(getBaseUrl(item.getStr("bookSourceUrl")));
            rule.setComment(item.getStr("bookSourceComment"));
        }
        rule.setDisabled(true);
        rule.setDisabledReason("缺少书源名称或主页URL");
        return rule;
    }

    private Rule markDisabled(Rule rule, String reason) {
        if (rule == null) return null;
        rule.setDisabled(true);
        rule.setDisabledReason(reason);
        return rule;
    }

    private ParsedRequest parseRequest(String searchUrl, String baseUrl) {
        Matcher matcher = REQUEST_OPTIONS.matcher(searchUrl);
        JSONObject options = null;
        String url = searchUrl;
        if (matcher.find()) {
            url = searchUrl.substring(0, matcher.start());
            try {
                options = JSONUtil.parseObj(normalizeOptions(matcher.group(1)));
            } catch (Exception ignored) {
                return null;
            }
        }
        url = simplifyShareExpressions(url);

        String method = options == null ? "get" : options.getStr("method", "get");
        String charset = options == null ? null : options.getStr("charset");
        String data = null;
        if (options != null && options.get("body") != null) {
            Object body = options.get("body");
            Map<String, Object> form = new LinkedHashMap<>();
            if (body instanceof JSONObject bodyObject) {
                bodyObject.forEach((key, value) -> form.put(key, replacePlaceholders(String.valueOf(value))));
            } else {
                String bodyText = replacePlaceholders(String.valueOf(body));
                for (String pair : bodyText.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2) form.put(parts[0], parts[1]);
                }
            }
            if (form.isEmpty()) return null;
            data = JSONUtil.toJsonStr(form);
        }

        // legado 的 POST 书源常把 {{key}} 放在请求体里，此时 URL 本身不含模板，需一并接受
        boolean urlHasKey = url.contains("{{key}}");
        boolean bodyHasKey = data != null && data.contains("%s");
        if (!urlHasKey && !bodyHasKey) return null;

        url = replacePlaceholders(url).trim();
        if (url.isEmpty()) {
            url = baseUrl;
        } else if (url.startsWith("/")) {
            // 避免 baseUrl 以 / 结尾时拼出双斜杠
            url = baseUrl.endsWith("/") ? baseUrl + url.substring(1) : baseUrl + url;
        }
        if (StrUtil.isBlank(url) || ("post".equalsIgnoreCase(method) && data == null)) return null;
        return new ParsedRequest(url, method, data, charset);
    }

    /** 将 legado 模板变量替换为占位符：{{key}} -> %s，{{page}} -> 1 */
    private String replacePlaceholders(String text) {
        return text.replace("{{key}}", "%s").replace("{{page}}", "1");
    }

    private String simplifyShareExpressions(String url) {
        StringBuilder result = new StringBuilder();
        Matcher m = Pattern.compile("\\{\\{(.*?)\\}\\}").matcher(url);
        int last = 0;
        while (m.find()) {
            result.append(url, last, m.start());
            String expr = m.group(1).trim();
            if (expr.contains("key")) {
                result.append("{{key}}");
            } else if (expr.contains("page")) {
                result.append("{{page}}");
            }
            last = m.end();
        }
        result.append(url.substring(last));
        return result.toString();
    }

    private String normalizeOptions(String options) {
        String normalized = options.replaceAll("'((?:\\\\.|[^'\\\\])*)'", "\"$1\"")
                .replaceAll("([,{]\\s*)([A-Za-z_$][\\w$-]*)(\\s*:)", "$1\"$2\"$3")
                // legado 模板变量（如 "keyword":{{key}}）不是合法 JSON 值，补上引号后再解析
                .replaceAll("(:\\s*)(\\{\\{[^{}]*\\}\\})", "$1\"$2\"");
        return normalized.replaceAll(",\\s*([}\\]])", "$1");
    }

    private Map<String, String> readHeaders(String header) {
        if (StrUtil.isBlank(header) || header.contains("@js:") || header.contains("<js>")) return Map.of();
        try {
            JSONObject object = JSONUtil.parseObj(header);
            Map<String, String> headers = new LinkedHashMap<>();
            object.forEach((key, value) -> headers.put(key, String.valueOf(value)));
            return headers;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /** 从 ruleContent/ruleToc 等块中解析单条分页/翻页规则（兼容字符串/数组/js 对象，跳过依赖 JS 的动态规则） */
    private String resolvePaging(JSONObject obj, String key) {
        if (obj == null) return null;
        String s = firstSelector(obj.get(key));
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("@js:") || s.contains("<js>")) return null;
        return s;
    }

    /** 兼容 legado ruleToc.nextTocUrl / ruleToc.nextPage 可能为字符串或数组 */
    private String resolvePagingSelector(JSONObject toc) {
        if (toc == null) return null;
        Object nextToc = toc.get("nextTocUrl");
        if (nextToc == null) nextToc = toc.get("nextPage");
        return firstSelector(nextToc);
    }

    /** 取出选择器值：数组取首项，js 对象取 rule 字段，其余按字符串处理 */
    private String firstSelector(Object v) {
        if (v == null) return null;
        if (v instanceof JSONArray array && !array.isEmpty()) return array.getStr(0);
        if (v instanceof JSONObject o) {
            Object rule = o.get("rule");
            return rule == null ? null : String.valueOf(rule);
        }
        return String.valueOf(v);
    }

    /**
     * 提取 legado JS 规则的源码，兼容 {@code <js>code</js>}、{@code @js:code}、{@code <js>@js:code</js>} 等写法。
     * 非 JS 规则返回 null。
     */
    private String extractJsCode(String rule) {
        if (StrUtil.isBlank(rule)) return null;
        String code = rule;
        int start = code.indexOf("<js>");
        if (start >= 0) {
            int end = code.indexOf("</js>", start);
            code = end > start ? code.substring(start + 4, end) : code.substring(start + 4);
        }
        code = code.trim();
        if (code.regionMatches(true, 0, "@js:", 0, 4)) code = code.substring(4).trim();
        return code.isEmpty() ? null : code;
    }

    private String toSelector(String sourceRule) {
        if (StrUtil.isBlank(sourceRule)) return "";

        String[] alternatives = sourceRule.split("\\|\\|");
        List<String> converted = new ArrayList<>();
        for (String alt : alternatives) {
            String sel = convertSingleSelector(alt.trim());
            if (StrUtil.isNotBlank(sel)) converted.add(sel);
        }
        if (converted.isEmpty()) return "";
        return String.join(", ", converted);
    }

    private String convertSingleSelector(String sourceRule) {
        String rule = sourceRule;
        if (rule.contains("<js>") || rule.contains("@js:") || rule.contains("@JSon:")) return "";
        // legado 的 ##正则##替换 属于取值后的后处理，交由 HtmlExtractor 应用
        String postProcess = "";
        int hashIndex = rule.indexOf("##");
        if (hashIndex >= 0) {
            postProcess = rule.substring(hashIndex).trim();
            rule = rule.substring(0, hashIndex).trim();
        }
        if (rule.regionMatches(true, 0, "@css:", 0, 5)) rule = rule.substring(5).trim();

        String attribute = "";
        Matcher attrMatcher = Pattern.compile("@(textNodes|text|html|href|src|content|value)$").matcher(rule);
        if (attrMatcher.find()) {
            attribute = switch (attrMatcher.group(1)) {
                case "href", "src", "content", "value" -> "@" + attrMatcher.group(1);
                default -> "";
            };
            rule = rule.substring(0, attrMatcher.start());
        }

        // legado 简写 text.标签@href：按元素文本精确定位
        if (rule.startsWith("text.")) {
            if (rule.equalsIgnoreCase("text.name") || rule.equalsIgnoreCase("text.author")) return "";
            String label = rule.substring(5).trim();
            if (label.isEmpty()) return "";
            String escaped = label.replace("'", "''");
            return "//a[contains(normalize-space(.),'" + escaped + "')]" + attribute + postProcess;
        }

        // 已是 XPath：原样保留，不再按 @ 切分（避免破坏 [@attr] 语法）
        if (rule.startsWith("/") || rule.startsWith("(")) {
            return rule.trim() + attribute + postProcess;
        }

        String[] parts = rule.split("@");
        List<String> selectors = new ArrayList<>();
        for (String part : parts) {
            String selector = part.trim().replaceAll("^tag\\.", "");
            selector = selector.replaceAll("^class\\.", ".").replaceAll("^id\\.", "#");
            Matcher excludedIndex = Pattern.compile("!(\\d+)$").matcher(selector);
            if (excludedIndex.find()) {
                int excluded = Integer.parseInt(excludedIndex.group(1));
                selector = selector.substring(0, excludedIndex.start())
                        + ":not(:nth-child(" + (excluded + 1) + "))";
            }
            Matcher index = Pattern.compile("^([a-zA-Z][\\w-]*)\\.(\\d+)$").matcher(selector);
            if (index.matches()) selector = index.group(1) + ":nth-of-type(" + (Integer.parseInt(index.group(2)) + 1) + ")";
            Matcher negIndex = Pattern.compile("^([a-zA-Z][\\w-]*)\\.(-\\d+)$").matcher(selector);
            if (negIndex.matches()) selector = negIndex.group(1) + ":nth-last-of-type(" + Math.abs(Integer.parseInt(negIndex.group(2))) + ")";
            if (!selector.isEmpty()) selectors.add(selector);
        }
        return String.join(" ", selectors) + attribute + postProcess;
    }

    private String getBaseUrl(String sourceUrl) {
        if (StrUtil.isBlank(sourceUrl)) return "";
        for (String line : sourceUrl.split("[\\r\\n]")) {
            String url = line.trim();
            int idx = url.indexOf("##");
            if (idx > 0) url = url.substring(0, idx);
            idx = url.indexOf(",");
            if (idx > 0) url = url.substring(0, idx);
            url = url.trim().split("#", 2)[0].trim();
            if (url.startsWith("http://") || url.startsWith("https://")) return url;
        }
        return "";
    }

    private record ShareFile(String name, List<Rule> sources) {}

    private record ParsedRequest(String url, String method, String data, String charset) {}

    /** 每条额外书源的读取进度事件（start=true 表示开始读取，start=false 表示最终结果） */
    public record ProgressEvent(int index, int total, String fileName, String sourceName, boolean start, boolean ok, String message) {}

    private static final int SOURCE_READ_TIMEOUT_SECONDS = 10;

    /** 重读闸门：全量重解析是重量级操作，同一时刻只允许一个任务，避免多人同时点把 CPU 打满 */
    private static final AtomicBoolean RELOADING = new AtomicBoolean(false);

    /** 尝试占用重读闸门，返回 false 表示已有任务在进行中 */
    public static boolean tryBeginReload() {
        return RELOADING.compareAndSet(false, true);
    }

    /** 释放重读闸门，必须与 {@link #tryBeginReload()} 成对调用 */
    public static void endReload() {
        RELOADING.set(false);
    }

    /**
     * 增量读取额外书源：逐条读取、实时上报进度、出错跳过、单条超时保护。
     * 返回与 {@link #listSourcesGrouped()} 一致的分组结构，供前端直接替换展示。
     * <p>
     * 调用方需先通过 {@link #tryBeginReload()} 占住闸门，结束后 {@link #endReload()} 释放。
     */
    public List<Map<String, Object>> reloadGrouped(Consumer<ProgressEvent> onProgress) {
        // 重读到磁盘最新文件后，失效旧缓存，避免后续搜索继续用旧的解析结果
        cachedFiles = null;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<File> files = discoverShareFiles();
            // 1) 先统计每个文件的条目数，算出总数用于进度条
            List<JSONArray> fileItems = new ArrayList<>();
            int total = 0;
            for (File file : files) {
                JSONArray items = readJsonItems(file);
                fileItems.add(items);
                total += items.size();
            }
            if (total == 0) {
                Console.log("额外书源为空，未发现可读取条目");
                return List.of();
            }

            // 2) 逐文件、逐条读取
            AtomicInteger running = new AtomicInteger(0);
            List<Map<String, Object>> grouped = new ArrayList<>();
            for (int fi = 0; fi < files.size(); fi++) {
                File file = files.get(fi);
                JSONArray items = fileItems.get(fi);
                if (items.isEmpty()) continue;

                List<Map<String, Object>> sources = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    int index = running.incrementAndGet();
                    Object value = items.get(i);
                    String rawName = (value instanceof JSONObject jo) ? StrUtil.blankToDefault(jo.getStr("bookSourceName"), "(未知名称)") : "(未知名称)";
                    String fileName = file.getName();

                    onProgress.accept(new ProgressEvent(index, total, fileName, rawName, true, true, "开始读取"));
                    if (EnvUtils.isDev()) {
                        Console.log("开始读取额外书源 [{}/{}] {} <- {}", index, total, fileName, rawName);
                    }

                    if (!(value instanceof JSONObject item)) {
                        onProgress.accept(new ProgressEvent(index, total, fileName, rawName, false, false, "条目格式无效"));
                        Console.error("跳过额外书源 [{}/{}] {}：条目格式无效", index, total, fileName);
                        continue;
                    }

                    Rule rule;
                    long start = System.currentTimeMillis();
                    try {
                        Rule r = withTimeout(executor, () -> toRule(item));
                        rule = (r != null) ? r : buildSkeletonFallback(item);
                    } catch (Exception e) {
                        String reason = e.getClass().getSimpleName() + "：" + StrUtil.emptyToDefault(e.getMessage(), "未知原因");
                        String msg = "解析超时或异常，已跳过（" + reason + "）";
                        onProgress.accept(new ProgressEvent(index, total, fileName, rawName, false, false, msg));
                        Console.error("跳过额外书源 [{}/{}] {} ({} ms)：{}", index, total, fileName, System.currentTimeMillis() - start, msg);
                        continue;
                    }

                    Map<String, Object> itemMap = buildSourceItem(fileName, i, rule);
                    sources.add(itemMap);
                    long cost = System.currentTimeMillis() - start;
                    String state = rule.isDisabled() ? "（解析完成，但不可用）" : "";
                    if (EnvUtils.isDev()) {
                        Console.log("完成读取额外书源 [{}/{}] {} -> {}{} ({} ms)", index, total, fileName, rule.getName(), state, cost);
                    }
                    onProgress.accept(new ProgressEvent(index, total, fileName, rule.getName(), false, true, "已读取" + state));
                }
                if (!sources.isEmpty()) {
                    Map<String, Object> group = new LinkedHashMap<>();
                    group.put("file", file.getName());
                    group.put("sources", sources);
                    grouped.add(group);
                }
            }
            return grouped;
        } finally {
            executor.shutdown();
        }
    }

    private List<File> discoverShareFiles() {
        File directory = resolveRulesDirectory();
        if (directory == null) return List.of();
        return FileUtil.loopFiles(directory, file -> SHARE_FILE.matcher(file.getName()).matches())
                .stream()
                .sorted(Comparator.comparing(File::getName))
                .toList();
    }

    /**
     * 读取单个书源文件的 JSONArray；整文件无法解析时退化为按 name/url 提取，保证不丢条目。
     */
    private JSONArray readJsonItems(File file) {
        try {
            return JSONUtil.readJSONArray(file, CharsetUtil.CHARSET_UTF_8);
        } catch (Exception e) {
            return parseLenient(file);
        }
    }

    private Rule withTimeout(ExecutorService executor, Callable<Rule> task) throws Exception {
        Future<Rule> future = executor.submit(task);
        try {
            return future.get(SOURCE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

}