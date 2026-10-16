package com.devops.agent.infrastructure;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.system.ApplicationTemp;
import org.springframework.data.mapping.context.AbstractMappingContext;
import org.springframework.data.mapping.model.BasicPersistentEntity;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.util.TypeInformation;
import org.springframework.util.ConcurrentLruCache;
import org.springframework.util.FileSystemUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批121：Spring Boot 3.5.15 及 Spring Data Commons 3.5.12 安全兼容回归；
 * 验证 ApplicationTemp 权限/软链接防御 (CVE-2026-40973) 及 PersistentPropertyPathFactory 缓存有界保护 (CVE-2026-41695/41716)。
 * 严禁使用大循环或耗时 DoS 载荷，通过确定性边界断言与反射结构校验。
 */
@Timeout(15)
class SpringBootDataSecurityCompatibilityTest {

    static class SampleEntity {
        String name;
    }

    static class MinimalMappingContext extends AbstractMappingContext<BasicPersistentEntity<Object, SampleProperty>, SampleProperty> {
        @Override
        @SuppressWarnings("unchecked")
        protected <S> BasicPersistentEntity<Object, SampleProperty> createPersistentEntity(TypeInformation<S> typeInformation) {
            return new BasicPersistentEntity<Object, SampleProperty>((TypeInformation<Object>) typeInformation);
        }

        @Override
        protected SampleProperty createPersistentProperty(Property property,
                                                          BasicPersistentEntity<Object, SampleProperty> owner,
                                                          SimpleTypeHolder simpleTypeHolder) {
            return new SampleProperty(property, owner, simpleTypeHolder);
        }
    }

    static class SampleProperty extends org.springframework.data.mapping.model.AnnotationBasedPersistentProperty<SampleProperty> {
        public SampleProperty(Property property,
                              org.springframework.data.mapping.PersistentEntity<?, SampleProperty> owner,
                              SimpleTypeHolder simpleTypeHolder) {
            super(property, owner, simpleTypeHolder);
        }

        @Override
        protected org.springframework.data.mapping.Association<SampleProperty> createAssociation() {
            return null;
        }
    }

    @Test
    void applicationTempCreatesSecureOwnedDirectory() throws IOException {
        ApplicationTemp temp = new ApplicationTemp(SpringBootDataSecurityCompatibilityTest.class);
        File dir = temp.getDir();
        assertNotNull(dir);
        assertTrue(dir.isDirectory());
        Path path = dir.toPath();
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = Files.getFileAttributeView(path, PosixFileAttributeView.class).readAttributes().permissions();
            assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
            assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
            assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
        }
        FileSystemUtils.deleteRecursively(dir);
    }

    @Test
    void applicationTempRejectsExistingDirectoryWithPermissivePermissions(@TempDir Path testDir) throws IOException {
        ApplicationTemp temp = new ApplicationTemp(SpringBootDataSecurityCompatibilityTest.class);
        File dir = temp.getDir();
        Path path = dir.toPath();
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.getFileAttributeView(path, PosixFileAttributeView.class).setPermissions(EnumSet.allOf(PosixFilePermission.class));
            var ex = assertThrows(IllegalStateException.class, () -> new ApplicationTemp(SpringBootDataSecurityCompatibilityTest.class).getDir(),
                    "permissive permissions must be rejected by ApplicationTemp");
            assertTrue(ex.getMessage().contains("does not have the permissions"),
                    "failure message must detail permission violation");
        }
        FileSystemUtils.deleteRecursively(dir);
    }

    @Test
    void applicationTempRejectsSymlinkedDirectoryLocation(@TempDir Path testDir) throws IOException {
        ApplicationTemp temp = new ApplicationTemp(SpringBootDataSecurityCompatibilityTest.class);
        File dir = temp.getDir();
        Path path = dir.toPath();
        FileSystemUtils.deleteRecursively(dir);
        Path targetDir = testDir.resolve("symlink-target");
        Files.createDirectories(targetDir);
        try {
            Files.createSymbolicLink(path, targetDir);
            var ex = assertThrows(IllegalStateException.class, () -> new ApplicationTemp(SpringBootDataSecurityCompatibilityTest.class).getDir(),
                    "symlink in ApplicationTemp location must be rejected");
            assertTrue(ex.getMessage().contains("already exists but it is not a directory"),
                    "failure message must detail symlink non-directory violation");
        } finally {
            Files.deleteIfExists(path);
            FileSystemUtils.deleteRecursively(targetDir.toFile());
        }
    }

    @Test
    void persistentPropertyPathFactoryUsesConcurrentLruCache() throws Exception {
        var context = new MinimalMappingContext();
        Field factoryField = AbstractMappingContext.class.getDeclaredField("persistentPropertyPathFactory");
        factoryField.setAccessible(true);
        Object factory = factoryField.get(context);
        assertNotNull(factory);

        Field cacheField = factory.getClass().getDeclaredField("propertyPaths");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(factory);
        assertNotNull(cache);
        assertTrue(cache instanceof ConcurrentLruCache,
                "propertyPaths cache must use ConcurrentLruCache to bound heap consumption");
        assertEquals(512, ((ConcurrentLruCache<?, ?>) cache).size(),
                "default capacity must be bounded to 512");
    }

    @Test
    void healthyMappingContextResolutionWorksNormally() {
        var context = new MinimalMappingContext();
        assertNotNull(context.getPersistentEntity(SampleEntity.class));
        assertEquals(SampleEntity.class, context.getPersistentEntity(SampleEntity.class).getType());
    }
}
