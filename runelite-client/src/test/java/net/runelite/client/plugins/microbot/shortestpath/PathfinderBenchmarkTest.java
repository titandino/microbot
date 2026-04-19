package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.CollisionMap;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.Pathfinder;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.PathfinderConfig;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.SplitFlagMap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Offline benchmark for the pathfinder. Exercises a fixed corpus of routes and
 * prints nodesChecked / elapsedMs / pathLength / endpoint-distance so that
 * pre/post-optimization numbers can be compared directly.
 *
 * <p>Also asserts loose correctness bounds (path reaches target, path length
 * within a sensible multiple of Chebyshev distance) so that regressions show
 * up as failures, not just degraded numbers.
 */
public class PathfinderBenchmarkTest {

	private static SplitFlagMap collisionMap;
	private static HashMap<WorldPoint, Set<Transport>> allTransports;

	@BeforeClass
	public static void loadFixtures() {
		collisionMap = SplitFlagMap.fromResources();
		assertNotNull("Collision map should load from resources", collisionMap);
		allTransports = Transport.loadAllFromResources();
		assertNotNull("Transports should load", allTransports);
	}

	private static final int WARMUP_RUNS = 1;
	private static final int MEASURED_RUNS = 3;

	private static final class Route {
		final String name;
		final WorldPoint src;
		final WorldPoint dst;
		final boolean useTransports;
		final int maxEndpointDistance;

		Route(String name, WorldPoint src, WorldPoint dst, boolean useTransports, int maxEndpointDistance) {
			this.name = name;
			this.src = src;
			this.dst = dst;
			this.useTransports = useTransports;
			this.maxEndpointDistance = maxEndpointDistance;
		}
	}

	private static final List<Route> CORPUS = Arrays.asList(
		new Route("lumbridge-short",      new WorldPoint(3222, 3218, 0), new WorldPoint(3232, 3218, 0), false, 0),
		new Route("lumbridge-medium",     new WorldPoint(3222, 3218, 0), new WorldPoint(3260, 3230, 0), false, 0),
		new Route("lumbridge-to-ge",      new WorldPoint(3222, 3218, 0), new WorldPoint(3164, 3485, 0), false, 0),
		new Route("draynor-to-falador",   new WorldPoint(3093, 3245, 0), new WorldPoint(2966, 3378, 0), false, 0),
		new Route("wilderness-short",     new WorldPoint(3094, 3500, 0), new WorldPoint(3094, 3550, 0), true,  10),
		new Route("ardougne-to-yanille",  new WorldPoint(2662, 3305, 0), new WorldPoint(2605, 3092, 0), false, 0),
		new Route("varrock-to-lumby",     new WorldPoint(3210, 3424, 0), new WorldPoint(3222, 3218, 0), false, 0),
		new Route("catherby-to-camelot",  new WorldPoint(2811, 3437, 0), new WorldPoint(2757, 3479, 0), false, 0),
		new Route("karamja-to-ios-entry", new WorldPoint(2852, 3078, 0), new WorldPoint(2167, 9308, 0), true,  5),
		new Route("karamja-to-bluedrags", new WorldPoint(2852, 3078, 0), new WorldPoint(2126, 9303, 0), true,  30)
	);

	private static final class Result {
		final String name;
		final long bestElapsedMs;
		final int bestNodesChecked;
		final int pathLength;
		final int smoothedLength;
		final int endpointDistance;
		final int chebyshevDistance;

		Result(String name, long bestElapsedMs, int bestNodesChecked, int pathLength, int smoothedLength, int endpointDistance, int chebyshev) {
			this.name = name;
			this.bestElapsedMs = bestElapsedMs;
			this.bestNodesChecked = bestNodesChecked;
			this.pathLength = pathLength;
			this.smoothedLength = smoothedLength;
			this.endpointDistance = endpointDistance;
			this.chebyshevDistance = chebyshev;
		}
	}

	@Test
	public void benchmarkCorpus() {
		List<Result> results = new ArrayList<>();
		for (Route r : CORPUS) {
			results.add(runRoute(r));
		}
		printResults(results);
		for (Result res : results) {
			assertTrue(res.name + ": endpoint too far from target (dist=" + res.endpointDistance + ")",
				res.endpointDistance <= findRoute(res.name).maxEndpointDistance);
			assertTrue(res.name + ": path unexpectedly empty", res.pathLength > 0);
			assertTrue(res.name + ": path length " + res.pathLength +
					" is more than 2.5x Chebyshev " + res.chebyshevDistance +
					" (possible routing regression)",
				res.chebyshevDistance == 0 || res.pathLength <= res.chebyshevDistance * 2.5 + 20);
		}
	}

	private Route findRoute(String name) {
		for (Route r : CORPUS) {
			if (r.name.equals(name)) {
				return r;
			}
		}
		throw new AssertionError("unknown route: " + name);
	}

	private Result runRoute(Route route) {
		for (int i = 0; i < WARMUP_RUNS; i++) {
			runOne(route);
		}
		long bestElapsed = Long.MAX_VALUE;
		int bestNodes = Integer.MAX_VALUE;
		int pathLength = 0;
		int smoothedLength = 0;
		int endpointDistance = 0;
		for (int i = 0; i < MEASURED_RUNS; i++) {
			RunStats s = runOne(route);
			if (s.elapsedNanos < bestElapsed) {
				bestElapsed = s.elapsedNanos;
			}
			if (s.nodesChecked < bestNodes) {
				bestNodes = s.nodesChecked;
			}
			pathLength = s.pathLength;
			smoothedLength = s.smoothedLength;
			endpointDistance = s.endpointDistance;
		}
		int chebyshev = chebyshev(route.src, route.dst);
		return new Result(route.name, bestElapsed / 1_000_000, bestNodes, pathLength, smoothedLength, endpointDistance, chebyshev);
	}

	private static final class RunStats {
		final long elapsedNanos;
		final int nodesChecked;
		final int pathLength;
		final int smoothedLength;
		final int endpointDistance;

		RunStats(long elapsedNanos, int nodesChecked, int pathLength, int smoothedLength, int endpointDistance) {
			this.elapsedNanos = elapsedNanos;
			this.nodesChecked = nodesChecked;
			this.pathLength = pathLength;
			this.smoothedLength = smoothedLength;
			this.endpointDistance = endpointDistance;
		}
	}

	private RunStats runOne(Route route) {
		PathfinderConfig config = route.useTransports
			? buildConfigWithTransports()
			: buildMinimalConfig();
		Pathfinder pf = new Pathfinder(config, route.src, route.dst);
		pf.run();
		assertTrue(route.name + ": pathfinder did not complete", pf.isDone());
		Pathfinder.PathfinderStats stats = pf.getStats();
		assertNotNull(route.name + ": stats unavailable", stats);
		List<WorldPoint> path = pf.getPath();
		assertNotNull(path);
		assertFalse(route.name + ": path was empty", path.isEmpty());
		List<WorldPoint> smoothed = pf.getWalkablePath();
		assertNotNull(smoothed);
		assertFalse(route.name + ": smoothed path was empty", smoothed.isEmpty());
		assertTrue(route.name + ": smoothed length " + smoothed.size() +
				" exceeds raw length " + path.size(),
			smoothed.size() <= path.size());
		assertEquals(route.name + ": smoothed path start mismatch",
			path.get(0), smoothed.get(0));
		assertEquals(route.name + ": smoothed path end mismatch",
			path.get(path.size() - 1), smoothed.get(smoothed.size() - 1));
		WorldPoint endpoint = path.get(path.size() - 1);
		int endDist = chebyshev(endpoint, route.dst);
		return new RunStats(
			stats.getElapsedTimeNanos(),
			stats.getTotalNodesChecked(),
			path.size(),
			smoothed.size(),
			endDist);
	}

	private static int chebyshev(WorldPoint a, WorldPoint b) {
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}

	private PathfinderConfig buildMinimalConfig() {
		PathfinderConfig config = new PathfinderConfig(
			collisionMap,
			new HashMap<>(),
			Collections.emptyList(),
			null,
			null);
		setCalculationCutoff(config, 10_000);
		return config;
	}

	private PathfinderConfig buildConfigWithTransports() {
		PathfinderConfig config = new PathfinderConfig(
			collisionMap,
			allTransports,
			Collections.emptyList(),
			null,
			null);
		setCalculationCutoff(config, 10_000);
		for (Map.Entry<WorldPoint, Set<Transport>> entry : allTransports.entrySet()) {
			if (entry.getKey() == null) {
				continue;
			}
			config.getTransports().put(entry.getKey(), entry.getValue());
			config.getTransportsPacked().put(
				WorldPointUtil.packWorldPoint(entry.getKey()), entry.getValue());
		}
		return config;
	}

	private static void setCalculationCutoff(PathfinderConfig config, long cutoffMs) {
		try {
			Field f = PathfinderConfig.class.getDeclaredField("calculationCutoffMillis");
			f.setAccessible(true);
			f.setLong(config, cutoffMs);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set calculationCutoffMillis", e);
		}
	}

	private static void printResults(List<Result> results) {
		System.out.println();
		System.out.println("=== Pathfinder Benchmark ===");
		System.out.printf("%-24s %10s %12s %10s %10s %10s %10s %12s%n",
			"route", "bestMs", "bestNodes", "pathLen", "smoothLen", "reduction", "endDist", "chebyshev");
		System.out.println("-----------------------------------------------------------------------------------------------------");
		long totalMs = 0;
		long totalNodes = 0;
		long totalRaw = 0;
		long totalSmooth = 0;
		for (Result r : results) {
			double reduction = r.pathLength > 0 ? 100.0 * (r.pathLength - r.smoothedLength) / r.pathLength : 0.0;
			System.out.printf("%-24s %10d %12d %10d %10d %9.1f%% %10d %12d%n",
				r.name, r.bestElapsedMs, r.bestNodesChecked, r.pathLength,
				r.smoothedLength, reduction, r.endpointDistance, r.chebyshevDistance);
			totalMs += r.bestElapsedMs;
			totalNodes += r.bestNodesChecked;
			totalRaw += r.pathLength;
			totalSmooth += r.smoothedLength;
		}
		System.out.println("-----------------------------------------------------------------------------------------------------");
		double overall = totalRaw > 0 ? 100.0 * (totalRaw - totalSmooth) / totalRaw : 0.0;
		System.out.printf("%-24s %10d %12d %10d %10d %9.1f%%%n", "TOTAL", totalMs, totalNodes, totalRaw, totalSmooth, overall);
		System.out.println();
	}
}
