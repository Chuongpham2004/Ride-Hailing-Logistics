package com.rhl.user.domain.driver;

import com.rhl.user.domain.DomainException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks an uploaded document file before it is stored (README §9: real MIME type, size,
 * extension and dangerous content). The type comes from the file's own bytes, never from the
 * client's Content-Type; the name only has to agree with it. Images are decoded and encoded
 * again, which proves they are images and drops their metadata (EXIF can hold GPS positions).
 * PDFs with active or hidden content are refused.
 */
public class UploadInspector {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String PDF = "application/pdf";

    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            JPEG, Set.of("jpg", "jpeg"),
            PNG, Set.of("png"),
            PDF, Set.of("pdf"));
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** PDF features that run code, launch programs, carry other files or cannot be inspected. */
    private static final List<String> PDF_FORBIDDEN = List.of("/JavaScript", "/JS", "/Launch", "/EmbeddedFile",
            "/EmbeddedFiles", "/FileAttachment", "/RichMedia", "/XFA", "/Encrypt");

    private final long maxBytes;
    private final long maxPixels;

    /** The bytes to store and their real type. */
    public record Accepted(String contentType, byte[] content) {
    }

    public UploadInspector(long maxBytes, long maxPixels) {
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
    }

    public Accepted inspect(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw DomainException.rule("The file is empty");
        }
        if (content.length > maxBytes) {
            throw DomainException.rule("The file is larger than " + (maxBytes / (1024 * 1024)) + " MB");
        }
        String type = sniff(content);
        if (type == null) {
            throw DomainException.rule("Only JPEG, PNG and PDF files are accepted");
        }
        String extension = extension(fileName);
        if (!EXTENSIONS.get(type).contains(extension)) {
            throw DomainException.rule("The file name does not match its content (" + type + ")");
        }
        return switch (type) {
            case PDF -> new Accepted(PDF, checkPdf(content));
            default -> new Accepted(type, reencode(content, type));
        };
    }

    static String sniff(byte[] content) {
        if (startsWith(content, JPEG_MAGIC)) {
            return JPEG;
        }
        if (startsWith(content, PNG_MAGIC)) {
            return PNG;
        }
        if (startsWith(content, PDF_MAGIC)) {
            return PDF;
        }
        return null;
    }

    private byte[] checkPdf(byte[] content) {
        // Objects can be hidden in compressed streams; what is visible is refused, the rest is
        // only ever served as an attachment with nosniff, never rendered by this platform.
        String text = new String(content, StandardCharsets.ISO_8859_1);
        for (String name : PDF_FORBIDDEN) {
            if (containsName(text, name)) {
                throw DomainException.rule("PDF files with scripts, attachments, forms or encryption are not accepted");
            }
        }
        return content;
    }

    private byte[] reencode(byte[] content, String type) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw DomainException.rule("The image cannot be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                // Checked from the header before decoding, so a small file cannot claim huge dimensions.
                if ((long) reader.getWidth(0) * reader.getHeight(0) > maxPixels) {
                    throw DomainException.rule("The image is too large");
                }
                BufferedImage image = reader.read(0);
                if (type.equals(JPEG) && image.getColorModel().hasAlpha()) {
                    throw DomainException.rule("The image cannot be read");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
                if (!ImageIO.write(image, type.equals(JPEG) ? "jpg" : "png", out)) {
                    throw DomainException.rule("The image cannot be read");
                }
                return out.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof DomainException domain) {
                throw domain;
            }
            throw DomainException.rule("The image cannot be read");
        }
    }

    /** A PDF name token: followed by a delimiter, so {@code /JS} does not match {@code /JSONData}. */
    private static boolean containsName(String text, String name) {
        int from = 0;
        while (true) {
            int at = text.indexOf(name, from);
            if (at < 0) {
                return false;
            }
            int end = at + name.length();
            if (end >= text.length() || " \t\r\n/<>[]()%{}".indexOf(text.charAt(end)) >= 0) {
                return true;
            }
            from = end;
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }
}
