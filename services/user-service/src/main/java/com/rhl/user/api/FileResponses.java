package com.rhl.user.api;

import com.rhl.user.application.driver.DocumentFileService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Document files are always downloads, never rendered inline: the browser gets the real type,
 * no sniffing, no caching and no permission to run anything from them.
 */
final class FileResponses {

    private FileResponses() {
    }

    static ResponseEntity<byte[]> attachment(DocumentFileService.Download file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(file.content());
    }
}
