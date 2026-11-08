package io.siggi.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether connections are kept open or closed after a response, on a real
 * loopback server.
 */
class ConnectionTest {

	private ServerSocket serverSocket;

	@BeforeEach
	void setUp() throws IOException {
		HTTPServer server = new HTTPServerBuilder().build();
		server.getResponderRegistry(null).register("/", request -> request.response.send("hello"));
		server.getResponderRegistry(null).register("/stream", request -> {
			// An endless chunked stream that stops when the client goes away
			HTTPResponse response = request.response;
			response.contentLength(-1);
			response.setBuffered(false);
			try {
				while (true) {
					response.write(new byte[1316]);
					response.flush();
					Thread.sleep(10L);
				}
			} catch (IOException | InterruptedException e) {
			}
		});
		server.getResponderRegistry(null).register("/close-delimited", request -> {
			request.response.closeDelimited();
			request.response.write("raw body".getBytes(StandardCharsets.UTF_8));
		});
		server.getResponderRegistry(null).register("/close-delimited-async", request -> {
			HTTPResponse response = request.openResponse();
			response.closeDelimited();
			Thread writer = new Thread(() -> {
				try {
					Thread.sleep(100L);
					response.write("from another thread".getBytes(StandardCharsets.UTF_8));
					response.close();
				} catch (Exception e) {
				}
			});
			writer.start();
		});
		server.getResponderRegistry(null).register("/refuse", request -> {
			// Answers without reading the request body, like an auth check
			request.response.setHeader("401 Unauthorized");
			request.response.send("not authenticated");
		});
		server.getResponderRegistry(null).register("/error", request -> {
			throw new IOException("the application's own error");
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
		String connection;
		boolean serverClosed;
	}

	/**
	 * Sends a request, reads the response by its Content-Length, then checks
	 * whether the server closes the connection.
	 */
	private Response send(Socket socket, String request) throws IOException {
		socket.setSoTimeout(5000);
		socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
		DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
		StringBuilder head = new StringBuilder();
		while (head.indexOf("\r\n\r\n") == -1) {
			int c = in.read();
			if (c == -1) {
				throw new IOException("Connection closed in the response headers");
			}
			head.append((char) c);
		}
		Response response = new Response();
		int contentLength = 0;
		for (String line : head.toString().split("\r\n")) {
			if (response.statusLine == null) {
				response.statusLine = line;
			} else if (line.regionMatches(true, 0, "Connection:", 0, 11)) {
				response.connection = line.substring(11).trim();
			} else if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
				contentLength = Integer.parseInt(line.substring(15).trim());
			}
		}
		in.readFully(new byte[contentLength]);
		socket.setSoTimeout(1000);
		try {
			response.serverClosed = in.read() == -1;
		} catch (SocketTimeoutException e) {
			response.serverClosed = false;
		} catch (IOException e) {
			// Reset, because the server closed with request bytes still unread
			response.serverClosed = true;
		}
		return response;
	}

	private Response send(String request) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			return send(socket, request);
		}
	}

	private void assertClosed(String request) throws IOException {
		Response response = send(request);
		assertEquals("HTTP/1.1 200 OK", response.statusLine);
		assertEquals("close", response.connection);
		assertTrue(response.serverClosed, "server should close the connection");
	}

	private void assertKeptOpen(String request) throws IOException {
		Response response = send(request);
		assertEquals("HTTP/1.1 200 OK", response.statusLine);
		assertEquals("Keep-Alive", response.connection);
		assertFalse(response.serverClosed, "server should keep the connection open");
	}

	@Test
	void http11KeepsConnectionOpen() throws IOException {
		assertKeptOpen("GET / HTTP/1.1\r\nHost: x\r\n\r\n");
	}

	@Test
	void http11ConnectionClose() throws IOException {
		assertClosed("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
		assertClosed("GET / HTTP/1.1\r\nHost: x\r\nConnection: Close\r\n\r\n");
		assertClosed("GET / HTTP/1.1\r\nHost: x\r\nConnection: TE, close\r\n\r\n");
	}

	@Test
	void http10ClosesUnlessKeepAliveAsked() throws IOException {
		assertClosed("GET / HTTP/1.0\r\n\r\n");
		assertKeptOpen("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
	}

	@Test
	void unreadSmallBodyIsDrainedAndConnectionReused() throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			String body = "{\"currentPassword\":\"x\",\"newPassword\":\"y\"}";
			Response refused = send(socket, "POST /refuse HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: "
					+ body.length() + "\r\n\r\n" + body);
			assertEquals("HTTP/1.1 401 Unauthorized", refused.statusLine);
			assertEquals("Keep-Alive", refused.connection);
			assertFalse(refused.serverClosed, "server should keep the connection open");
			Response next = send(socket, "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
			assertEquals("HTTP/1.1 200 OK", next.statusLine);
		}
	}

	@Test
	void unreadLargeBodyIsAnnouncedAsClose() throws IOException {
		// Only part of the declared body is sent; the server mustn't wait for the rest
		Response response = send("POST /refuse HTTP/1.1\r\nHost: x\r\nContent-Length: 1000000\r\n\r\nabc");
		assertEquals("HTTP/1.1 401 Unauthorized", response.statusLine);
		assertEquals("close", response.connection);
		assertTrue(response.serverClosed, "server should close the connection");
	}

	@Test
	void unreadChunkedBodyIsAnnouncedAsClose() throws IOException {
		Response response = send("POST /refuse HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n");
		assertEquals("HTTP/1.1 401 Unauthorized", response.statusLine);
		assertEquals("close", response.connection);
		assertTrue(response.serverClosed, "server should close the connection");
	}

	/**
	 * Sends a request and reads until the server closes the connection.
	 */
	private String sendAndReadUntilClosed(String request) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int amount;
			while ((amount = socket.getInputStream().read(buffer)) != -1) {
				received.write(buffer, 0, amount);
			}
			return new String(received.toByteArray(), StandardCharsets.ISO_8859_1);
		}
	}

	private static void assertCloseDelimited(String response, String expectedBody) {
		int split = response.indexOf("\r\n\r\n");
		String head = response.substring(0, split).toLowerCase(Locale.ROOT);
		assertTrue(head.startsWith("http/1.1 200 ok"), head);
		assertTrue(head.contains("\r\nconnection: close"), head);
		assertFalse(head.contains("\r\ncontent-length:"), head);
		assertFalse(head.contains("\r\ntransfer-encoding:"), head);
		assertEquals(expectedBody, response.substring(split + 4));
	}

	@Test
	void closeDelimitedBody() throws IOException {
		assertCloseDelimited(sendAndReadUntilClosed("GET /close-delimited HTTP/1.1\r\nHost: x\r\n\r\n"), "raw body");
	}

	@Test
	void closeDelimitedBodyEndedFromAnotherThread() throws IOException {
		assertCloseDelimited(sendAndReadUntilClosed("GET /close-delimited-async HTTP/1.1\r\nHost: x\r\n\r\n"), "from another thread");
	}

	private interface ClientAction {
		void run() throws Exception;
	}

	/**
	 * Runs the client action, waits for the server to react, and returns what
	 * the server wrote to System.err meanwhile.
	 */
	private static String captureErrors(ClientAction action) throws Exception {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(captured, true));
		try {
			action.run();
			Thread.sleep(500L);
		} finally {
			System.setErr(originalErr);
		}
		return captured.toString();
	}

	@Test
	void resetIdleConnectionIsNotLoggedAsError() throws Exception {
		String errors = captureErrors(() -> {
			Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort());
			send(socket, "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
			// Close with a reset rather than a FIN
			socket.setSoLinger(true, 0);
			socket.close();
		});
		assertFalse(errors.contains("error while handling"), errors);
	}

	@Test
	void clientLeavingDuringBodyIsNotLoggedAsError() throws Exception {
		String errors = captureErrors(() -> {
			try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				socket.getInputStream().read(new byte[8192]);
			}
		});
		assertFalse(errors.contains("error while handling"), errors);
	}

	@Test
	void applicationErrorIsStillLogged() throws Exception {
		String errors = captureErrors(() -> {
			Response response = send("GET /error HTTP/1.1\r\nHost: x\r\n\r\n");
			assertTrue(response.statusLine.startsWith("HTTP/1.1 500"), response.statusLine);
		});
		assertTrue(errors.contains("the application's own error"), errors);
	}
}
