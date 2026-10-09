package com.docuware.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContextBudgetTest {
    @Test void boundsUnicodeWithoutLosingOrSplittingCodePoints() {
        String text = "漢字😀English".repeat(3000);
        var parts = ContextBudget.split(text, 6000);
        assertThat(String.join("", parts)).isEqualTo(text);
        assertThat(parts).allSatisfy(part -> {
            assertThat(ContextBudget.bytes(part)).isLessThanOrEqualTo(6000);
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1))).isFalse();
        });
        assertThat(ContextBudget.prefix("😀", 3)).isEmpty();
    }
}
