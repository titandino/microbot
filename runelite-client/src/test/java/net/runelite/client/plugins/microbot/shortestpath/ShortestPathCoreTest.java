package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.*;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class ShortestPathCoreTest {

	private static SplitFlagMap collisionMap;

	@BeforeClass
	public static void loadCollisionMap() {
		collisionMap = SplitFlagMap.fromResources();
		assertNotNull("Collision map should load from resources", collisionMap);
		assertNotNull("Region extents should be set", SplitFlagMap.getRegionExtents());
	}

	// ========================
	// PrimitiveIntHashMap Tests
	// ========================

	@Test
	public void testHashMapBasicPutAndGet() {
		PrimitiveIntHashMap<String> map = new PrimitiveIntHashMap<>(16);
		map.put(WorldPointUtil.packWorldPoint(3200, 3200, 0), "lumbridge");
		map.put(WorldPointUtil.packWorldPoint(3222, 3218, 0), "lumbridge_castle");

		assertEquals("lumbridge", map.get(WorldPointUtil.packWorldPoint(3200, 3200, 0)));
		assertEquals("lumbridge_castle", map.get(WorldPointUtil.packWorldPoint(3222, 3218, 0)));
		assertNull(map.get(WorldPointUtil.packWorldPoint(9999, 9999, 0)));
	}

	@Test
	public void testHashMapRehashPreservesAllEntries() {
		PrimitiveIntHashMap<Integer> map = new PrimitiveIntHashMap<>(8, 0.5f);
		int entryCount = 500;

		for (int i = 0; i < entryCount; i++) {
			int packed = WorldPointUtil.packWorldPoint(3000 + (i % 200), 3000 + (i / 200), 0);
			map.put(packed, i);
		}

		int found = 0;
		for (int i = 0; i < entryCount; i++) {
			int packed = WorldPointUtil.packWorldPoint(3000 + (i % 200), 3000 + (i / 200), 0);
			Integer val = map.get(packed);
			if (val != null && val == i) {
				found++;
			}
		}
		assertEquals("All entries must survive rehash", entryCount, found);
	}

	@Test
	public void testHashMapStressRehash() {
		PrimitiveIntHashMap<Set<String>> map = new PrimitiveIntHashMap<>(4, 0.25f);
		int entryCount = 2000;

		for (int i = 0; i < entryCount; i++) {
			int packed = WorldPointUtil.packWorldPoint(2944 + (i % 448), 3525 + (i / 448), 0);
			Set<String> set = new HashSet<>();
			set.add("transport_" + i);
			map.put(packed, set);
		}

		int found = 0;
		for (int i = 0; i < entryCount; i++) {
			int packed = WorldPointUtil.packWorldPoint(2944 + (i % 448), 3525 + (i / 448), 0);
			Set<String> val = map.get(packed);
			if (val != null && val.contains("transport_" + i)) {
				found++;
			}
		}
		assertEquals("All entries must survive multiple rehashes", entryCount, found);
	}

	@Test
	public void testHashMapCollectionValueMerge() {
		PrimitiveIntHashMap<Set<String>> map = new PrimitiveIntHashMap<>(16);
		int packed = WorldPointUtil.packWorldPoint(3200, 3200, 0);

		Set<String> first = new HashSet<>();
		first.add("fairy_ring");
		map.put(packed, first);

		Set<String> second = new HashSet<>();
		second.add("spirit_tree");
		map.put(packed, second);

		Set<String> result = map.get(packed);
		assertNotNull(result);
		assertTrue("Should contain fairy_ring after merge", result.contains("fairy_ring"));
		assertTrue("Should contain spirit_tree after merge", result.contains("spirit_tree"));
	}

	// ========================
	// Wilderness Boundary Tests
	// ========================

	@Test
	public void testWildernessAboveGroundBoundary() {
		int insideWild = WorldPointUtil.packWorldPoint(3100, 3530, 0);
		int outsideWild = WorldPointUtil.packWorldPoint(3100, 3520, 0);
		int deepWild = WorldPointUtil.packWorldPoint(3100, 3900, 0);

		assertTrue("Point at 3100,3530 should be in wilderness",
				PathfinderConfig.isInWilderness(insideWild));
		assertFalse("Point at 3100,3520 should NOT be in wilderness",
				PathfinderConfig.isInWilderness(outsideWild));
		assertTrue("Point at 3100,3900 should be in deep wilderness",
				PathfinderConfig.isInWilderness(deepWild));
	}

	@Test
	public void testWildernessUndergroundBoundary() {
		int insideUnderground = WorldPointUtil.packWorldPoint(3100, 10000, 0);
		int outsideUnderground = WorldPointUtil.packWorldPoint(3100, 9900, 0);
		int wideUnderground = WorldPointUtil.packWorldPoint(3400, 10100, 0);

		assertTrue("Point at 3100,10000 should be in underground wilderness",
				PathfinderConfig.isInWilderness(insideUnderground));
		assertFalse("Point at 3100,9900 should NOT be in underground wilderness",
				PathfinderConfig.isInWilderness(outsideUnderground));
		assertTrue("Point at 3400,10100 should be in underground wilderness (wide area)",
				PathfinderConfig.isInWilderness(wideUnderground));
	}

	@Test
	public void testWildernessUndergroundWidthCoversUpstream() {
		int farEastUnderground = WorldPointUtil.packWorldPoint(3450, 10100, 0);
		assertTrue("Point at 3450,10100 should be in underground wilderness (upstream width=518)",
				PathfinderConfig.isInWilderness(farEastUnderground));
	}

	@Test
	public void testFeroxEnclaveNotWilderness() {
		int feroxCenter = WorldPointUtil.packWorldPoint(3130, 3630, 0);
		assertFalse("Ferox Enclave center should NOT be wilderness",
				PathfinderConfig.isInWilderness(feroxCenter));
	}

	@Test
	public void testWildernessAboveGroundStartsAtCorrectY() {
		int atY3524 = WorldPointUtil.packWorldPoint(3100, 3524, 0);
		int atY3525 = WorldPointUtil.packWorldPoint(3100, 3525, 0);

		assertFalse("Y=3524 should NOT be wilderness (boundary is Y=3525)",
				PathfinderConfig.isInWilderness(atY3524));
		assertTrue("Y=3525 should be wilderness",
				PathfinderConfig.isInWilderness(atY3525));
	}

	// ========================
	// Collision Map Tests
	// ========================

	@Test
	public void testCollisionMapLoadsRegions() {
		SplitFlagMap.RegionExtent extents = SplitFlagMap.getRegionExtents();
		assertTrue("Region width should be > 0", extents.getWidth() > 0);
		assertTrue("Region height should be > 0", extents.getHeight() > 0);
	}

	@Test
	public void testCollisionMapWalkableTiles() {
		CollisionMap map = new CollisionMap(collisionMap);
		assertTrue("Lumbridge center should allow north movement", map.n(3222, 3218, 0));
		assertTrue("Lumbridge center should allow east movement", map.e(3222, 3218, 0));
	}

	@Test
	public void testCollisionMapBlockedTile() {
		CollisionMap map = new CollisionMap(collisionMap);
		assertTrue("Lumbridge castle wall tile should be blocked", map.isBlocked(3210, 3222, 0));
		assertFalse("Open Lumbridge courtyard tile should not be blocked", map.isBlocked(3222, 3218, 0));
	}

	// ========================
	// Transport Loading Tests
	// ========================

	@Test
	public void testTransportLoadingDoesNotThrow() {
		HashMap<WorldPoint, Set<Transport>> transports = Transport.loadAllFromResources();
		assertNotNull("Transports should load", transports);
		assertTrue("Should load at least 100 transport origins", transports.size() > 100);
	}

	@Test
	public void testNewTransportTypesLoaded() {
		HashMap<WorldPoint, Set<Transport>> transports = Transport.loadAllFromResources();

		boolean hasHotAirBalloon = false;
		boolean hasMagicMushtree = false;
		boolean hasSeasonalTransport = false;

		for (Set<Transport> transportSet : transports.values()) {
			for (Transport t : transportSet) {
				if (t.getType() == TransportType.HOT_AIR_BALLOON) hasHotAirBalloon = true;
				if (t.getType() == TransportType.MAGIC_MUSHTREE) hasMagicMushtree = true;
				if (t.getType() == TransportType.SEASONAL_TRANSPORT) hasSeasonalTransport = true;
			}
		}

		assertTrue("Hot air balloon transports should be loaded", hasHotAirBalloon);
		assertTrue("Magic mushtree transports should be loaded", hasMagicMushtree);
		assertTrue("Seasonal transports should be loaded", hasSeasonalTransport);
	}

	@Test
	public void testFairyRingTransportsExist() {
		HashMap<WorldPoint, Set<Transport>> transports = Transport.loadAllFromResources();
		boolean hasFairyRing = false;
		for (Set<Transport> transportSet : transports.values()) {
			for (Transport t : transportSet) {
				if (t.getType() == TransportType.FAIRY_RING) {
					hasFairyRing = true;
					break;
				}
			}
			if (hasFairyRing) break;
		}
		assertTrue("Fairy ring transports should be loaded", hasFairyRing);
	}

	// ========================
	// WorldPointUtil Tests
	// ========================

	@Test
	public void testPackUnpackRoundTrip() {
		int x = 3222, y = 3218, z = 0;
		int packed = WorldPointUtil.packWorldPoint(x, y, z);
		assertEquals(x, WorldPointUtil.unpackWorldX(packed));
		assertEquals(y, WorldPointUtil.unpackWorldY(packed));
		assertEquals(z, WorldPointUtil.unpackWorldPlane(packed));
	}

	@Test
	public void testPackUnpackHighCoords() {
		int x = 3462, y = 10376, z = 2;
		int packed = WorldPointUtil.packWorldPoint(x, y, z);
		assertEquals(x, WorldPointUtil.unpackWorldX(packed));
		assertEquals(y, WorldPointUtil.unpackWorldY(packed));
		assertEquals(z, WorldPointUtil.unpackWorldPlane(packed));
	}

	@Test
	public void testDistanceBetween() {
		int a = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int b = WorldPointUtil.packWorldPoint(3210, 3200, 0);
		assertEquals(10, WorldPointUtil.distanceBetween(a, b));
	}

	@Test
	public void testDistanceToArea() {
		WorldArea area = new WorldArea(3200, 3200, 10, 10, 0);
		int inside = WorldPointUtil.packWorldPoint(3205, 3205, 0);
		int outside = WorldPointUtil.packWorldPoint(3220, 3205, 0);

		assertEquals("Inside point should have distance 0", 0, WorldPointUtil.distanceToArea2D(inside, area));
		assertTrue("Outside point should have distance > 0", WorldPointUtil.distanceToArea2D(outside, area) > 0);
	}

	// ========================
	// Pathfinder Partial Path Tests
	// ========================

	@Test
	public void testPathfinderGetPathReturnsEmptyWhenNoPath() {
		Pathfinder pf = new Pathfinder(
				createMinimalConfig(),
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(3232, 3218, 0)
		);
		List<WorldPoint> path = pf.getPath();
		assertNotNull("getPath() should return empty list before run, not throw", path);
		assertTrue("Path should be empty before pathfinder runs", path.isEmpty());
	}

	@Test
	public void testPathfinderRunsAndProducesPath() throws Exception {
		PathfinderConfig config = createMinimalConfig();
		assertNotNull("Config map should be available", config.getMap());

		Pathfinder pf = new Pathfinder(
				config,
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(3232, 3218, 0)
		);

		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertNotNull("Path should not be null", path);
		assertFalse("Path should not be empty for a short walkable route", path.isEmpty());
	}

	@Test
	public void testPathfinderLongRoute() {
		Pathfinder pf = new Pathfinder(
				createMinimalConfig(),
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(3164, 3485, 0)
		);

		pf.run();

		assertTrue("Pathfinder should complete for Lumbridge to GE route", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertNotNull(path);
		assertTrue("Path should have many tiles for a long route", path.size() > 50);
	}

	@Test
	public void testPathfinderCancelReturnsPath() throws Exception {
		Pathfinder pf = new Pathfinder(
				createMinimalConfig(),
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(2500, 3500, 0)
		);

		Thread t = new Thread(pf);
		t.start();
		Thread.sleep(200);
		pf.cancel();
		t.join(5000);

		List<WorldPoint> path = pf.getPath();
		assertNotNull("Cancelled pathfinder should return a path (possibly partial)", path);
	}

	@Test
	public void testPathfinderWildernessRoute() {
		Pathfinder pf = new Pathfinder(
				createMinimalConfig(),
				new WorldPoint(3094, 3500, 0),
				new WorldPoint(3094, 3550, 0)
		);

		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertNotNull(path);
		assertFalse("Path into wilderness should not be empty", path.isEmpty());
	}

	// ========================
	// Isle of Souls Dungeon Route Tests
	// ========================

	@Test
	public void testKaramjaToIsleOfSoulsDungeonEntrance() {
		PathfinderConfig config = createConfigWithTransports();
		WorldPoint karamja = new WorldPoint(2852, 3078, 0);
		WorldPoint dungeonEntrance = new WorldPoint(2167, 9308, 0);

		Pathfinder pf = new Pathfinder(config, karamja, dungeonEntrance);
		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertNotNull(path);
		assertFalse("Path should not be empty", path.isEmpty());

		WorldPoint endpoint = path.get(path.size() - 1);
		int distToTarget = Math.max(
				Math.abs(endpoint.getX() - dungeonEntrance.getX()),
				Math.abs(endpoint.getY() - dungeonEntrance.getY()));
		assertTrue("Should reach within 5 tiles of dungeon entrance, got dist=" + distToTarget +
				" at " + endpoint, distToTarget <= 5);
	}

	@Test
	public void testKaramjaToIronDragons() {
		PathfinderConfig config = createConfigWithTransports();
		WorldPoint karamja = new WorldPoint(2852, 3078, 0);
		WorldPoint ironDragons = new WorldPoint(2154, 9294, 0);

		Pathfinder pf = new Pathfinder(config, karamja, ironDragons);
		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertFalse("Path should not be empty", path.isEmpty());

		WorldPoint endpoint = path.get(path.size() - 1);
		int distToTarget = Math.max(
				Math.abs(endpoint.getX() - ironDragons.getX()),
				Math.abs(endpoint.getY() - ironDragons.getY()));
		assertTrue("Should reach within 15 tiles of iron dragons, got dist=" + distToTarget +
				" at " + endpoint, distToTarget <= 15);
	}

	@Test
	public void testKaramjaToBlueDragons() {
		PathfinderConfig config = createConfigWithTransports();
		WorldPoint karamja = new WorldPoint(2852, 3078, 0);
		WorldPoint blueDragons = new WorldPoint(2126, 9303, 0);

		Pathfinder pf = new Pathfinder(config, karamja, blueDragons);
		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertFalse("Path should not be empty", path.isEmpty());

		WorldPoint endpoint = path.get(path.size() - 1);
		int distToTarget = Math.max(
				Math.abs(endpoint.getX() - blueDragons.getX()),
				Math.abs(endpoint.getY() - blueDragons.getY()));
		assertTrue("Should reach within 30 tiles of blue dragons, got dist=" + distToTarget +
				" at " + endpoint, distToTarget <= 30);
	}

	// ========================
	// Pathfinder Performance Tests
	// ========================

	@Test
	public void testShortPathDoesNotFloodEntireMap() {
		PathfinderConfig config = createMinimalConfig();
		WorldPoint src = new WorldPoint(3222, 3218, 0);
		WorldPoint dst = new WorldPoint(3232, 3228, 0);

		Pathfinder pf = new Pathfinder(config, src, dst);
		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		Pathfinder.PathfinderStats stats = pf.getStats();
		assertNotNull(stats);

		assertTrue("Short path (~15 tiles) should check fewer than 50,000 nodes, got " + stats.getTotalNodesChecked(),
				stats.getTotalNodesChecked() < 50_000);
	}

	@Test
	public void testUnreachableTargetCompletesViaCutoff() {
		PathfinderConfig config = createMinimalConfig();
		CollisionMap map = config.getMap();

		int startX = 3222, targetX = startX, targetY = 3218;
		while (!map.isBlocked(targetX, targetY, 0)) {
			targetX++;
			if (targetX > 3300) {
				fail("No blocked tile found scanning x=" + startX + ".." + targetX + " y=" + targetY + " plane=0");
			}
		}

		Pathfinder pf = new Pathfinder(config,
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(targetX, targetY, 0));
		pf.run();

		assertTrue("Pathfinder should complete even for blocked target", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertNotNull("Path should not be null", path);
		assertFalse("Should produce a partial path toward the blocked target", path.isEmpty());
	}

	@Test
	public void testShortPathCompletesUnder500ms() {
		PathfinderConfig config = createMinimalConfig();
		Pathfinder pf = new Pathfinder(config,
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(3260, 3230, 0));

		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		Pathfinder.PathfinderStats stats = pf.getStats();
		assertNotNull(stats);

		long elapsedMs = stats.getElapsedTimeNanos() / 1_000_000;
		assertTrue("Short path should complete under 500ms, took " + elapsedMs + "ms", elapsedMs < 500);
	}

	@Test
	public void testLongPathCompletesUnder3Seconds() {
		PathfinderConfig config = createMinimalConfig();
		Pathfinder pf = new Pathfinder(config,
				new WorldPoint(3222, 3218, 0),
				new WorldPoint(3164, 3485, 0));

		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		Pathfinder.PathfinderStats stats = pf.getStats();
		assertNotNull(stats);

		long elapsedMs = stats.getElapsedTimeNanos() / 1_000_000;
		assertTrue("Long path (Lumbridge to GE) should complete under 3s, took " + elapsedMs + "ms",
				elapsedMs < 3000);
	}

	@Test
	public void testNearbyBlockedTargetResolvesFast() {
		PathfinderConfig config = createMinimalConfig();
		Pathfinder pf = new Pathfinder(config,
				new WorldPoint(1369, 3368, 0),
				new WorldPoint(1415, 3355, 0));

		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		Pathfinder.PathfinderStats stats = pf.getStats();
		assertNotNull(stats);

		long elapsedMs = stats.getElapsedTimeNanos() / 1_000_000;
		assertTrue("Nearby path (~46 tiles) should complete under 500ms, took " + elapsedMs + "ms",
				elapsedMs < 500);
		assertTrue("Nearby path should check under 150,000 nodes, got " + stats.getTotalNodesChecked(),
				stats.getTotalNodesChecked() < 150_000);
	}

	@Test
	public void testIsleOfSoulsDungeonEntranceIsWalkable() {
		CollisionMap map = new CollisionMap(collisionMap);
		assertFalse("IoS dungeon entrance (2167,9308) should be walkable",
				map.isBlocked(2167, 9308, 0));
	}

	@Test
	public void testDungeonPathToKnownReachableTile() {
		PathfinderConfig config = createConfigWithTransports();
		WorldPoint src = new WorldPoint(2167, 9308, 0);
		WorldPoint dst = new WorldPoint(2165, 9294, 0);

		Pathfinder pf = new Pathfinder(config, src, dst);
		pf.run();

		assertTrue("Pathfinder should complete", pf.isDone());
		List<WorldPoint> path = pf.getPath();
		assertFalse("Path to known reachable dungeon tile should not be empty", path.isEmpty());

		WorldPoint endpoint = path.get(path.size() - 1);
		int distToTarget = Math.max(
				Math.abs(endpoint.getX() - dst.getX()),
				Math.abs(endpoint.getY() - dst.getY()));
		assertTrue("Should reach within 2 tiles of reachable dungeon target, got dist=" + distToTarget,
				distToTarget <= 2);
	}

	@Test
	public void testIgnoreCollisionPackedIsHashSetLookup() {
		int packed = WorldPointUtil.packWorldPoint(3142, 3457, 0);
		assertTrue("Known ignore-collision tile should be in the packed set",
				CollisionMap.ignoreCollisionPacked.contains(packed));

		int notIgnored = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		assertFalse("Random tile should not be in ignore set",
				CollisionMap.ignoreCollisionPacked.contains(notIgnored));
	}

	private PathfinderConfig createConfigWithTransports() {
		HashMap<WorldPoint, Set<Transport>> allTransports = Transport.loadAllFromResources();
		PathfinderConfig config = new PathfinderConfig(
				collisionMap,
				allTransports,
				Collections.emptyList(),
				null,
				null
		);
		try {
			java.lang.reflect.Field f = PathfinderConfig.class.getDeclaredField("calculationCutoffMillis");
			f.setAccessible(true);
			f.setLong(config, 10000);

			for (Map.Entry<WorldPoint, Set<Transport>> entry : allTransports.entrySet()) {
				if (entry.getKey() == null) {
					continue;
				}
				config.getTransports().put(entry.getKey(), entry.getValue());
				config.getTransportsPacked().put(
						WorldPointUtil.packWorldPoint(entry.getKey()), entry.getValue());
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to configure transports", e);
		}
		return config;
	}

	// ========================
	// Pathfinder Tiebreaker / Route Diversity Tests
	// ========================

	@Test
	public void testPathfinderTiebreakerProducesDiverseRoutes() {
		// With deterministic A*, the same (start, target) pair always produces the
		// same tile sequence, leaving a fingerprint on bots that shuttle between
		// fixed waypoints. Node.tiebreaker seeds a random secondary priority-queue
		// key so equal-fCost frontiers expand in a different order each run —
		// paths stay optimal by cost but diverge tile-by-tile.
		final WorldPoint start = new WorldPoint(3222, 3218, 0);   // Lumbridge
		final WorldPoint target = new WorldPoint(3164, 3485, 0);  // Grand Exchange
		final int runs = 10;

		List<List<WorldPoint>> paths = new ArrayList<>(runs);
		for (int i = 0; i < runs; i++) {
			Pathfinder pf = new Pathfinder(createMinimalConfig(), start, target);
			pf.run();
			assertTrue("Run " + i + " should complete", pf.isDone());
			List<WorldPoint> path = pf.getPath();
			assertNotNull("Run " + i + " path should not be null", path);
			assertFalse("Run " + i + " path should not be empty", path.isEmpty());
			paths.add(path);
		}

		// Optimality: with no transports configured, path cost == path length
		// (every step is cost 1). All runs should return the same length.
		int referenceLength = paths.get(0).size();
		for (int i = 1; i < runs; i++) {
			assertEquals(
					"Run " + i + " length should match run 0 (optimality preserved)",
					referenceLength, paths.get(i).size());
		}

		// Diversity: at least two of the N runs should produce different tile
		// sequences. Lumbridge → GE has abundant equal-cost alternatives through
		// Varrock squares, so the tiebreaker reliably picks different ones.
		long distinctPaths = paths.stream().distinct().count();
		assertTrue(
				"Expected at least 2 distinct paths over " + runs + " runs, got " + distinctPaths,
				distinctPaths >= 2);
	}

	@Test
	public void testPathfinderShortRouteStillOptimal() {
		// Tiebreaker must not break optimality on short routes where only one
		// shortest tile sequence exists. Two runs should still agree on length.
		final WorldPoint start = new WorldPoint(3222, 3218, 0);
		final WorldPoint target = new WorldPoint(3228, 3218, 0); // 6 tiles east

		Pathfinder a = new Pathfinder(createMinimalConfig(), start, target);
		a.run();
		Pathfinder b = new Pathfinder(createMinimalConfig(), start, target);
		b.run();

		assertEquals("Both runs should have equal path length",
				a.getPath().size(), b.getPath().size());
	}

	private PathfinderConfig createMinimalConfig() {
		PathfinderConfig config = new PathfinderConfig(
				collisionMap,
				new HashMap<>(),
				Collections.emptyList(),
				null,
				null
		);
		try {
			java.lang.reflect.Field f = PathfinderConfig.class.getDeclaredField("calculationCutoffMillis");
			f.setAccessible(true);
			f.setLong(config, 10000);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set calculationCutoffMillis", e);
		}
		return config;
	}
}
