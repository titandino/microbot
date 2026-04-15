/*
 * Copyright (c) 2026, Microbot
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 */
package net.runelite.client.plugins.microbot.threadsafety;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.fail;

/**
 * CI guardrail that fails when an {@code Rs2*} utility (under
 * {@code net.runelite.client.plugins.microbot.util}) or the new query API
 * (under {@code net.runelite.client.plugins.microbot.api}) calls a RuneLite
 * API method that the {@link ClientThreadScannerTest scanner} has inferred
 * to require the client thread, without doing so inside a
 * {@code clientThread.invoke*()} lambda.
 *
 * <h2>Definition of "inferred-list method"</h2>
 * Any {@code net.runelite.api.*} method invoked from a method that the
 * scanner classifies as one of:
 * <ul>
 *   <li>{@code REQUIRES_CLIENT_THREAD} ({@code assert client.isClientThread()})</li>
 *   <li>{@code EVENT_HANDLER} ({@code @Subscribe})</li>
 *   <li>{@code CONFIRMED_LAMBDA} (lambda body passed to {@code ClientThread.invoke*()})</li>
 * </ul>
 * In other words, the same set the scanner publishes under
 * <em>"Inferred RuneLite API methods needing client thread"</em> in
 * {@code docs/client-thread-manifest.md}.
 *
 * <h2>How the rule is enforced</h2>
 * For each method in a guarded class (Rs2* under {@code util/} or any class
 * under {@code api/}) that is <strong>not itself</strong> a
 * {@code CONFIRMED_LAMBDA}, the test rejects any direct invocation of an
 * inferred-list method.  Methods that need to call client-thread-only API
 * have to factor those calls into a lambda passed to
 * {@code Microbot.getClientThread().invoke(...)} /
 * {@code runOnClientThreadOptional(...)} — the lambda body then becomes a
 * {@code CONFIRMED_LAMBDA} and is permitted.
 *
 * <h2>Baseline</h2>
 * Pre-existing violations live in
 * {@code src/test/resources/threadsafety/client-thread-guardrail-baseline.txt}.
 * The test fails on:
 * <ul>
 *   <li>any new violation that is not in the baseline (regression), and</li>
 *   <li>any baseline entry that no longer matches a real violation
 *       (so the baseline does not silently rot).</li>
 * </ul>
 * Regenerate the baseline with
 * {@code -Dmicrobot.guardrail.regenerate-baseline=true} and commit the diff.
 */
public class ClientThreadGuardrailTest
{
	private static final String CLIENT_THREAD_INTERNAL = "net/runelite/client/callback/ClientThread";
	private static final String SUBSCRIBE_DESC = "Lnet/runelite/client/eventbus/Subscribe;";
	private static final String ASSERTION_ERROR_INTERNAL = "java/lang/AssertionError";
	private static final String IS_CLIENT_THREAD = "isClientThread";
	private static final String RUNELITE_API_PREFIX = "net/runelite/api/";

	private static final String UTIL_PACKAGE_PREFIX = "net/runelite/client/plugins/microbot/util/";
	private static final String QUERY_API_PACKAGE_PREFIX = "net/runelite/client/plugins/microbot/api/";

	private static final String BASELINE_RESOURCE = "/threadsafety/client-thread-guardrail-baseline.txt";
	private static final Path BASELINE_RELATIVE_PATH = Paths.get(
		"runelite-client", "src", "test", "resources", "threadsafety", "client-thread-guardrail-baseline.txt");

	private static final String REGENERATE_BASELINE_PROPERTY = "microbot.guardrail.regenerate-baseline";

	/**
	 * API classes/packages whose methods are inherently thread-safe and should
	 * never be flagged as client-thread violations.
	 *
	 * <p><b>Full-class exemptions</b> — every method is safe:</p>
	 * <ul>
	 *   <li>{@code net/runelite/api/Point} — {@code @Value} immutable canvas coordinate</li>
	 *   <li>{@code net/runelite/api/MenuAction} — Java enum ({@code ordinal()}, {@code getId()})</li>
	 * </ul>
	 *
	 * <p><b>Conditional package exemption</b> — safe unless the method accepts
	 * a live game parameter ({@code Client}, {@code WorldView}, {@code Scene}):</p>
	 * <ul>
	 *   <li>{@code net/runelite/api/coords/} — {@code @Value} types (WorldPoint, LocalPoint,
	 *       WorldArea). Pure getters and math are safe; factory methods like
	 *       {@code WorldPoint.fromLocal(Client, ...)} are <em>not</em> exempt because they
	 *       dereference live client/scene state.</li>
	 * </ul>
	 *
	 * <p><b>Criteria for adding new exemptions:</b></p>
	 * <ol>
	 *   <li>The class must be a true value type ({@code @Value}), enum, or pure-function utility.</li>
	 *   <li>None of its methods may read or mutate live game state (tile data, widget trees,
	 *       entity lists, scene graph).</li>
	 *   <li>If only <em>some</em> methods are safe, use the conditional-package approach with
	 *       {@link #LIVE_GAME_PARAM_PATTERN} to exclude unsafe ones.</li>
	 * </ol>
	 */
	private static final Set<String> THREAD_SAFE_FULL_CLASSES = Set.of(
		"net/runelite/api/Point",
		"net/runelite/api/MenuAction"
	);

	private static final String COORDS_PACKAGE_PREFIX = "net/runelite/api/coords/";

	/**
	 * Pattern matching parameter types that indicate a coords method accesses
	 * live game state and should NOT be exempt.  Matched against the
	 * pretty-printed parameter list (e.g. {@code "(Client, LocalPoint): WorldPoint"}).
	 */
	private static final Pattern LIVE_GAME_PARAM_PATTERN = Pattern.compile(
		"\\b(Client|WorldView|Scene)\\b");

	private enum Category
	{
		REQUIRES_CLIENT_THREAD,
		CHECKS_THREAD_GUARD,
		SELF_MARSHALLING,
		EVENT_HANDLER,
		CONFIRMED_LAMBDA
	}

	private static final class MethodFinding
	{
		final String className;
		final String methodName;
		final String methodDesc;
		final Set<Category> categories = new TreeSet<>();
		final List<String> rlApiInvocations = new ArrayList<>();
		final List<String> lambdaHandlesPassedToInvoke = new ArrayList<>();

		MethodFinding(String className, String methodName, String methodDesc)
		{
			this.className = className;
			this.methodName = methodName;
			this.methodDesc = methodDesc;
		}

		String simpleSignature()
		{
			return methodName + prettyDescriptor(methodDesc);
		}

		String key()
		{
			return className + "." + methodName + methodDesc;
		}

		String fullyQualified()
		{
			return className.replace('/', '.') + "#" + simpleSignature();
		}
	}

	@Test
	public void rs2AndQueryApiMustNotCallInferredListMethodsOffClientThread() throws IOException
	{
		Path repoRoot = resolveRepoRoot();
		List<Path> scanRoots = List.of(
			repoRoot.resolve("runelite-api/build/classes/java/main"),
			repoRoot.resolve("runelite-client/build/classes/java/main"));

		boolean anyClassesPresent = scanRoots.stream().anyMatch(Files::isDirectory);
		Assume.assumeTrue(
			"Compiled main classes not found under runelite-{api,client}/build/classes/java/main; "
				+ "run `./gradlew :client:compileJava` first or trigger via `./gradlew :client:runUnitTests` "
				+ "(which compiles main as a transitive dependency).",
			anyClassesPresent);

		Map<String, MethodFinding> findingsByKey = new LinkedHashMap<>();
		for (Path root : scanRoots)
		{
			if (!Files.isDirectory(root))
			{
				continue;
			}
			try (Stream<Path> stream = Files.walk(root))
			{
				List<Path> classFiles = stream
					.filter(p -> p.toString().endsWith(".class"))
					.collect(Collectors.toList());
				for (Path classFile : classFiles)
				{
					try (InputStream in = Files.newInputStream(classFile))
					{
						ClassReader reader = new ClassReader(in);
						ClassNode cn = new ClassNode();
						reader.accept(cn, ClassReader.SKIP_FRAMES);
						for (MethodNode mn : cn.methods)
						{
							MethodFinding finding = analyzeMethod(cn, mn);
							findingsByKey.put(finding.key(), finding);
						}
					}
					catch (IOException e)
					{
						System.err.println("[guardrail] Failed to read " + classFile + ": " + e);
					}
				}
			}
		}

		traverseLambdaReachability(findingsByKey);

		Set<String> inferredListMethods = collectInferredListMethods(findingsByKey);
		Set<String> violations = collectViolations(findingsByKey, inferredListMethods);

		if (Boolean.getBoolean(REGENERATE_BASELINE_PROPERTY))
		{
			Path baselinePath = repoRoot.resolve(BASELINE_RELATIVE_PATH);
			writeBaseline(baselinePath, violations);
			System.out.println("[guardrail] Wrote " + violations.size() + " entries to " + baselinePath);
			return;
		}

		Set<String> baseline = loadBaseline();
		Set<String> newViolations = new TreeSet<>(violations);
		newViolations.removeAll(baseline);
		Set<String> obsoleteBaseline = new TreeSet<>(baseline);
		obsoleteBaseline.removeAll(violations);

		if (newViolations.isEmpty() && obsoleteBaseline.isEmpty())
		{
			System.out.println("[guardrail] " + violations.size() + " known violation(s); no regressions.");
			return;
		}

		StringBuilder failure = new StringBuilder();
		if (!newViolations.isEmpty())
		{
			failure.append(newViolations.size())
				.append(" new client-thread guardrail violation(s) detected.\n")
				.append("An Rs2*/query-api method is calling a RuneLite API method that the scanner has inferred ")
				.append("to require the client thread, without being inside a clientThread.invoke* lambda:\n\n");
			for (String v : newViolations)
			{
				failure.append("  + ").append(v).append("\n");
			}
			failure.append("\nFix: wrap the offending call in ")
				.append("`Microbot.getClientThread().runOnClientThreadOptional(() -> ...)` or ")
				.append("`Microbot.getClientThread().invoke(() -> ...)`.\n")
				.append("Alternatively, if the violation is intentional and already covered elsewhere, ")
				.append("regenerate the baseline with `-D")
				.append(REGENERATE_BASELINE_PROPERTY)
				.append("=true` and commit the diff.\n\n");
		}
		if (!obsoleteBaseline.isEmpty())
		{
			failure.append(obsoleteBaseline.size())
				.append(" baseline entry/entries no longer match a real violation. ")
				.append("Regenerate the baseline with `-D")
				.append(REGENERATE_BASELINE_PROPERTY)
				.append("=true` and commit the diff:\n\n");
			for (String v : obsoleteBaseline)
			{
				failure.append("  - ").append(v).append("\n");
			}
		}

		fail(failure.toString());
	}

	private static Set<String> collectInferredListMethods(Map<String, MethodFinding> findingsByKey)
	{
		Set<String> inferred = new TreeSet<>();
		for (MethodFinding f : findingsByKey.values())
		{
			if (f.categories.contains(Category.REQUIRES_CLIENT_THREAD)
				|| f.categories.contains(Category.EVENT_HANDLER)
				|| f.categories.contains(Category.CONFIRMED_LAMBDA))
			{
				inferred.addAll(f.rlApiInvocations);
			}
		}
		// Method-reference case: a CONFIRMED_LAMBDA whose own owner is in net/runelite/api/
		// IS the inferred API method (e.g. `clientThread.invoke(actor::getName)`).
		for (MethodFinding f : findingsByKey.values())
		{
			if (f.categories.contains(Category.CONFIRMED_LAMBDA)
				&& f.className.startsWith(RUNELITE_API_PREFIX))
			{
				inferred.add(f.className + "#" + f.methodName + prettyDescriptor(f.methodDesc));
			}
		}
		return inferred;
	}

	private static Set<String> collectViolations(
		Map<String, MethodFinding> findingsByKey,
		Set<String> inferredListMethods)
	{
		Set<String> violations = new TreeSet<>();
		for (MethodFinding f : findingsByKey.values())
		{
			if (!isGuardedClass(f.className))
			{
				continue;
			}
			// Lambda bodies that are reached only via clientThread.invoke* are safe by construction.
			if (f.categories.contains(Category.CONFIRMED_LAMBDA))
			{
				continue;
			}
			for (String inv : f.rlApiInvocations)
			{
				if (inferredListMethods.contains(inv) && !isThreadSafeApiMethod(inv))
				{
					violations.add(f.fullyQualified() + "  ->  " + apiInvocationToReadable(inv));
				}
			}
		}
		return violations;
	}

	/**
	 * Returns {@code true} when the given inferred-list API method is known to
	 * be thread-safe and should not generate a violation.
	 *
	 * <p>The key format is {@code "net/runelite/api/coords/WorldPoint#distanceTo(WorldPoint): int"}.
	 *
	 * @see #THREAD_SAFE_FULL_CLASSES
	 * @see #COORDS_PACKAGE_PREFIX
	 * @see #LIVE_GAME_PARAM_PATTERN
	 */
	private static boolean isThreadSafeApiMethod(String inv)
	{
		int hash = inv.indexOf('#');
		if (hash < 0)
		{
			return false;
		}
		String owner = inv.substring(0, hash);

		if (THREAD_SAFE_FULL_CLASSES.contains(owner))
		{
			return true;
		}

		if (owner.startsWith(COORDS_PACKAGE_PREFIX))
		{
			String signature = inv.substring(hash + 1);
			return !LIVE_GAME_PARAM_PATTERN.matcher(signature).find();
		}

		return false;
	}

	private static String apiInvocationToReadable(String inv)
	{
		int hash = inv.indexOf('#');
		if (hash < 0)
		{
			return inv;
		}
		return inv.substring(0, hash).replace('/', '.') + "#" + inv.substring(hash + 1);
	}

	private static boolean isGuardedClass(String internalName)
	{
		if (internalName.startsWith(QUERY_API_PACKAGE_PREFIX))
		{
			return true;
		}
		if (internalName.startsWith(UTIL_PACKAGE_PREFIX))
		{
			int lastSlash = internalName.lastIndexOf('/');
			String simpleName = internalName.substring(lastSlash + 1);
			int dollar = simpleName.indexOf('$');
			String topName = dollar < 0 ? simpleName : simpleName.substring(0, dollar);
			return topName.startsWith("Rs2");
		}
		return false;
	}

	private static Set<String> loadBaseline() throws IOException
	{
		Set<String> baseline = new TreeSet<>();
		try (InputStream in = ClientThreadGuardrailTest.class.getResourceAsStream(BASELINE_RESOURCE))
		{
			if (in == null)
			{
				return baseline;
			}
			String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			for (String line : content.split("\n"))
			{
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#"))
				{
					continue;
				}
				baseline.add(trimmed);
			}
		}
		return baseline;
	}

	private static void writeBaseline(Path baselinePath, Set<String> violations) throws IOException
	{
		Files.createDirectories(baselinePath.getParent());
		StringBuilder sb = new StringBuilder();
		sb.append("# Baseline of pre-existing client-thread guardrail violations.\n");
		sb.append("# Maintained by ").append(ClientThreadGuardrailTest.class.getName()).append(".\n");
		sb.append("# Format: <fully-qualified caller>  ->  <api owner>#<member>\n");
		sb.append("# Regenerate with: ./gradlew :client:runUnitTests -D")
			.append(REGENERATE_BASELINE_PROPERTY).append("=true\n");
		sb.append("# Lines starting with '#' are ignored.\n");
		sb.append("\n");
		for (String v : violations)
		{
			sb.append(v).append("\n");
		}
		Files.writeString(baselinePath, sb.toString());
	}

	// ---------------------------------------------------------------------
	// Bytecode analysis helpers — mirror of ClientThreadScannerTest's logic.
	// Kept self-contained so the guardrail does not couple to the scanner's
	// public surface and can evolve independently.
	// ---------------------------------------------------------------------

	private static int traverseLambdaReachability(Map<String, MethodFinding> findingsByKey)
	{
		ArrayDeque<String> queue = new ArrayDeque<>();
		Set<String> visited = new HashSet<>();

		for (MethodFinding f : findingsByKey.values())
		{
			if (f.categories.contains(Category.SELF_MARSHALLING))
			{
				queue.addAll(f.lambdaHandlesPassedToInvoke);
			}
		}

		while (!queue.isEmpty())
		{
			String key = queue.poll();
			if (!visited.add(key))
			{
				continue;
			}
			MethodFinding lambdaBody = findingsByKey.get(key);
			if (lambdaBody == null)
			{
				continue;
			}
			lambdaBody.categories.add(Category.CONFIRMED_LAMBDA);
			queue.addAll(lambdaBody.lambdaHandlesPassedToInvoke);
		}

		return visited.size();
	}

	private static MethodFinding analyzeMethod(ClassNode cn, MethodNode mn)
	{
		MethodFinding finding = new MethodFinding(cn.name, mn.name, mn.desc);

		if (mn.visibleAnnotations != null)
		{
			for (AnnotationNode an : mn.visibleAnnotations)
			{
				if (SUBSCRIBE_DESC.equals(an.desc))
				{
					finding.categories.add(Category.EVENT_HANDLER);
				}
			}
		}

		if (mn.instructions == null || mn.instructions.size() == 0)
		{
			return finding;
		}

		boolean callsIsClientThread = false;
		boolean newsAssertionError = false;
		boolean callsClientThreadInvoke = false;

		Handle pendingLambdaHandle = null;

		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext())
		{
			if (insn instanceof MethodInsnNode)
			{
				MethodInsnNode m = (MethodInsnNode) insn;
				if (IS_CLIENT_THREAD.equals(m.name) && m.desc.equals("()Z"))
				{
					callsIsClientThread = true;
				}
				if (CLIENT_THREAD_INTERNAL.equals(m.owner)
					&& (m.name.startsWith("invoke") || m.name.startsWith("runOnClientThread")))
				{
					callsClientThreadInvoke = true;
					if (pendingLambdaHandle != null)
					{
						finding.lambdaHandlesPassedToInvoke.add(handleKey(pendingLambdaHandle));
					}
				}
				if (m.owner.startsWith(RUNELITE_API_PREFIX))
				{
					finding.rlApiInvocations.add(m.owner + "#" + m.name + prettyDescriptor(m.desc));
				}
				pendingLambdaHandle = null;
			}
			else if (insn instanceof InvokeDynamicInsnNode)
			{
				InvokeDynamicInsnNode idn = (InvokeDynamicInsnNode) insn;
				if (idn.bsmArgs != null && idn.bsmArgs.length >= 2 && idn.bsmArgs[1] instanceof Handle)
				{
					Handle h = (Handle) idn.bsmArgs[1];
					int tag = h.getTag();
					if (tag == Opcodes.H_INVOKESTATIC
						|| tag == Opcodes.H_INVOKESPECIAL
						|| tag == Opcodes.H_INVOKEVIRTUAL
						|| tag == Opcodes.H_INVOKEINTERFACE
						|| tag == Opcodes.H_NEWINVOKESPECIAL)
					{
						pendingLambdaHandle = h;
					}
				}
			}
			else if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW)
			{
				TypeInsnNode t = (TypeInsnNode) insn;
				if (ASSERTION_ERROR_INTERNAL.equals(t.desc))
				{
					newsAssertionError = true;
				}
			}
		}

		if (callsIsClientThread && newsAssertionError)
		{
			finding.categories.add(Category.REQUIRES_CLIENT_THREAD);
		}
		else if (callsIsClientThread)
		{
			finding.categories.add(Category.CHECKS_THREAD_GUARD);
		}

		if (callsClientThreadInvoke)
		{
			finding.categories.add(Category.SELF_MARSHALLING);
		}

		return finding;
	}

	private static String handleKey(Handle h)
	{
		return h.getOwner() + "." + h.getName() + h.getDesc();
	}

	private static String prettyDescriptor(String desc)
	{
		Type[] args = Type.getArgumentTypes(desc);
		Type ret = Type.getReturnType(desc);
		StringBuilder sb = new StringBuilder("(");
		for (int i = 0; i < args.length; i++)
		{
			if (i > 0)
			{
				sb.append(", ");
			}
			sb.append(simpleTypeName(args[i]));
		}
		sb.append("): ").append(simpleTypeName(ret));
		return sb.toString();
	}

	private static String simpleTypeName(Type t)
	{
		String name = t.getClassName();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(dot + 1);
	}

	private static Path resolveRepoRoot()
	{
		Path cwd = Paths.get("").toAbsolutePath();
		Path candidate = cwd;
		for (int i = 0; i < 6; i++)
		{
			if (Files.isDirectory(candidate.resolve("runelite-api"))
				&& Files.isDirectory(candidate.resolve("runelite-client")))
			{
				return candidate;
			}
			Path parent = candidate.getParent();
			if (parent == null)
			{
				break;
			}
			candidate = parent;
		}
		throw new IllegalStateException("Could not locate repo root from " + cwd);
	}
}
