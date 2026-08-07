package org.dddml.uniauth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceKeyFileTest {

    @TempDir
    Path tempDirectory;

    @Test
    void configuredKeyFileIsCreatedAndReused() {
        Path keyFile = tempDirectory.resolve("keys").resolve("signing-key.ser");

        JwtTokenService first = new JwtTokenService(keyFile.toString());
        JwtTokenService second = new JwtTokenService(keyFile.toString());

        assertThat(keyFile).exists();
        assertThat(second.getPublicKey().getEncoded())
                .containsExactly(first.getPublicKey().getEncoded());
    }

    @Test
    void generatedKeyFileIsOwnerOnlyOnPosixFileSystems() throws Exception {
        Path keyFile = tempDirectory.resolve("signing-key.ser");

        new JwtTokenService(keyFile.toString());

        FileStore fileStore = Files.getFileStore(keyFile);
        if (fileStore.supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(keyFile))
                    .isEqualTo(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE
                    ));
        }
    }

    @Test
    void invalidExistingKeyFileIsRejectedWithoutReplacement() throws Exception {
        Path keyFile = tempDirectory.resolve("invalid-key.ser");
        byte[] invalidKey = new byte[]{0, 0, 0, 1, 42};
        Files.write(keyFile, invalidKey);
        FileStore fileStore = Files.getFileStore(keyFile);
        if (fileStore.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                    keyFile,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE
                    )
            );
        }

        assertThatThrownBy(() -> new JwtTokenService(keyFile.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be loaded");
        assertThat(Files.readAllBytes(keyFile)).containsExactly(invalidKey);
    }
}
