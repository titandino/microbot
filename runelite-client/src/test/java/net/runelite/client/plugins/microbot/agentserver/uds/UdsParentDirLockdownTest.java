package net.runelite.client.plugins.microbot.agentserver.uds;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Parent-dir permissions are the real access gate for the UDS socket — most
 * filesystems ignore mode bits on socket files themselves, so anyone with
 * traversal rights to {@code ~/.runelite} could connect regardless of the
 * socket's own permissions. This test pins the lockdown behaviour: on POSIX
 * the parent must be chmod 0700 after {@code start()}, no more, no less.
 *
 * Windows/ACL paths exist in the implementation but are exercised only in
 * manual QA; asserting ACL shape requires a running Windows JDK.
 */
public class UdsParentDirLockdownTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void posixParentIsLockedToOwnerOnly() throws IOException {
        Assume.assumeTrue("POSIX-only test",
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path parent = tempFolder.newFolder().toPath().resolve("agent");
        Path socket = parent.resolve("agent.sock");

        // Call the exact entry point start() uses.
        UdsHttpServer.lockDownParentDirectory(socket);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(parent);
        assertTrue("owner read required", perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue("owner write required", perms.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue("owner execute required", perms.contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse("group read must be denied", perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse("group write must be denied", perms.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse("group execute must be denied", perms.contains(PosixFilePermission.GROUP_EXECUTE));
        assertFalse("others read must be denied", perms.contains(PosixFilePermission.OTHERS_READ));
        assertFalse("others write must be denied", perms.contains(PosixFilePermission.OTHERS_WRITE));
        assertFalse("others execute must be denied", perms.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    public void missingParentIsCreatedWithLockdown() throws IOException {
        Assume.assumeTrue("POSIX-only test",
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path root = tempFolder.newFolder().toPath();
        Path socket = root.resolve("nested").resolve("more-nested").resolve("agent.sock");
        assertFalse(Files.exists(socket.getParent()));

        UdsHttpServer.lockDownParentDirectory(socket);

        assertTrue(Files.isDirectory(socket.getParent()));
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(socket.getParent());
        assertEquals(PosixFilePermission.OWNER_READ,    perms.stream().filter(p -> p == PosixFilePermission.OWNER_READ).findFirst().orElseThrow());
    }
}
