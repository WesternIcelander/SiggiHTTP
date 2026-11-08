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
	void resetIdleConnectionIsNotLoggedAsError() throws Exception {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(captured, true));
		try {
			Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort());
			send(socket, "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
			// Close with a reset rather than a FIN
			socket.setSoLinger(true, 0);
			socket.close();
			Thread.sleep(500);
		} finally {
			System.setErr(originalErr);
		}
		assertFalse(captured.toString().contains("error while handling"), captured.toString());
	}
}
