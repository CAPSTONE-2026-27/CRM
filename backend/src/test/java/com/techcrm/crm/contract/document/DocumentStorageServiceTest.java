package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageServiceTest {

    @TempDir Path root;

    DocumentStorageService storage;

    @BeforeEach
    void setUp() {
        ContractProperties properties = new ContractProperties();
        properties.getStorage().setRoot(root.toString());
        storage = new DocumentStorageService(properties);
    }

    @Test
    void storesUnderTheOrganizationAndContractNumber() {
        var stored = storage.store(7L, "CTR-000042", "pdf", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(stored.relativePath()).startsWith("7/CTR-000042/CTR-000042-");
        assertThat(stored.relativePath()).endsWith(".pdf");
        assertThat(stored.sizeBytes()).isEqualTo(5);
        assertThat(Files.exists(stored.absolutePath())).isTrue();
    }

    @Test
    void readsBackWhatItStored() {
        var stored = storage.store(7L, "CTR-000042", "docx", "contract bytes".getBytes(StandardCharsets.UTF_8));

        assertThat(storage.exists(stored.relativePath())).isTrue();
        assertThat(new String(storage.read(stored.relativePath()), StandardCharsets.UTF_8))
                .isEqualTo("contract bytes");
    }

    /**
     * Regenerating a contract must not overwrite the document a customer may be
     * midway through signing, so the file name carries a timestamp on top of the
     * contract number.
     */
    @Test
    void aRegenerationDoesNotOverwriteThePreviousDocument() throws Exception {
        var first = storage.store(7L, "CTR-000042", "pdf", "v1".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1100); // the stamp has second resolution
        var second = storage.store(7L, "CTR-000042", "pdf", "v2".getBytes(StandardCharsets.UTF_8));

        assertThat(second.relativePath()).isNotEqualTo(first.relativePath());
        assertThat(new String(storage.read(first.relativePath()), StandardCharsets.UTF_8)).isEqualTo("v1");
        assertThat(new String(storage.read(second.relativePath()), StandardCharsets.UTF_8)).isEqualTo("v2");
    }

    @Test
    void keepsEachOrganizationsDocumentsInItsOwnSubtree() {
        var seven = storage.store(7L, "CTR-000001", "pdf", "a".getBytes(StandardCharsets.UTF_8));
        var eight = storage.store(8L, "CTR-000002", "pdf", "b".getBytes(StandardCharsets.UTF_8));

        assertThat(seven.relativePath()).startsWith("7/");
        assertThat(eight.relativePath()).startsWith("8/");
    }

    /** The stored path comes out of the database, so a row that somehow acquired
     *  a traversal must not be able to read the server's filesystem through the
     *  download endpoints. */
    @Test
    void refusesAStoredPathThatEscapesTheRoot() {
        assertThatThrownBy(() -> storage.resolve("../../../etc/passwd"))
                .isInstanceOf(ContractDocumentException.class)
                .hasMessageContaining("escapes the document root");
    }

    @Test
    void reportsAMissingDocumentRatherThanThrowing() {
        assertThat(storage.exists("7/CTR-999999/nothing.pdf")).isFalse();
        assertThat(storage.exists(null)).isFalse();
    }

    @Test
    void readingAMissingDocumentIsADocumentFailure() {
        assertThatThrownBy(() -> storage.read("7/CTR-999999/nothing.pdf"))
                .isInstanceOf(ContractDocumentException.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void temporaryFilesAreWrittenAndCleanedUp() {
        Path temporary = storage.writeTemporary("CTR-000042.docx", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.exists(temporary)).isTrue();
        storage.deleteTemporary(temporary);
        assertThat(Files.exists(temporary)).isFalse();
    }
}
