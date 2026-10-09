package com.docuware.ai;

import java.util.List;

public interface AiProvider {
    boolean enabled();
    String embeddingModel();
    String chatModel();
    List<float[]> embed(List<String> texts);
    String generate(String instructions, String input);
}
