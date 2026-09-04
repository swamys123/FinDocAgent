package com.findoc.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PdfExtractionService {
    public String extract(Path path, String fileType) throws IOException {
        if ("text/plain".equals(fileType)) {
            return Files.readString(path);
        }
        try (var document = Loader.loadPDF(Files.readAllBytes(path))) {
            return new PDFTextStripper().getText(document);
        }
    }
}