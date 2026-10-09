package com.docuware.storage;

import com.docuware.exception.ApiException;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FileValidator {
    private static final Map<String, String> TYPES = Map.of("pdf", "application/pdf",
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "txt", "text/plain", "csv", "text/csv", "md", "text/markdown");
    public Validated validate(MultipartFile file) {
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "File must not be empty");
        if (file.getSize() > 20L * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Maximum file size is 20 MB");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (filename.isBlank() || filename.length() > 255) throw unsupported();
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = TYPES.get(extension);
        if (mime == null) throw unsupported();
        String supplied = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream").split(";")[0].trim().toLowerCase(Locale.ROOT);
        Set<String> accepted = new HashSet<>(Set.of(mime, "application/octet-stream"));
        if (Set.of("txt", "csv", "md").contains(extension)) accepted.add("text/plain");
        if (extension.equals("csv")) accepted.add("application/vnd.ms-excel");
        if (!accepted.contains(supplied)) throw unsupported();
        try (InputStream in = file.getInputStream()) {
            if (extension.equals("pdf")) {
                if (!Arrays.equals(in.readNBytes(5), "%PDF-".getBytes(StandardCharsets.US_ASCII))) throw unsupported();
            } else if (extension.equals("docx")) {
                validateDocx(in);
            } else {
                var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                try (Reader reader = new InputStreamReader(in, decoder)) {
                    char[] buffer = new char[8192]; int read;
                    while ((read = reader.read(buffer)) >= 0) {
                        for (int i = 0; i < read; i++) if (buffer[i] == 0) throw unsupported();
                    }
                }
            }
        } catch (IOException e) { throw unsupported(); }
        return new Validated(filename, extension, mime);
    }
    private void validateDocx(InputStream input) throws IOException {
        boolean document = false, contentTypes = false;
        long expanded = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10000) throw unsupported();
                if (entry.getName().equals("word/document.xml")) document = true;
                if (entry.getName().equals("[Content_Types].xml")) contentTypes = true;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > 50L * 1024 * 1024) throw unsupported();
                }
            }
        }
        if (!document || !contentTypes) throw unsupported();
    }
    private ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "Upload a valid PDF, DOCX, or UTF-8 TXT, CSV, Markdown file with a matching content type");
    }
    public record Validated(String filename, String extension, String contentType) {}
}
