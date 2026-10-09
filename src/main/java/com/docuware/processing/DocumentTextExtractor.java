package com.docuware.processing;

import com.docuware.document.Document;
import com.docuware.storage.StorageService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {
    private static final int MAX_CHARACTERS = 1_000_000;
    private final StorageService storage;
    public DocumentTextExtractor(StorageService storage) { this.storage = storage; }
    public String extract(Document document) throws IOException {
        var resource = storage.load(document.storageKey);
        String text;
        if (document.fileType.equals("pdf")) {
            try (var pdf = Loader.loadPDF(resource.getFile())) {
                if (pdf.getNumberOfPages() > 1000) throw new IOException("Too many PDF pages");
                BoundedWriter writer = new BoundedWriter(MAX_CHARACTERS);
                new PDFTextStripper().writeText(pdf, writer);
                text = writer.toString();
            }
        } else if (document.fileType.equals("docx")) {
            try (InputStream stream = resource.getInputStream(); var docx = new XWPFDocument(stream);
                 var extractor = new XWPFWordExtractor(docx)) { text = extractor.getText(); }
        } else {
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                BoundedWriter writer = new BoundedWriter(MAX_CHARACTERS); reader.transferTo(writer); text = writer.toString();
            }
        }
        if (text.length() > MAX_CHARACTERS) throw new IOException("Extracted text exceeds limit");
        text = text.replace("\u0000", "").trim();
        if (text.isBlank()) throw new IOException("Document has no extractable text; scanned documents require OCR");
        return text;
    }
    private static class BoundedWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        private final int limit;
        BoundedWriter(int limit) { this.limit = limit; }
        public void write(char[] buffer, int offset, int length) throws IOException {
            if (content.length() + length > limit) throw new IOException("Extracted text exceeds limit");
            content.append(buffer, offset, length);
        }
        public void flush() {}
        public void close() {}
        public String toString() { return content.toString(); }
    }
}
