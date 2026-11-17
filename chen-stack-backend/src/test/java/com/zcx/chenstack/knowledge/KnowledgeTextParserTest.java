package com.zcx.chenstack.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Markdown / 纯文本解析与安全清洗测试。 */
class KnowledgeTextParserTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void stripsUtf8BomAndRecordsFact() {
        byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, utf8("# 标题\n正文"));
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse("a.md", withBom, null);
        assertTrue(parsed.bomPresent());
        assertEquals("标题", parsed.title());
        assertFalse(parsed.normalizedText().startsWith("\uFEFF"));
    }

    @Test
    void rejectsNonUtf8() {
        byte[] gbk = "中文".getBytes(java.nio.charset.Charset.forName("GBK"));
        KnowledgeDocException error = assertThrows(KnowledgeDocException.class,
                () -> KnowledgeTextParser.parse("a.txt", gbk, null));
        assertEquals("IMPORT_ENCODING_INVALID", error.code());
    }

    @Test
    void rejectsOversizeAndEmpty() {
        byte[] tooBig = new byte[KnowledgeTextParser.MAX_FILE_BYTES + 1];
        assertEquals("IMPORT_TOO_LARGE",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("a.txt", tooBig, null)).code());
        assertEquals("IMPORT_EMPTY",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("a.txt", new byte[0], null)).code());
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertEquals("IMPORT_EXTENSION_INVALID",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("a.pdf", utf8("x"), null)).code());
        assertEquals("IMPORT_EXTENSION_INVALID",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("noext", utf8("x"), null)).code());
    }

    @Test
    void markdownBecomesPlainText() {
        String markdown = """
                ---
                title: ignored
                ---
                # Redis 过期策略

                关键点 **惰性删除** 与 *定期删除*。

                - 第一项
                - 第二项

                > 引用块

                [链接文字](https://example.com/x)
                ![图片](https://example.com/a.png)

                ```java
                int a = 1;
                ```
                表格 | 值
                --- | ---
                """;
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse("redis.md", utf8(markdown), null);
        String text = parsed.normalizedText();
        assertEquals("Redis 过期策略", parsed.title());
        assertTrue(text.contains("惰性删除"));
        assertTrue(text.contains("定期删除"));
        assertTrue(text.contains("链接文字"));
        assertTrue(text.contains("第一项"));
        assertFalse(text.contains("```"));
        assertFalse(text.contains("!["));
        assertFalse(text.contains("title: ignored"));
    }

    @Test
    void stripsScriptsAndEventHandlers() {
        String markdown = "# 标题\n\n<script>alert(1)</script>\n\n正文 <img src=x onerror=alert(2)> 结束";
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse("x.md", utf8(markdown), null);
        String text = parsed.normalizedText();
        assertFalse(text.contains("script"));
        assertFalse(text.contains("alert(1)"));
        assertFalse(text.contains("<img"));
        assertTrue(text.contains("正文"));
    }

    @Test
    void controlCharactersRemovedButNulRejected() {
        // NUL 视为二进制特征，整份拒绝
        assertEquals("IMPORT_NOT_TEXT",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("x.txt", utf8("正\u0000文"), null)).code());
        // 其它控制字符只清洗，不拒绝
        String raw = "正\u0007文\u000B内容\n第二行\u001F";
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse("x.txt", utf8(raw), null);
        assertFalse(parsed.normalizedText().contains("\u0007"));
        assertFalse(parsed.normalizedText().contains("\u001F"));
        assertTrue(parsed.normalizedText().contains("正文内容"));
    }

    @Test
    void titleFallsBackToFileNameThenH1() {
        KnowledgeTextParser.Parsed byName = KnowledgeTextParser.parse("my-notes.md", utf8("没有标题"), null);
        assertEquals("my-notes", byName.title());
        KnowledgeTextParser.Parsed byH1 = KnowledgeTextParser.parse("a.md", utf8("# 一级标题\n正文"), null);
        assertEquals("一级标题", byH1.title());
        KnowledgeTextParser.Parsed byArg = KnowledgeTextParser.parse("a.md", utf8("# 一级\n正文"), " 指定标题 ");
        assertEquals("指定标题", byArg.title());
    }

    @Test
    void titleTruncatedTo80() {
        String longName = "长".repeat(120) + ".md";
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse(longName, utf8("正文"), null);
        assertEquals(80, parsed.title().length());
    }

    @Test
    void rejectsBodyOver20000Chars() {
        String big = "字".repeat(20_001);
        assertEquals("IMPORT_CHARS_OUT_OF_RANGE",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("a.txt", utf8(big), null)).code());
    }

    @Test
    void markdownTooLongAfterParsingIsRejected() {
        String big = ("# 标题\n" + "内容".repeat(10_001));
        assertEquals("IMPORT_CHARS_OUT_OF_RANGE",
                assertThrows(KnowledgeDocException.class,
                        () -> KnowledgeTextParser.parse("a.md", utf8(big), null)).code());
    }

    @Test
    void contentTypesAndExtensions() {
        assertEquals("text/markdown", KnowledgeTextParser.contentTypeOf("a.MD"));
        assertEquals("text/markdown", KnowledgeTextParser.contentTypeOf("a.markdown"));
        assertEquals("text/plain", KnowledgeTextParser.contentTypeOf("a.txt"));
        assertEquals("md", KnowledgeTextParser.extensionOf("dir\\a.md"));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
