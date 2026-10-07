package com.rhl.user.domain.driver;

import com.rhl.user.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UploadInspectorTest {

    private final UploadInspector inspector = new UploadInspector(1024 * 1024, 4_000_000);

    @Test
    void imagesAreRecognisedByTheirBytesAndReencodedWithoutMetadata() throws IOException {
        byte[] png = pngWithText("GPSLatitude", "10.7769");
        assertThat(new String(png, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");

        UploadInspector.Accepted accepted = inspector.inspect("license.PNG", png);

        assertThat(accepted.contentType()).isEqualTo(UploadInspector.PNG);
        assertThat(new String(accepted.content(), StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(accepted.content())).getWidth()).isEqualTo(40);

        assertThat(inspector.inspect("id.jpeg", jpeg()).contentType()).isEqualTo(UploadInspector.JPEG);
    }

    @Test
    void theNameMustAgreeWithTheContent() throws IOException {
        assertThatThrownBy(() -> inspector.inspect("scan.pdf", pngWithText("a", "b")))
                .isInstanceOf(DomainException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> inspector.inspect("noextension", jpeg())).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MZ\u0090\u0000 executable", "<html><script>alert(1)</script>", "GIF89a....",
            "PK\u0003\u0004 zip"})
    void otherTypesAreRefusedWhateverTheyAreCalled(String content) {
        assertThatThrownBy(() -> inspector.inspect("photo.jpg", content.getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(DomainException.class).hasMessageContaining("Only JPEG, PNG and PDF");
    }

    @Test
    void aJpegHeaderOnTopOfSomethingElseIsNotAnImage() {
        byte[] fake = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'n', 'o', 't', ' ', 'a', 'n', ' ', 'i', 'm', 'g'};
        assertThatThrownBy(() -> inspector.inspect("x.jpg", fake))
                .isInstanceOf(DomainException.class).hasMessageContaining("cannot be read");
    }

    @Test
    void sizeAndDimensionsAreLimited() throws IOException {
        assertThatThrownBy(() -> inspector.inspect("big.pdf", new byte[1024 * 1024 + 1]))
                .isInstanceOf(DomainException.class).hasMessageContaining("larger than");
        assertThatThrownBy(() -> inspector.inspect("empty.pdf", new byte[0])).isInstanceOf(DomainException.class);
        UploadInspector small = new UploadInspector(1024 * 1024, 100);
        assertThatThrownBy(() -> small.inspect("wide.png", pngWithText("a", "b")))
                .isInstanceOf(DomainException.class).hasMessageContaining("too large");
    }

    @Test
    void plainPdfsAreKeptAsTheyAre() {
        byte[] pdf = pdf("<< /Type /Catalog /Pages 2 0 R /Metadata << /JSONData 1 >> >>");
        UploadInspector.Accepted accepted = inspector.inspect("insurance.pdf", pdf);
        assertThat(accepted.contentType()).isEqualTo(UploadInspector.PDF);
        assertThat(accepted.content()).isEqualTo(pdf);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/OpenAction << /S /JavaScript /JS (app.alert(1)) >>", "/AA << /O << /JS(x) >> >>",
            "/Launch << /F (cmd.exe) >>", "/EmbeddedFiles << /Names [(a.exe) 3 0 R] >>", "/Encrypt 5 0 R",
            "/AcroForm << /XFA 7 0 R >>", "/Annots [<< /Subtype /FileAttachment /FS 9 0 R >>]"})
    void pdfsWithActiveOrHiddenContentAreRefused(String object) {
        assertThatThrownBy(() -> inspector.inspect("doc.pdf", pdf("<< " + object + " >>")))
                .isInstanceOf(DomainException.class).hasMessageContaining("PDF files with");
    }

    @Test
    void aFileBacksOneDocumentOfItsOwnDriver() {
        UUID driver = UUID.randomUUID();
        DocumentFile file = DocumentFile.uploaded(UUID.randomUUID(), driver, UploadInspector.PDF, 10, "0".repeat(64),
                Instant.EPOCH);
        assertThat(file.getObjectKey()).isEqualTo("drivers/" + driver + "/" + file.getId());

        assertThatThrownBy(() -> file.attach(UUID.randomUUID(), Instant.EPOCH)).isInstanceOf(DomainException.class);
        file.attach(driver, Instant.EPOCH);
        assertThat(file.getStatus()).isEqualTo(DocumentFile.Status.ATTACHED);
        assertThatThrownBy(() -> file.attach(driver, Instant.EPOCH))
                .isInstanceOf(DomainException.class).hasMessageContaining("already attached");
    }

    private static byte[] pdf(String body) {
        return ("%PDF-1.7\n1 0 obj " + body + " endobj\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    /** A 40x30 PNG carrying a tEXt chunk, the way phones store location and camera data. */
    public static byte[] pngWithText(String key, String value) throws IOException {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
        IIOMetadataNode text = new IIOMetadataNode("tEXt");
        IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
        entry.setAttribute("keyword", key);
        entry.setAttribute("value", value);
        text.appendChild(entry);
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
        root.appendChild(text);
        metadata.mergeTree("javax_imageio_png_1.0", root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(new IIOImage(image, null, metadata));
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
