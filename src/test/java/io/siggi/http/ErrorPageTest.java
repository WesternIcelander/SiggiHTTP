package io.siggi.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error page handler, on a real loopback server.
 */
class ErrorPageTest {

	private static final IOException APP_ERROR = new IOException("the application's own error");

	private HTTPServer server;
	private ServerSocket serverSocket;

	@BeforeEach
	void setUp() throws IOException {
		server = new HTTPServerBuilder().build();
		server.setRequestURISizeLimit(100);
		server.setRequestHeaderTimeout(300L);
		server.getResponderRegistry(null).register("/error", request -> {
			throw APP_ERROR;
		});
		serverSocket = new ServerSocket(0);
		Thread acceptor = new Thread(() -> {
			while (!serverSocket.isClosed()) {
				try {
					server.handle(serverSocket.accept());
				} catch (IOException e) {
				}
			}
		});
		acceptor.setDaemon(true);
		acceptor.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		serverSocket.close();
	}

	private static final class Response {
		String statusLine;
		String head;
		String body;

		String header(String name) {
			for (String line : head.split("\r\n")) {
				if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
					return line.substring(name.length() + 1).trim();
				}
			}
			return null;
		}
	}

	private Response send(String request) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int amount;
			while ((amount = socket.getInputStream().read(buffer)) != -1) {
				received.write(buffer, 0, amount);
			}
			String text = new String(received.toByteArray(), StandardCharsets.UTF_8);
			int split = text.indexOf("\r\n\r\n");
			Response response = new Response();
			response.head = text.substring(0, split);
			response.statusLine = response.head.split("\r\n")[0];
			response.body = text.substring(split + 4);
			return response;
		}
	}

	private static String longUri() {
		StringBuilder uri = new StringBuilder("/");
		for (int i = 0; i < 200; i++) {
			uri.append('a');
		}
		return uri.toString();
	}

	@Test
	void builtInPagesAreUnchangedWithoutHandler() throws IOException {
		Response response = send("GET nothing HTTP/1.1\r\nHost: x\r\n\r\n");
		assertEquals("HTTP/1.1 400 Bad Request", response.statusLine);
		assertEquals("text/html; charset=utf-8", response.header("Content-Type"));
		assertEquals("close", response.header("Connection"));
		assertTrue(response.body.contains("<h1>400 Bad Request</h1>"), response.body);
		assertEquals(Integer.toString(response.body.getBytes(StandardCharsets.UTF_8).length), response.header("Content-Length"));
	}

	@Test
	void handlerAddsHeadersToEveryBuiltInError() throws IOException {
		server.setErrorPageHandler(page -> {
			page.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
			page.addHeader("X-Error", Integer.toString(page.getStatusCode()));
		});
		String[][] cases = {
			{"GET nothing HTTP/1.1\r\nHost: x\r\n\r\n", "400"},
			{"GET " + longUri() + " HTTP/1.1\r\nHost: x\r\n\r\n", "414"},
			{"GET /error HTTP/1.1\r\nHost: x\r\n\r\n", "500"},
			// An unfinished request line runs into the request header deadline
			{"GET / HTTP/1.1", "408"},
		};
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
		try {
			for (String[] c : cases) {
				Response response = send(c[0]);
				assertTrue(response.statusLine.startsWith("HTTP/1.1 " + c[1] + " "), response.statusLine);
				assertEquals("frame-ancestors 'none'", response.header("Content-Security-Policy"), c[1]);
				assertEquals(c[1], response.header("X-Error"));
				assertEquals("close", response.header("Connection"), c[1]);
			}
		} finally {
			System.setErr(originalErr);
		}
	}

	@Test
	void handlerCanReplaceThePage() throws IOException {
		server.setErrorPageHandler(page -> page.setBody(("{\"error\":" + page.getStatusCode() + "}").getBytes(StandardCharsets.UTF_8), "application/json"));
		Response response = send("GET nothing HTTP/1.1\r\nHost: x\r\n\r\n");
		assertEquals("HTTP/1.1 400 Bad Request", response.statusLine);
		assertEquals("application/json", response.header("Content-Type"));
		assertEquals("13", response.header("Content-Length"));
		assertEquals("{\"error\":400}", response.body);
	}

	@Test
	void timeoutHasEmptyBodyByDefault() throws IOException {
		AtomicReference<ErrorPage> seen = new AtomicReference<>();
		server.setErrorPageHandler(seen::set);
		Response response = send("GET / HTTP/1.1");
		assertEquals("HTTP/1.1 408 Request Timeout", response.statusLine);
		assertEquals("0", response.header("Content-Length"));
		assertNull(response.header("Content-Type"));
		assertEquals("", response.body);
		assertNull(seen.get().getHost());
	}

	@Test
	void handlerSeesHostAndException() throws IOException {
		AtomicReference<ErrorPage> seen = new AtomicReference<>();
		server.setErrorPageHandler(seen::set);
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
		try {
			send("GET /error HTTP/1.1\r\nHost: example.com\r\n\r\n");
		} finally {
			System.setErr(originalErr);
		}
		assertEquals(500, seen.get().getStatusCode());
		assertEquals("Internal Server Error", seen.get().getReasonPhrase());
		assertEquals("example.com", seen.get().getHost());
		assertSame(APP_ERROR, seen.get().getException());
	}

	@Test
	void failingHandlerFallsBackToBuiltInPage() throws IOException {
		server.setErrorPageHandler(page -> {
			page.setHeader("X-Half-Done", "yes");
			throw new IllegalStateException("handler bug");
		});
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(captured, true));
		Response response;
		try {
			response = send("GET nothing HTTP/1.1\r\nHost: x\r\n\r\n");
		} finally {
			System.setErr(originalErr);
		}
		assertEquals("HTTP/1.1 400 Bad Request", response.statusLine);
		assertNull(response.header("X-Half-Done"));
		assertTrue(response.body.contains("<h1>400 Bad Request</h1>"), response.body);
		assertTrue(captured.toString().contains("handler bug"), captured.toString());
	}

	@Test
	void serverControlledHeadersCantBeSet() {
		ErrorPage page = new ErrorPage(400, "Bad Request", null, null, "<p>x</p>");
		for (String name : new String[]{"Content-Length", "transfer-encoding", "Connection", "Keep-Alive", "Content-Type"}) {
			assertThrows(IllegalArgumentException.class, () -> page.setHeader(name, "1"), name);
			assertThrows(IllegalArgumentException.class, () -> page.addHeader(name, "1"), name);
		}
		assertThrows(IllegalArgumentException.class, () -> page.setHeader("X-Test", "a\r\nInjected: yes"));
		assertThrows(IllegalArgumentException.class, () -> page.setBody(new byte[0], "text/plain\r\nInjected: yes"));
		assertFalse(page.getHeaders().containsKey("X-Test"));
	}

	@Test
	void headersAreCaseInsensitive() {
		ErrorPage page = new ErrorPage(400, "Bad Request", null, null, null);
		page.addHeader("X-Test", "a");
		page.addHeader("x-test", "b");
		assertEquals(1, page.getHeaders().size());
		assertEquals(2, page.getHeaders().get("X-Test").size());
		page.setHeader("X-TEST", "c");
		assertEquals(1, page.getHeaders().size());
		assertEquals("c", page.getHeaders().get("X-TEST").get(0));
		page.removeHeader("x-Test");
		assertTrue(page.getHeaders().isEmpty());
		assertEquals(0, page.getBody().length);
		assertNull(page.getContentType());
	}
}
