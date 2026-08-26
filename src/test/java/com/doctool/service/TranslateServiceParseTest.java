package com.doctool.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 Google gtx 响应解析：只取译文分段，忽略语言标记和跟踪ID等字段
 */
class TranslateServiceParseTest {

    @Test
    void parse_onlyTakesTranslationSegments() {
        // 模拟真实 gtx 响应：译文分段 + 语言标记 + 末尾跟踪ID
        String body = "[[[\"你好世界\",\"Hello World\",null,null,10],"
                + "[\"这是一个测试。\",\"This is a test.\",null,null,10]],"
                + "null,\"en\",null,null,null,1,[],[[\"en\"],null,[1],[\"en\"]],"
                + "\"6ffafab0da7e640be86ac09d0d5e539c\"]";
        String result = TranslateService.parseTranslateResponse(body, "fallback");
        assertEquals("你好世界这是一个测试。", result);
    }

    @Test
    void parse_unescapesUnicodeAndStripsZeroWidth() {
        // JSON 中的   转义应被解码，且零宽字符最终被剔除
        String body = "[[[\"简介：丽达\\u200b越来\\u200b越沉浸\",\"intro\",null,null,10]],null,\"en\"]";
        String result = TranslateService.parseTranslateResponse(body, "fallback");
        assertEquals("简介：丽达越来越沉浸", result);
    }

    @Test
    void parse_handlesEscapedQuotes() {
        String body = "[[[\"他说：\\\"你好\\\"\",\"He said: \\\"hi\\\"\",null,null,10]],null,\"en\"]";
        assertEquals("他说：\"你好\"", TranslateService.parseTranslateResponse(body, "fallback"));
    }

    @Test
    void parse_fallbackOnGarbage() {
        assertEquals("fallback", TranslateService.parseTranslateResponse("not json", "fallback"));
        assertEquals("fallback", TranslateService.parseTranslateResponse("{\"a\":1}", "fallback"));
        assertEquals("fallback", TranslateService.parseTranslateResponse("[[null]]", "fallback"));
    }
}
