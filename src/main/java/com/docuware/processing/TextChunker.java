package com.docuware.processing;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class TextChunker {
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 1800, text.length());
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start + 900) end = boundary;
                if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end == text.length()) break;
            start = Math.max(start + 1, end - 200);
            if (Character.isLowSurrogate(text.charAt(start))) start++;
        }
        return chunks;
    }
}
