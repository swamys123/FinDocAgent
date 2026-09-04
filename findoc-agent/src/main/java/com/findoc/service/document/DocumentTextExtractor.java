package com.findoc.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DocumentTextExtractor {
    public Extraction extract(String fileType, byte[] content) throws IOException {
        if ("text/plain".equals(fileType)) {
            return new Extraction(new String(content, StandardCharsets.UTF_8), null);
        }
        try (var pdf = Loader.loadPDF(content)) {
            return new Extraction(new PDFTextStripper().getText(pdf), pdf.getNumberOfPages());
        }
    }

    public record Extraction(String text, Integer pageCount) {}
}