package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The failure paths, which are the ones that have to behave without LibreOffice
 * installed. The happy path needs a real LibreOffice and so belongs in a manual
 * or integration run, not here — this project has no integration test
 * infrastructure to hang it on.
 */
class PdfConversionServiceTest {

    @TempDir Path work;

    private PdfConversionService service(boolean enabled, String executable) {
        ContractProperties properties = new ContractProperties();
        properties.getLibreoffice().setEnabled(enabled);
        properties.getLibreoffice().setPath(executable);
        properties.getLibreoffice().setTimeoutSeconds(10);
        return new PdfConversionService(properties);
    }

    @Test
    void reportsWhetherConversionIsAvailableAtAll() {
        assertThat(service(true, "soffice").isEnabled()).isTrue();
        assertThat(service(false, "soffice").isEnabled()).isFalse();
    }

    @Test
    void refusesToConvertWhenDisabled() throws Exception {
        Path docx = Files.writeString(work.resolve("a.docx"), "not really a docx");

        assertThatThrownBy(() -> service(false, "soffice").convertToPdf(docx))
                .isInstanceOf(ContractDocumentException.class)
                .hasMessageContaining("disabled");
    }

    /**
     * The common misconfiguration: LibreOffice is not installed, or the path is
     * wrong. The message has to name the setting that fixes it, because this is
     * what a developer hits on a fresh machine.
     */
    @Test
    void explainsHowToFixAMissingLibreOffice() throws Exception {
        Path docx = Files.writeString(work.resolve("a.docx"), "not really a docx");

        assertThatThrownBy(() -> service(true, "definitely-not-a-real-binary-xyz").convertToPdf(docx))
                .isInstanceOf(ContractDocumentException.class)
                .hasMessageContaining("contract.libreoffice.path")
                .hasMessageContaining("contract.libreoffice.enabled=false");
    }

    /** The failure detail belongs in the exception for the log; it must not be
     *  the thing an API client sees. That split is enforced by ContractService
     *  and ApiExceptionHandler, both of which rely on this being the type. */
    @Test
    void failsAsAContractDocumentExceptionSoTheDetailStaysServerSide() {
        Path missing = work.resolve("does-not-exist.docx");

        assertThatThrownBy(() -> service(true, "definitely-not-a-real-binary-xyz").convertToPdf(missing))
                .isInstanceOf(ContractDocumentException.class);
    }

    @Test
    void doesNotLeaveItsTemporaryDirectoriesBehind() throws Exception {
        Path docx = Files.write(work.resolve("a.docx"), "x".getBytes(StandardCharsets.UTF_8));
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));

        long before = countConversionDirs(tempRoot);
        try {
            service(true, "definitely-not-a-real-binary-xyz").convertToPdf(docx);
        } catch (ContractDocumentException expected) {
            // the point is the cleanup below, not the failure
        }

        assertThat(countConversionDirs(tempRoot)).isEqualTo(before);
    }

    private long countConversionDirs(Path tempRoot) throws Exception {
        if (!Files.isDirectory(tempRoot)) {
            return 0;
        }
        try (var paths = Files.list(tempRoot)) {
            return paths.filter(p -> p.getFileName().toString().startsWith("crm-contract-pdf-")).count();
        }
    }
}
