package com.docuware.processing;

import com.docuware.document.Document;
import com.docuware.storage.StorageService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExtractionTest {
    @TempDir Path directory;
    @Test void pdfAndDocxExtractRealText() throws Exception {
        Path pdfPath = directory.resolve("sample.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText("Spring Boot document intelligence"); stream.endText();
            }
            pdf.save(pdfPath.toFile());
        }
        Path docxPath = directory.resolve("sample.docx");
        try (var docx = new XWPFDocument(); OutputStream output = Files.newOutputStream(docxPath)) {
            docx.createParagraph().createRun().setText("Spring Boot document intelligence"); docx.write(output);
        }
        StorageService storage = mock(StorageService.class);
        DocumentTextExtractor extractor = new DocumentTextExtractor(storage);
        for (String type : List.of("pdf", "docx")) {
            Document d = new Document(); d.fileType = type; d.storageKey = "sample." + type;
            when(storage.load(d.storageKey)).thenReturn(new FileSystemResource(directory.resolve(d.storageKey)));
            assertThat(extractor.extract(d)).contains("Spring Boot document intelligence");
        }
    }
    @Test void chunkingCoversLongContentAndTerminatesForUnicode() {
        String text = "Spring Boot dependency injection. ".repeat(400) + "😀".repeat(2000);
        List<String> chunks = new TextChunker().split(text);
        assertThat(chunks).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(1800));
        assertThat(chunks.getLast()).endsWith("😀");
        assertThat(new TextChunker().split("")).isEmpty();
        for (String chunk : chunks) {
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
        }
    }
}
