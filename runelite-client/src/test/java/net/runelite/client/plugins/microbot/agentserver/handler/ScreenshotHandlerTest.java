package net.runelite.client.plugins.microbot.agentserver.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;
import net.runelite.api.Client;
import net.runelite.client.ui.DrawManager;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ScreenshotHandlerTest {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

	private static HttpServer server;
	private static ExecutorService serverExecutor;
	private static int port;
	private static Client mockClient;
	private static Path tempDir;

	private static BufferedImage createTestImage() {
		BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
		Graphics g = img.getGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 100, 100);
		g.dispose();
		return img;
	}

	/** DrawManager that fulfills each requested frame listener immediately on-demand. */
	private static class ImmediateDrawManager extends DrawManager {
		private final BufferedImage frame;

		ImmediateDrawManager(BufferedImage frame) {
			this.frame = frame;
		}

		@Override
		public void requestNextFrameListener(Consumer<Image> listener) {
			super.requestNextFrameListener(listener);
			processDrawComplete(() -> frame);
		}
	}

	@BeforeClass
	public static void startServer() throws IOException {
		mockClient = mock(Client.class);
		DrawManager drawManager = new ImmediateDrawManager(createTestImage());

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		serverExecutor = Executors.newFixedThreadPool(2);
		server.setExecutor(serverExecutor);

		ScreenshotHandler handler = new ScreenshotHandler(GSON, mockClient, drawManager);
		server.createContext(handler.getPath(), handler);
		server.start();

		port = server.getAddress().getPort();
		tempDir = Files.createTempDirectory("screenshot-test");
	}

	@AfterClass
	public static void stopServer() {
		if (server != null) {
			server.stop(0);
		}
		if (serverExecutor != null) {
			serverExecutor.shutdownNow();
		}
		if (tempDir != null) {
			File dir = tempDir.toFile();
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					f.delete();
				}
			}
			dir.delete();
		}
	}

	@Test
	public void testGetScreenshotReturnsPng() throws IOException {
		HttpURLConnection conn = openConnection("/screenshot");
		conn.setRequestMethod("GET");
		assertEquals(200, conn.getResponseCode());
		assertEquals("image/png", conn.getHeaderField("Content-Type"));

		byte[] bytes = conn.getInputStream().readAllBytes();
		assertTrue("Response should be non-empty PNG data", bytes.length > 0);

		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		assertNotNull("Response should be valid PNG image", image);
		assertEquals(100, image.getWidth());
		assertEquals(100, image.getHeight());
	}

	@Test
	public void testGetScreenshotSaveToDisk() throws IOException {
		String dir = tempDir.toAbsolutePath().toString();
		Response resp = get("/screenshot?save=true&label=testshot&dir=" + dir);

		assertEquals(200, resp.code);
		assertEquals(true, resp.body.get("success"));
		assertNotNull(resp.body.get("path"));
		assertEquals(100.0, resp.body.get("width"));
		assertEquals(100.0, resp.body.get("height"));

		String path = (String) resp.body.get("path");
		File savedFile = new File(path);
		assertTrue("Screenshot file should exist on disk", savedFile.exists());
		assertTrue("File name should contain label", savedFile.getName().startsWith("testshot-"));
		assertTrue("File should be PNG", savedFile.getName().endsWith(".png"));

		BufferedImage saved = ImageIO.read(savedFile);
		assertNotNull(saved);
		assertEquals(100, saved.getWidth());
		assertEquals(100, saved.getHeight());
	}

	@Test
	public void testGetScreenshotSaveDefaultLabel() throws IOException {
		String dir = tempDir.toAbsolutePath().toString();
		Response resp = get("/screenshot?save=true&dir=" + dir);

		assertEquals(200, resp.code);
		String path = (String) resp.body.get("path");
		assertTrue("Default label should be 'screenshot'", new File(path).getName().startsWith("screenshot-"));
	}

	@Test
	public void testPostReturns405() throws IOException {
		HttpURLConnection conn = openConnection("/screenshot");
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);
		try (OutputStream os = conn.getOutputStream()) {
			os.write("{}".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals(405, conn.getResponseCode());
	}

	@Test
	public void testRejectsCrossOriginRequests() throws IOException, InterruptedException {
		java.net.http.HttpClient hc = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create("http://127.0.0.1:" + port + "/screenshot"))
				.header("Origin", "https://evil.example.com")
				.GET()
				.build();
		java.net.http.HttpResponse<String> resp = hc.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
		assertEquals(403, resp.statusCode());
		assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
	}

	@Test
	public void testNoCorsWildcardOnGet() throws IOException {
		HttpURLConnection conn = openConnection("/screenshot");
		conn.setRequestMethod("GET");
		conn.getResponseCode();
		assertNull(conn.getHeaderField("Access-Control-Allow-Origin"));
	}

	@Test
	public void testNoFrameAvailableReturns500() throws IOException {
		DrawManager emptyDrawManager = new DrawManager();

		HttpServer emptyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		ExecutorService emptyExecutor = Executors.newFixedThreadPool(1);
		emptyServer.setExecutor(emptyExecutor);
		ScreenshotHandler handler = new ScreenshotHandler(GSON, mockClient, emptyDrawManager);
		emptyServer.createContext(handler.getPath(), handler);
		emptyServer.start();
		int emptyPort = emptyServer.getAddress().getPort();

		try {
			HttpURLConnection conn = (HttpURLConnection)
					new URL("http://127.0.0.1:" + emptyPort + "/screenshot").openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);
			assertEquals(500, conn.getResponseCode());

			String json;
			try (Scanner sc = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8.name())) {
				json = sc.useDelimiter("\\A").hasNext() ? sc.next() : "{}";
			}
			Map<String, Object> body = GSON.fromJson(json, MAP_TYPE);
			assertNotNull(body.get("error"));
			assertTrue(((String) body.get("error")).contains("frame"));
		} finally {
			emptyServer.stop(0);
			emptyExecutor.shutdownNow();
		}
	}

	@Test
	public void testSaveResponseIsJson() throws IOException {
		HttpURLConnection conn = openConnection("/screenshot?save=true&dir=" + tempDir.toAbsolutePath());
		conn.setRequestMethod("GET");
		conn.getResponseCode();
		assertTrue(conn.getHeaderField("Content-Type").contains("application/json"));
	}

	private Response get(String path) throws IOException {
		HttpURLConnection conn = openConnection(path);
		conn.setRequestMethod("GET");
		return readResponse(conn);
	}

	private HttpURLConnection openConnection(String path) throws IOException {
		URL url = new URL("http://127.0.0.1:" + port + path);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(5000);
		return conn;
	}

	private Response readResponse(HttpURLConnection conn) throws IOException {
		int code = conn.getResponseCode();
		InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
		String json;
		try (Scanner sc = new Scanner(stream, StandardCharsets.UTF_8.name())) {
			json = sc.useDelimiter("\\A").hasNext() ? sc.next() : "{}";
		}
		Map<String, Object> body = GSON.fromJson(json, MAP_TYPE);
		return new Response(code, body);
	}

	private static class Response {
		final int code;
		final Map<String, Object> body;

		Response(int code, Map<String, Object> body) {
			this.code = code;
			this.body = body;
		}
	}
}
