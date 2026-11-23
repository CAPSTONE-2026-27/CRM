package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Where generated contract documents live.
 *
 * The local filesystem, because that is the only storage this CRM has: there is
 * no object store, no blob column, and no existing upload path anywhere in the
 * codebase to reuse. Introducing S3 for this one feature would add a dependency,
 * a credential and a failure mode that nothing else in the project needs.
 *
 * The root is configuration and the database stores paths relative to it, so
 * moving the store — including onto a mounted bucket — is a config change rather
 * than a data migration.
 */
@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** @param relativePath  what goes in the database
     *  @param absolutePath  where it actually is right now
     *  @param sizeBytes     recorded so a download can set Content-Length without a stat */
    public record StoredDocument(String relativePath, Path absolutePath, long sizeBytes) {
    }

    private final ContractProperties properties;

    public DocumentStorageService(ContractProperties properties) {
        this.properties = properties;
    }

    /**
     * Writes one document and returns where it went.
     *
     * The file name carries the contract number and a timestamp:
     * {@code 7/CTR-000042/CTR-000042-20260905-142530.pdf}. The contract number
     * alone would be unique across contracts but not across regenerations of the
     * same one — and overwriting the DOCX a customer is midway through signing
     * is not a recoverable mistake. The organization id leads so that one
     * tenant's documents are a single subtree, which is what makes a per-tenant
     * backup or deletion possible.
     */
    public StoredDocument store(Long organizationId, String contractNumber, String extension, byte[] content) {
        String fileName = contractNumber + "-" + STAMP.format(OffsetDateTime.now()) + "." + extension;
        String relativePath = organizationId + "/" + contractNumber + "/" + fileName;

        try {
            Path target = root().resolve(relativePath).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            log.info("Stored contract document {} ({} bytes)", relativePath, content.length);
            return new StoredDocument(relativePath, target, content.length);
        } catch (IOException e) {
            throw new ContractDocumentException("Could not store contract document " + relativePath, e);
        }
    }

    /** Used by the DOCX -> PDF step, which needs a real file on disk for
     *  LibreOffice to open. */
    public Path writeTemporary(String fileName, byte[] content) {
        try {
            Path directory = Files.createTempDirectory("crm-contract-");
            Path file = directory.resolve(fileName);
            Files.write(file, content);
            return file;
        } catch (IOException e) {
            throw new ContractDocumentException("Could not write a temporary copy of " + fileName, e);
        }
    }

    public void deleteTemporary(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        } catch (IOException e) {
            log.debug("Could not clean up temporary file {}", file, e);
        }
    }

    /**
     * Resolves a stored path back to a file.
     *
     * The normalised result is checked to still be under the root, so a path
     * that somehow acquired a {@code ../} — a hand-edited row, a restore from a
     * different layout — cannot be used to read an arbitrary file off the
     * server through the download endpoints.
     */
    public Path resolve(String relativePath) {
        Path root = root();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ContractDocumentException("Stored document path escapes the document root: " + relativePath);
        }
        return resolved;
    }

    public boolean exists(String relativePath) {
        return relativePath != null && Files.isRegularFile(resolve(relativePath));
    }

    public byte[] read(String relativePath) {
        Path path = resolve(relativePath);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ContractDocumentException("Stored contract document is unreadable: " + relativePath, e);
        }
    }

    private Path root() {
        try {
            Path root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
