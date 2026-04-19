package net.runelite.client.plugins.microbot.util.antiban;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

public class UnsafeUsageGuardTest {

	private static final Path MICROBOT_SRC = Path.of(
			"src/main/java/net/runelite/client/plugins/microbot");

	@Test
	public void noMicrobotSourceImportsSunMiscUnsafe() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(MICROBOT_SRC)) {
			paths.filter(p -> p.toString().endsWith(".java"))
					.forEach(p -> {
						try {
							for (String line : Files.readAllLines(p)) {
								if (line.contains("sun.misc.Unsafe")) {
									offenders.add(MICROBOT_SRC.relativize(p) + " :: " + line.trim());
									break;
								}
							}
						} catch (IOException ignored) {
						}
					});
		}
		assertTrue("sun.misc.Unsafe must not appear in microbot sources — it's a forensic bot signature. Offenders: " + offenders,
				offenders.isEmpty());
	}
}
