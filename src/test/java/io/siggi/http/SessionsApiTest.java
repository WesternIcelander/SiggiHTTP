package io.siggi.http;

import io.siggi.http.session.SessionHandle;
import io.siggi.http.session.Sessions;
import io.siggi.http.session.SessionsBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request-level Sessions API, on a real loopback server.
 */
class SessionsApiTest {

	private ServerSocket serverSocket;
	private HTTPServer server;
	private Sessions sessions;

	private final CountDownLatch held = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);

	@AfterEach
	void tearDown() throws IOException {
		release.countDown();
		if (serverSocket != null) {
			serverSocket.close();
		}
	}

	@SuppressWarnings("deprecation")
	private void start(Sessions sessions, String legacyCookieName) throws IOException {
		this.sessions = sessions;
		HTTPServerBuilder builder = new HTTPServerBuilder();
		if (legacyCookieName != null) {
			builder.setSessionCookieName(legacyCookieName);
		}
		server = builder.build();
		register("/open", request -> {
			try (SessionHandle session = sessions.openSession(request)) {
				String value = request.get.get("set");
				if (value != null) {
					session.set("value", value);
				}
				request.response.send(session.getSessionId() + " " + session.get("value"));
			}
		});
		register("/read", request -> {
			SessionHandle session = sessions.readSession(request);
			request.response.send(session.exists() + " " + session.getSessionId() + " " + session.get("value"));
		});
		register("/hold", request -> {
			// opens, changes the value halfway, and waits before finishing
			SessionHandle session = sessions.openSession(request);
			session.set("value", "half");
			held.countDown();
			release.await(5, TimeUnit.SECONDS);
			session.set("value", "done");
			session.close();
			request.response.send("held");
		});
		register("/leave-open", request -> {
			sessions.openSession(request).set("value", "left open");
			request.response.send("ok");
		});
		register("/downgrade-and-hold", request -> {
			SessionHandle session = sessions.openSession(request);
			session.set("value", "downgraded");
			session.downgrade();
			String setAfterDowngrade;
			try {
				session.set("value", "x");
				setAfterDowngrade = "allowed";
			} catch (IllegalStateException e) {
				setAfterDowngrade = "refused";
			}
			held.countDown();
			release.await(5, TimeUnit.SECONDS);
			request.response.send(setAfterDowngrade + " " + session.isWritable() + " " + session.get("value"));
		});
		register("/open-twice", request -> {
			sessions.openSession(request);
			try {
				sessions.openSession(request);
				request.response.send("allowed");
			} catch (IllegalStateException e) {
				request.response.send("refused");
			}
		});
		register("/read-while-open", request -> {
			try (SessionHandle session = sessions.openSession(request)) {
				session.set("value", "mine");
				request.response.send(sessions.readSession(request).get("value"));
			}
		});
		register("/open-after-headers", request -> {
			request.response.contentLength(-1);
			request.response.write("started ".getBytes(StandardCharsets.UTF_8));
			try {
				sessions.openSession(request);
				request.response.write("allowed".getBytes(StandardCharsets.UTF_8));
			} catch (IllegalStateException e) {
				request.response.write("refused".getBytes(StandardCharsets.UTF_8));
			}
		});
		register("/logout", request -> {
			sessions.deleteSession(request);
			request.response.send("logged out");
		});
		register("/login", request -> {
			try (SessionHandle session = sessions.resetSession(request)) {
				session.set("user", "siggi");
				request.response.send(session.getSessionId() + " " + session.get("user"));
			}
		});
		register("/login-while-open", request -> {
			SessionHandle old = sessions.openSession(request);
			old.set("value", "old");
			try (SessionHandle session = sessions.resetSession(request)) {
				request.response.send(session.getSessionId() + " " + old.isWritable() + " " + old.get("value") + " " + session.get("value"));
			}
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

	private void register(String path, HTTPResponder responder) {
		server.getResponderRegistry(null).register(path, responder);
	}

	private static final class Response {
		final List<String> setCookies = new ArrayList<>();
		String body;
	}

	private Response get(String path, String cookie) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(10000);
			String request = "GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n"
					+ (cookie == null ? "" : "Cookie: " + cookie + "\r\n") + "\r\n";
			socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int amount;
			while ((amount = socket.getInputStream().read(buffer)) != -1) {
				received.write(buffer, 0, amount);
			}
			String raw = new String(received.toByteArray(), StandardCharsets.UTF_8);
			int end = raw.indexOf("\r\n\r\n");
			Response response = new Response();
			for (String line : raw.substring(0, end).split("\r\n")) {
				if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)) {
					response.setCookies.add(line.substring(11).trim());
				}
			}
			response.body = raw.substring(end + 4);
			if (raw.contains("Transfer-Encoding: chunked")) {
				response.body = dechunk(response.body);
			}
			return response;
		}
	}

	private static String dechunk(String body) {
		StringBuilder out = new StringBuilder();
		int pos = 0;
		while (true) {
			int lineEnd = body.indexOf("\r\n", pos);
			int size = Integer.parseInt(body.substring(pos, lineEnd).trim(), 16);
			if (size == 0) {
				return out.toString();
			}
			out.append(body, lineEnd + 2, lineEnd + 2 + size);
			pos = lineEnd + 2 + size + 2;
		}
	}

	private CompletableFuture<Response> getAsync(String path, String cookie) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return get(path, cookie);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static String idOf(Response response) {
		return response.body.split(" ")[0];
	}

	private static Sessions newSessions() {
		return new SessionsBuilder().build();
	}

	@Test
	void openCreatesASessionWithALaxSessionCookie() throws IOException {
		start(newSessions(), null);
		Response response = get("/open?set=a", null);
		String id = idOf(response);
		assertEquals(64, id.length());
		assertEquals(1, response.setCookies.size());
		assertEquals("SessID=" + id + "; Path=/; HttpOnly; SameSite=Lax", response.setCookies.get(0));
	}

	@Test
	void valuesPersistAndAnExistingSessionSetsNoCookie() throws IOException {
		start(newSessions(), null);
		String id = idOf(get("/open?set=a", null));
		Response opened = get("/open", "SessID=" + id);
		assertEquals(id + " a", opened.body);
		assertTrue(opened.setCookies.isEmpty(), opened.setCookies.toString());
		Response read = get("/read", "SessID=" + id);
		assertEquals("true " + id + " a", read.body);
		assertTrue(read.setCookies.isEmpty(), read.setCookies.toString());
	}

	@Test
	void readWithoutASessionCreatesNothing() throws IOException {
		start(newSessions(), null);
		Response response = get("/read", "SessID=abcdef0123");
		assertEquals("false null null", response.body);
		assertTrue(response.setCookies.isEmpty());
		assertFalse(sessions.sessionExists("abcdef0123"));
	}

	@Test
	void unknownIdIsReplacedNotAdopted() throws IOException {
		start(newSessions(), null);
		Response response = get("/open", "SessID=abcdef0123");
		assertFalse(idOf(response).equals("abcdef0123"));
		assertFalse(sessions.sessionExists("abcdef0123"));
		assertEquals(1, response.setCookies.size());
	}

	@Test
	void readWaitsForAWriterToClose() throws Exception {
		start(newSessions(), null);
		String cookie = "SessID=" + idOf(get("/open?set=before", null));
		CompletableFuture<Response> writer = getAsync("/hold", cookie);
		assertTrue(held.await(5, TimeUnit.SECONDS));
		CompletableFuture<Response> reader = getAsync("/read", cookie);
		Thread.sleep(300L);
		assertFalse(reader.isDone(), "read didn't wait for the writer");
		release.countDown();
		assertEquals("held", writer.get(5, TimeUnit.SECONDS).body);
		assertTrue(reader.get(5, TimeUnit.SECONDS).body.endsWith(" done"));
	}

	@Test
	void aHandleLeftOpenIsClosedWhenTheRequestEnds() throws Exception {
		start(newSessions(), null);
		String cookie = "SessID=" + idOf(get("/open", null));
		get("/leave-open", cookie);
		Response response = getAsync("/open", cookie).get(5, TimeUnit.SECONDS);
		assertTrue(response.body.endsWith(" left open"), response.body);
	}

	@Test
	void downgradeReleasesTheLockAndKeepsACopy() throws Exception {
		start(newSessions(), null);
		String cookie = "SessID=" + idOf(get("/open", null));
		CompletableFuture<Response> downgraded = getAsync("/downgrade-and-hold", cookie);
		assertTrue(held.await(5, TimeUnit.SECONDS));
		// the first request is still running, but no longer holds the lock
		Response other = getAsync("/open?set=other", cookie).get(5, TimeUnit.SECONDS);
		assertTrue(other.body.endsWith(" other"), other.body);
		release.countDown();
		assertEquals("refused false downgraded", downgraded.get(5, TimeUnit.SECONDS).body);
	}

	@Test
	void openingTwiceInOneRequestIsRefused() throws Exception {
		start(newSessions(), null);
		assertEquals("refused", getAsync("/open-twice", null).get(5, TimeUnit.SECONDS).body);
	}

	@Test
	void readWhileThisRequestHasItOpenSeesItsChanges() throws Exception {
		start(newSessions(), null);
		assertEquals("mine", getAsync("/read-while-open", null).get(5, TimeUnit.SECONDS).body);
	}

	@Test
	void aNewSessionAfterTheHeadersWereSentIsRefused() throws IOException {
		start(newSessions(), null);
		Response response = get("/open-after-headers", null);
		assertEquals("started refused", response.body);
	}

	@Test
	void secureRequestsGetASecureCookie() throws IOException {
		start(newSessions(), null);
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.getOutputStream().write("GET /open HTTP/1.1\r\nHost: x\r\nX-Forwarded-Proto: https\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int amount;
			while ((amount = socket.getInputStream().read(buffer)) != -1) {
				received.write(buffer, 0, amount);
			}
			assertTrue(received.toString("UTF-8").contains("; Path=/; HttpOnly; Secure; SameSite=Lax\r\n"));
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void sessionsFromCreateKeepTheOldCookie() throws IOException {
		start(Sessions.create(3600000L), "Legacy");
		Response response = get("/open?set=a", null);
		String id = idOf(response);
		assertEquals("Legacy=" + id + "; path=/; HttpOnly;", response.setCookies.get(0));
		assertNotNull(sessions.get(id));
		assertEquals(id + " a", get("/open", "Legacy=" + id).body);
	}

	@Test
	void deleteSessionDeletesAndClearsTheCookie() throws IOException {
		start(newSessions(), null);
		String id = idOf(get("/open?set=a", null));
		Response response = get("/logout", "SessID=" + id);
		assertEquals("logged out", response.body);
		assertEquals(java.util.Collections.singletonList("SessID=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"), response.setCookies);
		assertFalse(sessions.sessionExists(id));
		assertEquals("false null null", get("/read", "SessID=" + id).body);
	}

	@Test
	void deleteSessionWithoutACookieSetsNoCookie() throws IOException {
		start(newSessions(), null);
		assertTrue(get("/logout", null).setCookies.isEmpty());
	}

	@Test
	void deleteSessionWaitsForAWriter() throws Exception {
		start(newSessions(), null);
		String id = idOf(get("/open", null));
		CompletableFuture<Response> writer = getAsync("/hold", "SessID=" + id);
		assertTrue(held.await(5, TimeUnit.SECONDS));
		CompletableFuture<Response> logout = getAsync("/logout", "SessID=" + id);
		Thread.sleep(300L);
		assertFalse(logout.isDone(), "delete didn't wait for the writer");
		assertTrue(sessions.sessionExists(id));
		release.countDown();
		writer.get(5, TimeUnit.SECONDS);
		logout.get(5, TimeUnit.SECONDS);
		assertFalse(sessions.sessionExists(id));
	}

	@Test
	void resetSessionReplacesTheSessionWithOneCookie() throws IOException {
		start(newSessions(), null);
		String oldId = idOf(get("/open?set=a", null));
		Response response = get("/login", "SessID=" + oldId);
		String newId = idOf(response);
		assertFalse(newId.equals(oldId));
		assertEquals(newId + " siggi", response.body);
		assertEquals(java.util.Collections.singletonList("SessID=" + newId + "; Path=/; HttpOnly; SameSite=Lax"), response.setCookies);
		assertFalse(sessions.sessionExists(oldId));
		assertEquals("true " + newId + " null", get("/read", "SessID=" + newId).body);
	}

	@Test
	void resetSessionEmptiesThisRequestsOldHandle() throws Exception {
		start(newSessions(), null);
		String oldId = idOf(get("/open", null));
		Response response = getAsync("/login-while-open", "SessID=" + oldId).get(5, TimeUnit.SECONDS);
		String[] parts = response.body.split(" ");
		assertFalse(parts[0].equals(oldId));
		assertEquals("false null null", parts[1] + " " + parts[2] + " " + parts[3]);
		assertFalse(sessions.sessionExists(oldId));
	}
}
