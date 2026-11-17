package com.zcx.chenstack.knowledge;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown / 纯文本的解析与规范化。
 *
 * <p>职责边界：把上传的原始字节变成“可入库的纯文本正文”，同时保留原始 Markdown 供追溯。
 * 不访问数据库、不访问网络，可在单测中直接调用。</p>
 *
 * <p>安全边界：严格 UTF-8 解码；剥离 BOM；拒绝 NUL 与不可打印控制字符；用 jsoup 白名单
 * 清空残余 HTML，确保入库正文不含脚本或事件属性。</p>
 */
public final class KnowledgeTextParser {
    /** 解析器版本，写入 knowledge_document_version.parser_version。 */
    public static final String MARKDOWN_PARSER = "md-plain-v1";
    public static final String TEXT_PARSER = "txt-plain-v1";
    /** 单文件上限 2 MiB，与数据库 CHECK 及上传配置一致。 */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TITLE_CHARS = 80;
    static final int MAX_TITLE_DB = 160;

    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\s*\\n.*?\\n---\\s*(?:\\n|\\z)", Pattern.DOTALL);
    private static final Pattern FENCED_CODE = Pattern.compile("^\\s*(```|~~~).*$", Pattern.MULTILINE);
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$", Pattern.MULTILINE);
    private static final Pattern BLOCKQUOTE = Pattern.compile("^\\s{0,3}>\\s?", Pattern.MULTILINE);
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s{0,6}(?:[-*+]|\\d{1,3}[.)])\\s+", Pattern.MULTILINE);
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?[\\s:|-]{3,}\\|?\\s*$", Pattern.MULTILINE);
    private static final Pattern INLINE_MARKS = Pattern.compile("(`{1,3}|\\*{1,3}|_{1,3}|~~)");
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");
    private static final Pattern H1 = Pattern.compile("^\\s{0,3}#\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private KnowledgeTextParser() {
    }

    /** 解析结果。rawText 为解码后的原文，normalizedText 为入库正文。 */
    public record Parsed(String title, String rawText, String normalizedText, String rawFormat,
                         String parserVersion, boolean bomPresent, String contentType) {
    }

    /** 按扩展名判断是否受支持，并返回规范后的 content type。 */
    public static String contentTypeOf(String filename) {
        String ext = extensionOf(filename);
        if (MARKDOWN_EXTENSIONS.contains(ext)) {
            return "text/markdown";
        }
        if (TEXT_EXTENSIONS.contains(ext)) {
            return "text/plain";
        }
        throw KnowledgeDocException.bad("IMPORT_EXTENSION_INVALID", "仅支持 .md、.markdown、.txt 文件");
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            throw KnowledgeDocException.bad("IMPORT_FILENAME_INVALID", "缺少文件名");
        }
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw KnowledgeDocException.bad("IMPORT_EXTENSION_INVALID", "缺少文件扩展名");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 去掉目录与扩展名，作为标题兜底。 */
    public static String baseNameOf(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 解析上传字节。
     *
     * @param filename       原始文件名（只用于扩展名与标题兜底，不参与对象键）
     * @param bytes          原始字节
     * @param fallbackTitle  前端可选的标题；为空时依次用首个 H1、文件名
     */
    public static Parsed parse(String filename, byte[] bytes, String fallbackTitle) {
        String contentType = contentTypeOf(filename);
        if (bytes == null || bytes.length == 0) {
            throw KnowledgeDocException.bad("IMPORT_EMPTY", "文件内容为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw KnowledgeDocException.bad("IMPORT_TOO_LARGE", "文件超过 2 MiB 限制");
        }
        boolean bom = startsWithBom(bytes);
        byte[] payload = bom ? java.util.Arrays.copyOfRange(bytes, UTF8_BOM.length, bytes.length) : bytes;
        String raw = decodeUtf8(payload);
        if (raw.indexOf('\u0000') >= 0) {
            throw KnowledgeDocException.bad("IMPORT_NOT_TEXT", "文件不是纯文本");
        }
        String format = "text/markdown".equals(contentType) ? "MARKDOWN" : "TEXT";
        String parserVersion = "MARKDOWN".equals(format) ? MARKDOWN_PARSER : TEXT_PARSER;
        String normalized = "MARKDOWN".equals(format) ? markdownToPlain(raw) : textToPlain(raw);
        if (normalized.isBlank()) {
            throw KnowledgeDocException.bad("IMPORT_BODY_EMPTY", "解析后正文为空");
        }
        if (normalized.length() > KnowledgeChunker.MAX_DOCUMENT_CHARS) {
            throw KnowledgeDocException.bad("IMPORT_CHARS_OUT_OF_RANGE",
                    "解析后正文超过 " + KnowledgeChunker.MAX_DOCUMENT_CHARS + " 字符限制");
        }
        String title = resolveTitle(fallbackTitle, raw, filename);
        String rawNormalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        return new Parsed(title, rawNormalized, normalized, format, parserVersion, bom, contentType);
    }

    static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2];
    }

    static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw KnowledgeDocException.bad("IMPORT_ENCODING_INVALID", "文件必须是 UTF-8 编码");
        }
    }

    static String resolveTitle(String fallback, String raw, String filename) {
        String candidate = cleanTitle(fallback);
        if (candidate.isEmpty()) {
            Matcher h1 = H1.matcher(raw);
            if (h1.find()) {
                candidate = cleanTitle(h1.group(1));
            }
        }
        if (candidate.isEmpty()) {
            candidate = cleanTitle(baseNameOf(filename));
        }
        if (candidate.isEmpty()) {
            throw KnowledgeDocException.bad("IMPORT_TITLE_INVALID", "无法确定文档标题");
        }
        if (candidate.length() > MAX_TITLE_CHARS) {
            candidate = candidate.substring(0, MAX_TITLE_CHARS).strip();
        }
        return candidate;
    }

    private static String cleanTitle(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = INLINE_MARKS.matcher(value).replaceAll("");
        cleaned = LINK.matcher(cleaned).replaceAll("$1");
        cleaned = Jsoup.clean(cleaned, Safelist.none());
        return CONTROL.matcher(cleaned).replaceAll("").replace('\u00A0', ' ').strip();
    }

    /** Markdown → 纯文本。保留文字内容与结构顺序，丢弃标记与残余 HTML。 */
    static String markdownToPlain(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = FRONT_MATTER.matcher(text).replaceFirst("");
        text = FENCED_CODE.matcher(text).replaceAll("");
        text = IMAGE.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = HEADING.matcher(text).replaceAll("$2");
        text = BLOCKQUOTE.matcher(text).replaceAll("");
        text = LIST_MARKER.matcher(text).replaceAll("");
        text = TABLE_DIVIDER.matcher(text).replaceAll("");
        text = INLINE_MARKS.matcher(text).replaceAll("");
        text = text.replace('|', ' ');
        return finish(text);
    }

    /** 纯文本 → 纯文本。只做换行统一、HTML 清洗与控制字符处理。 */
    static String textToPlain(String raw) {
        return finish(raw.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private static String finish(String text) {
        // jsoup 白名单清理：彻底移除 script/style/iframe 与一切标签，避免入库正文携带可执行内容
        Document document = Jsoup.parse(text);
        document.select("script,style,noscript,iframe,object,embed,svg,math,form").remove();
        String plain = document.body().wholeText();
        plain = CONTROL.matcher(plain).replaceAll("");
        plain = plain.replace('\u00A0', ' ');
        plain = MULTI_BLANK.matcher(plain).replaceAll("\n\n");
        StringBuilder lines = new StringBuilder(plain.length());
        for (String line : plain.split("\n", -1)) {
            lines.append(line.stripTrailing()).append('\n');
        }
        return KnowledgeChunker.normalize(lines.toString());
    }

    /** 供审计与展示的截断，不改变入库正文。 */
    public static String preview(String text, int chars) {
        if (text == null) {
            return "";
        }
        String limited = text.length() <= chars ? text : text.substring(0, chars);
        return limited + (text.length() > chars ? "…" : "");
    }

    /** 数据库 title 列上限，解析标题按 80 截断后仍远小于该值。 */
    public static int maxTitleDbChars() {
        return MAX_TITLE_DB;
    }
}
