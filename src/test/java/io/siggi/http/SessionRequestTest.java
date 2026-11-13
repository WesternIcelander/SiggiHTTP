package io.siggi.http;

import io.siggi.http.session.Session;
import io.siggi.http.session.Sessions;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session lookup from the session cookie, on a real loopback server.
 */
class SessionRequestTest {

	private static final String CLIENT_CHOSEN_ID = "abcdef0123";

	private Sessions sessions;
	private ServerSocket serverSocket;

	@BeforeEach
	void setUp() throws IOException {
		sessions = Sessions.create(3600000L);
		HTTPServer server = new HTTPServerBuilder().setSessions(sessions).setSessionCookieName("SessID").build();
		server.getResponderRegistry(null).register("/if-exists", request -> {
			Session session = request.getSessionIfExists();
			request.response.send(session == null ? "none" : session.getSessionId());
		});
		server.getResponderRegistry(null).register("/reset", request -> request.response.send(request.resetSession().getSessionId()));
		server.getResponderRegistry(null).register("/session", request -> request.response.send(request.getSession().getSessionId()));
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
		String setCookie;
		int setCookieCount;
		String body;
	}

	private Response get(String path, String sessionId) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(5000);
			String request = "GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n"
					+ (sessionId == null ? "" : "Cookie: SessID=" + sessionId + "\r\n") + "\r\n";
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
				if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)) {
					response.setCookie = line.substring(11).trim();
					response.setCookieCount++;
				} else if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
					contentLength = Integer.parseInt(line.substring(15).trim());
				}
			}
			byte[] body = new byte[contentLength];
			in.readFully(body);
			response.body = new String(body, StandardCharsets.UTF_8);
			return response;
		}
	}

	@Test
	void getSessionIfExistsIgnoresUnknownId() throws IOException {
		assertEquals("none", get("/if-exists", CLIENT_CHOSEN_ID).body);
		assertFalse(sessions.sessionExists(CLIENT_CHOSEN_ID));
	}

	@Test
	void getSessionIfExistsFindsExistingSession() throws IOException {
		String id = sessions.newSession().getSessionId();
		assertEquals(id, get("/if-exists", id).body);
	}

	@Test
	void getSessionReplacesUnknownIdWithRandomOne() throws IOException {
		Response response = get("/session", CLIENT_CHOSEN_ID);
		assertNotEquals(CLIENT_CHOSEN_ID, response.body);
		assertEquals("SessID=" + response.body + "; Path=/; HttpOnly", response.setCookie);
		assertFalse(sessions.sessionExists(CLIENT_CHOSEN_ID));
	}

	@Test
	void getSessionKeepsExistingSession() throws IOException {
		String id = sessions.newSession().getSessionId();
		assertEquals(id, get("/session", id).body);
	}

	@Test
	void resetSessionWithoutASessionSetsOneCookie() throws IOException {
		Response response = get("/reset", null);
		assertEquals(1, response.setCookieCount);
		assertEquals("SessID=" + response.body + "; Path=/; HttpOnly", response.setCookie);
	}

	@Test
	void resetSessionReplacesTheExistingSession() throws IOException {
		String id = sessions.newSession().getSessionId();
		Response response = get("/reset", id);
		assertEquals(1, response.setCookieCount);
		assertNotEquals(id, response.body);
		assertFalse(sessions.sessionExists(id));
	}
}
