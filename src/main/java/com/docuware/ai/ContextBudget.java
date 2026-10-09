package com.docuware.ai;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** A conservative UTF-8 byte budget bounds context even for multibyte languages. */
final class ContextBudget {
    private ContextBudget() {}
    static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    static String prefix(String text, int maxBytes) {
        int end = 0, used = 0;
        while (end < text.length()) {
            int point = text.codePointAt(end);
            int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (used + size > maxBytes) break;
            used += size; end += Character.charCount(point);
        }
        return text.substring(0, end);
    }
    static List<String> split(String text, int maxBytes) {
        if (maxBytes < 4) throw new IllegalArgumentException("Context budget must fit a Unicode code point");
        List<String> result = new ArrayList<>(); int offset = 0;
        while (offset < text.length()) {
            String part = prefix(text.substring(offset), maxBytes); result.add(part); offset += part.length();
        }
        return result;
    }
}
