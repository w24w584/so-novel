package com.pcdd.sonovel.utils;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.converters.JavetProxyConverter;
import com.caoccao.javet.interop.engine.IJavetEngine;
import com.caoccao.javet.interop.engine.JavetEnginePool;
import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.core.HtmlExtractor;
import lombok.SneakyThrows;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行 legado 分享书源中依赖 JS 的规则（目录 {@code <js>}/{@code @js:}）。
 * <p>
 * 提供 legado JS 环境的常用子集：{@code result}、{@code baseUrl}、{@code source.getKey()}、
 * {@code java.ajax/get/setContent/getElements/getElement/getString/getStringList/put/log} 等，
 * 元素以 HTML 字符串（outerHtml）形式往返，兼容 {@code Array.from(list).map(String)}、{@code list.splice()} 等写法。
 * 未实现的 API 会抛错并由调用方降级处理（不会影响其他书源）。
 *
 * @author pcdd
 */
public class LegadoJsRuntime {

    private static final JavetEnginePool<V8Runtime> POOL = new JavetEnginePool<>();
    /** legado 请求参数后缀：url,{...} */
    private static final Pattern OPTIONS_SUFFIX = Pattern.compile(",\\s*(\\{.*})\\s*$", Pattern.DOTALL);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** JS 规则返回的章节项：html 为元素片段，或明确给出 text/href */
    public record TocItem(String html, String text, String href) {}

    private LegadoJsRuntime() {
    }

    /**
     * 执行目录 JS 规则，返回章节项列表。
     *
     * @param jsCode    legado 目录 JS 源码
     * @param pageHtml  目录页 HTML（对应 JS 中的 result）
     * @param pageUrl   目录页 URL（对应 baseUrl、相对链接基准）
     * @param sourceKey 书源主页（对应 source.getKey()）
     */
    public static List<TocItem> evalChapterList(String jsCode, String pageHtml, String pageUrl, String sourceKey, Integer timeout) {
        if (StrUtil.isBlank(jsCode)) return List.of();

        try (IJavetEngine<V8Runtime> engine = POOL.getEngine()) {
            V8Runtime v8 = engine.getV8Runtime();
            // 需要代理转换器才能把 Java 对象（java.ajax 等）暴露给 JS
            v8.setConverter(new JavetProxyConverter());
            // 书源 JS 中常使用 eval 拼接动态代码（默认被禁用）
            v8.allowEval(true);

            JsBridge bridge = new JsBridge(
                    pageHtml,
                    pageUrl,
                    sourceKey,
                    timeout == null ? DEFAULT_TIMEOUT_SECONDS : Math.max(5, timeout));
            v8.getGlobalObject().set("__bridge", bridge);
            v8.getGlobalObject().set("__result", StrUtil.nullToEmpty(pageHtml));
            v8.getGlobalObject().set("__baseUrl", StrUtil.nullToEmpty(pageUrl));
            v8.getGlobalObject().set("__sourceKey", StrUtil.nullToEmpty(sourceKey));

            String script = buildScript(jsCode);
            String json = v8.getExecutor(script).executeString();
            return toItems(json);
        } catch (Exception e) {
            Console.error("<== 执行分享书源目录 JS 规则失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** legado JS 环境前置：注入 result/baseUrl/source/book/java 等常用对象 */
    private static final String PRELUDE = """
            var java = {
                ajax: function (u) { return __bridge.ajax(String(u)); },
                post: function (u, b) { return __bridge.post(String(u), b == null ? '' : String(b)); },
                setContent: function (h) { __bridge.setContent(h == null ? '' : String(h)); },
                getElements: function (s) { return JSON.parse(__bridge.getElementsJson(String(s))); },
                getElement: function (s) { return __bridge.getElement(String(s)); },
                getString: function (s) { return __bridge.getString(String(s)); },
                getStringList: function (s) { return JSON.parse(__bridge.getStringListJson(String(s))); },
                getRegex: function (s) { return JSON.parse(__bridge.getRegexJson(String(s))); },
                put: function (k, v) { __bridge.put(String(k), v == null ? '' : String(v)); },
                get: function (k) { return __bridge.getVar(String(k)); },
                log: function (m) { __bridge.log(m == null ? '' : String(m)); },
                toast: function () {},
                longToast: function () {},
                base64Encode: function (s) { return __bridge.base64Encode(String(s)); },
                base64Decode: function (s) { return __bridge.base64Decode(String(s)); },
                md5Encode16: function (s) { return __bridge.md5Encode16(String(s)); },
                timeFormatUTC: function (t) { return String(t); }
            };
            var result = __result;
            var baseUrl = __baseUrl;
            var key = __sourceKey;
            var page = 1;
            var cookie = {get: function () { return ''; }, set: function () {}, remove: function () {}};
            var source = {
                getKey: function () { return __sourceKey; },
                getName: function () { return ''; },
                get: function () { return ''; },
                put: function () {},
                getVariable: function () { return ''; }
            };
            var book = {name: '', author: '', bookUrl: '', origin: __baseUrl, getVariable: function () { return ''; }};
            var chapter = {};
            """;

    /**
     * 包装为：用 eval 求值 JS（取最后一个表达式的值，与 legado 一致），
     * 再把返回值归一化为 [{h: html}] 或 [{t: text, u: href}] 的 JSON 数组。
     * 少数书源使用顶层 {@code return} 写法（eval 视为语法错误），此时回退到 new Function。
     */
    private static String buildScript(String jsCode) {
        // 注意：不能用 JSONUtil.toJsonStr（会把字符串当作 JSON 原样输出，丢失引号）
        String encoded = JSONUtil.quote(jsCode);
        return PRELUDE + "\n"
                + "var __v = null;\n"
                + "var __code = " + encoded + ";\n"
                + "try { __v = eval(__code); } catch (__e) {\n"
                + "  if (__e instanceof SyntaxError) { __v = (new Function(__code))(); } else { throw __e; }\n"
                + "}\n"
                + "var __a;\n"
                + "if (__v == null) { __a = []; }\n"
                + "else if (Object.prototype.toString.call(__v) === '[object Array]') { __a = __v; }\n"
                + "else if (typeof __v === 'object' && typeof __v.length === 'number') { __a = Array.prototype.slice.call(__v); }\n"
                + "else { __a = [__v]; }\n"
                + "var __o = [];\n"
                + "for (var __i = 0; __i < __a.length; __i++) {\n"
                + "  var __x = __a[__i];\n"
                + "  if (__x == null) continue;\n"
                + "  if (typeof __x === 'string') { if (__x.length > 0) __o.push({h: __x}); continue; }\n"
                + "  if (typeof __x === 'object') {\n"
                + "    var __t = (__x.text != null) ? __x.text : ((__x.name != null) ? __x.name : null);\n"
                + "    var __u = (__x.href != null) ? __x.href : ((__x.url != null) ? __x.url : null);\n"
                + "    if (__t != null || __u != null) { __o.push({t: __t == null ? '' : String(__t), u: __u == null ? '' : String(__u)}); continue; }\n"
                + "    var __s = String(__x);\n"
                + "    if (__s.length > 0 && __s !== '[object Object]') { __o.push({h: __s}); }\n"
                + "    continue;\n"
                + "  }\n"
                + "  __o.push({t: String(__x)});\n"
                + "}\n"
                + "var __json = JSON.stringify(__o);\n"
                + "__json\n";
    }

    private static List<TocItem> toItems(String json) {
        List<TocItem> items = new ArrayList<>();
        if (StrUtil.isBlank(json)) return items;
        try {
            JSONArray array = JSONUtil.parseArray(json);
            for (Object value : array) {
                if (!(value instanceof JSONObject obj)) continue;
                String html = obj.getStr("h");
                if (StrUtil.isNotBlank(html)) {
                    items.add(new TocItem(html, null, null));
                    continue;
                }
                String text = StrUtil.nullToEmpty(obj.getStr("t"));
                String href = StrUtil.nullToEmpty(obj.getStr("u"));
                if (StrUtil.isNotBlank(text) || StrUtil.isNotBlank(href)) {
                    items.add(new TocItem(null, text, href));
                }
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    /**
     * 暴露给 JS 的 {@code java} 实现（javet 以代理对象方式调用其 {@link V8Function} 方法）。
     */
    public static class JsBridge {

        private final String baseUri;
        private final int timeout;
        private final Map<String, String> vars = new LinkedHashMap<>();

        private String content;
        private Document document;

        JsBridge(String content, String baseUri, String sourceKey, int timeout) {
            this.content = StrUtil.nullToEmpty(content);
            this.baseUri = StrUtil.nullToEmpty(baseUri);
            this.timeout = timeout;
        }

        @V8Function
        public String ajax(String urlWithOptions) {
            return request(urlWithOptions, null);
        }

        @V8Function
        public String post(String urlWithOptions, String body) {
            return request(urlWithOptions, body);
        }

        @V8Function
        public void setContent(String html) {
            this.content = StrUtil.nullToEmpty(html);
            this.document = null;
        }

        @V8Function
        public String getElementsJson(String selector) {
            JSONArray array = new JSONArray();
            for (Element el : select(selector)) {
                array.add(el.outerHtml());
            }
            return array.toString();
        }

        @V8Function
        public String getElement(String selector) {
            Elements elements = select(selector);
            return elements.isEmpty() ? "" : elements.first().outerHtml();
        }

        @V8Function
        public String getString(String selector) {
            if (StrUtil.isBlank(selector)) return "";
            try {
                return StrUtil.nullToEmpty(HtmlExtractor.extract(doc(), selector.trim()));
            } catch (Exception e) {
                return "";
            }
        }

        @V8Function
        public String getStringListJson(String selector) {
            JSONArray array = new JSONArray();
            for (Element el : select(selector)) {
                array.add(el.text().trim());
            }
            return array.toString();
        }

        @V8Function
        public String getRegexJson(String regex) {
            JSONArray array = new JSONArray();
            if (StrUtil.isBlank(regex)) return array.toString();
            try {
                Matcher matcher = Pattern.compile(regex).matcher(content);
                while (matcher.find()) {
                    array.add(matcher.groupCount() > 0 ? StrUtil.nullToEmpty(matcher.group(1)) : matcher.group());
                }
            } catch (Exception ignored) {
            }
            return array.toString();
        }

        @V8Function
        public void put(String key, String value) {
            vars.put(key, value);
        }

        @V8Function
        public String getVar(String key) {
            return StrUtil.nullToEmpty(vars.get(key));
        }

        @V8Function
        public void log(String message) {
            if (EnvUtils.isDev()) {
                Console.log("<== 书源 JS: {}", message);
            }
        }

        @V8Function
        public String base64Encode(String text) {
            return Base64.encode(text);
        }

        @V8Function
        public String base64Decode(String text) {
            try {
                return Base64.decodeStr(text);
            } catch (Exception e) {
                return "";
            }
        }

        @SneakyThrows
        @V8Function
        public String md5Encode16(String text) {
            byte[] digest = MessageDigest.getInstance("MD5").digest(StrUtil.nullToEmpty(text).getBytes(StandardCharsets.UTF_8));
            // 与 legado 一致：取 32 位结果的第 8~24 位
            return cn.hutool.core.util.HexUtil.encodeHexStr(digest).substring(8, 24);
        }

        private Elements select(String selector) {
            if (StrUtil.isBlank(selector)) return new Elements();
            try {
                return HtmlExtractor.select(doc(), selector.trim());
            } catch (Exception e) {
                return new Elements();
            }
        }

        private Document doc() {
            if (document == null) {
                document = Jsoup.parse(content, baseUri);
            }
            return document;
        }

        /**
         * 模拟 legado 的请求：支持 {@code url,{options}} 形式，options 可含 headers/method/body/charset。
         */
        @SneakyThrows
        private String request(String urlWithOptions, String body) {
            String url = StrUtil.nullToEmpty(urlWithOptions);
            JSONObject options = null;
            Matcher matcher = OPTIONS_SUFFIX.matcher(url);
            if (matcher.find()) {
                url = url.substring(0, matcher.start());
                try {
                    options = JSONUtil.parseObj(matcher.group(1));
                } catch (Exception ignored) {
                }
            }
            url = url.trim();
            if (StrUtil.isBlank(url)) return "";

            boolean hasUa = false;
            boolean hasReferer = false;
            Request.Builder builder = new Request.Builder().url(url);
            if (options != null && options.getJSONObject("headers") != null) {
                for (Map.Entry<String, Object> entry : options.getJSONObject("headers").entrySet()) {
                    builder.addHeader(entry.getKey(), String.valueOf(entry.getValue()));
                    String name = entry.getKey().toLowerCase();
                    hasUa |= name.equals("user-agent");
                    hasReferer |= name.equals("referer");
                }
            }
            if (!hasUa) builder.addHeader(Header.USER_AGENT.toString(), RandomUA.generate());
            if (!hasReferer) builder.addHeader(Header.REFERER.toString(), url.substring(0, url.indexOf('/', url.indexOf("://") + 3) + 1));

            String requestBody = body;
            String method = options == null ? null : options.getStr("method");
            if (requestBody == null && options != null && options.get("body") != null) {
                requestBody = String.valueOf(options.get("body"));
            }
            if (requestBody != null) {
                builder.post(RequestBody.create(requestBody, MediaType.parse("application/x-www-form-urlencoded")));
            } else if ("post".equalsIgnoreCase(method)) {
                builder.post(RequestBody.create("", null));
            } else if ("head".equalsIgnoreCase(method)) {
                builder.head();
            }

            OkHttpClient client = HttpClientContext.get();
            if (client == null) client = new OkHttpClient();
            Call call = client.newCall(builder.build());
            call.timeout().timeout(timeout, TimeUnit.SECONDS);

            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (responseBody == null) return "";
                byte[] bytes = responseBody.bytes();
                if (!response.isSuccessful() && EnvUtils.isDev()) {
                    Console.error("<== 书源 JS 请求 {} 返回 HTTP {}，响应: {}", url, response.code(),
                            StrUtil.subPre(new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " "), 120));
                }
                String charset = options == null ? null : options.getStr("charset");
                return new String(bytes, detectCharset(bytes, responseBody.contentType(), charset));
            }
        }

        /** 优先响应头 charset，其次页面 meta 声明，最后书源 options.charset / UTF-8 */
        private Charset detectCharset(byte[] bytes, MediaType mediaType, String declared) {
            if (mediaType != null && mediaType.charset() != null) return mediaType.charset();
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-]+)").matcher(head);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (Exception ignored) {
                }
            }
            if (StrUtil.isNotBlank(declared) && Charset.isSupported(declared)) return Charset.forName(declared);
            return StandardCharsets.UTF_8;
        }
    }

}
