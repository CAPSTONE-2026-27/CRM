package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Converts a generated DOCX to PDF with headless LibreOffice.
 *
 * LibreOffice rather than a Java PDF library because the DOCX is the contract of
 * record: the PDF the customer signs has to be the same document, laid out by
 * something that understands Word's own layout rules. A reimplementation would
 * drift from the DOCX exactly where it matters — page breaks in the middle of a
 * signature block.
 *
 * Each conversion gets its own profile directory. Without one, concurrent
 * {@code soffice} invocations contend for the single default user profile and
 * the second one exits immediately having converted nothing, which shows up as
 * an intermittently missing PDF rather than as an error.
 */
@Service
public class PdfConversionService {

    private static final Logger log = LoggerFactory.getLogger(PdfConversionService.class);

    private final ContractProperties properties;

    public PdfConversionService(ContractProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.getLibreoffice().isEnabled();
    }

    /**
     * Converts {@code docx} and returns the PDF bytes.
     *
     * @throws ContractDocumentException if LibreOffice is missing, fails, times
     *                                   out, or exits successfully without
     *                                   having written a PDF — which it does
     *                                   when handed a file it cannot parse.
     */
    public byte[] convertToPdf(Path docx) {
        ContractProperties.LibreOffice config = properties.getLibreoffice();
        if (!config.isEnabled()) {
            throw new ContractDocumentException("PDF conversion is disabled (contract.libreoffice.enabled=false)");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("crm-contract-pdf-");
            Path outDir = Files.createDirectory(workDir.resolve("out"));
            Path profileDir = workDir.resolve("profile");

            List<String> command = List.of(
                    config.getPath(),
                    "--headless",
                    "--norestore",
                    // A URL, not a path: LibreOffice ignores a bare path here.
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--convert-to", "pdf:writer_pdf_Export",
                    "--outdir", outDir.toString(),
                    docx.toAbsolutePath().toString());

            log.debug("Converting {} to PDF: {}", docx.getFileName(), String.join(" ", command));

            // Output goes to a file rather than a pipe: reading a pipe means
            // blocking until EOF, which only comes when the process exits — so
            // the timeout below would never get a chance to fire. Leaving the
            // pipe unread instead risks the process blocking on a full buffer.
            Path logFile = workDir.resolve("soffice.log");

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .directory(workDir.toFile())
                    .start();

            if (!process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ContractDocumentException(
                        "LibreOffice did not finish converting " + docx.getFileName()
                                + " within " + config.getTimeoutSeconds() + "s");
            }

            String output = readLog(logFile);
            if (process.exitValue() != 0) {
                throw new ContractDocumentException(
                        "LibreOffice exited with " + process.exitValue() + ": " + output);
            }

            Path pdf = findPdf(outDir);
            if (pdf == null) {
                // LibreOffice reports success for input it silently refuses, so
                // the absence of a file is the only reliable failure signal.
                throw new ContractDocumentException(
                        "LibreOffice reported success but produced no PDF for "
                                + docx.getFileName() + ". Output: " + output);
            }
            return Files.readAllBytes(pdf);

        } catch (IOException e) {
            throw new ContractDocumentException(
                    "Could not run LibreOffice (" + config.getPath() + "). "
                            + "Check contract.libreoffice.path, or set contract.libreoffice.enabled=false "
                            + "to generate DOCX only.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContractDocumentException("PDF conversion was interrupted", e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /** Best-effort: an unreadable log must not mask the conversion result the
     *  caller is actually waiting on. */
    private String readLog(Path logFile) {
        try {
            if (!Files.exists(logFile)) {
                return "";
            }
            return new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** LibreOffice names the output after the input, but the exact name depends
     *  on the filter, so the directory is searched rather than guessed. */
    private Path findPdf(Path outDir) throws IOException {
        try (Stream<Path> files = Files.list(outDir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete temporary file {}", path, e);
                }
            });
        } catch (IOException e) {
            // A leaked temp directory is a housekeeping problem, not a reason to
            // fail a conversion that already succeeded.
            log.warn("Could not clean up temporary conversion directory {}", directory, e);
        }
    }
}
