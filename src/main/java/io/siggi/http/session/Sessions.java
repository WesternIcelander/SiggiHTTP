package io.siggi.http.session;

import io.siggi.http.HTTPRequest;
import java.io.CharArrayReader;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Sessions {

	final long expireTime;
	final String cookieName;
	final SameSite sameSite;
	final long cookieMaxAge;
	/**
	 * Made with the deprecated create() methods: the session cookie keeps
	 * the old format, and its name comes from the HTTPServer.
	 */
	final boolean legacy;

	Sessions(SessionsBuilder builder) {
		this.expireTime = builder.expireTime;
		this.cookieName = builder.cookieName;
		this.sameSite = builder.sameSite;
		this.cookieMaxAge = builder.cookieMaxAge;
		this.legacy = builder.legacy;
	}

	/**
	 * Creates Sessions kept in memory.
	 *
	 * @param expireTime how long a session lasts without being used, in
	 * milliseconds
	 * @return the Sessions
	 * @deprecated Use {@link SessionsBuilder} instead. Sessions made this way
	 * keep the old session cookie, without SameSite, and take its name from
	 * the HTTPServer.
	 */
	@Deprecated(since = "1.11", forRemoval = true)
	public static Sessions create(long expireTime) {
		return create(expireTime, null);
	}

	/**
	 * Creates Sessions kept in files in a directory, or in memory if the
	 * directory is null.
	 *
	 * @param expireTime how long a session lasts without being used, in
	 * milliseconds
	 * @param directory the directory, or null to keep sessions in memory
	 * @return the Sessions
	 * @deprecated Use {@link SessionsBuilder} instead. Sessions made this way
	 * keep the old session cookie, without SameSite, and take its name from
	 * the HTTPServer.
	 */
	@Deprecated(since = "1.11", forRemoval = true)
	public static Sessions create(long expireTime, File directory) {
		SessionsBuilder builder = new SessionsBuilder();
		// The old methods didn't check the expire time, so don't start now
		builder.expireTime = expireTime;
		builder.directory = directory;
		builder.legacy = true;
		return builder.build();
	}

	/**
	 * Returns the name of the session cookie.
	 *
	 * @return the cookie name
	 */
	public String getCookieName() {
		return cookieName;
	}

	/**
	 * Returns the SameSite attribute of the session cookie.
	 *
	 * @return the SameSite value, or null if the attribute is left out
	 */
	public SameSite getSameSite() {
		return sameSite;
	}

	/**
	 * Returns the Max-Age of the session cookie.
	 *
	 * @return the Max-Age in seconds, or -1 if the cookie has none
	 */
	public long getCookieMaxAge() {
		return cookieMaxAge;
	}

	/**
	 * Returns how long a session lasts without being used.
	 *
	 * @return the time in milliseconds
	 */
	public long getExpireTime() {
		return expireTime;
	}

	// writable handles, by the request that opened them
	private final Map<HTTPRequest, SessionHandle> openHandles = new ConcurrentHashMap<>();

	/**
	 * Opens the request's session for writing, creating one if the client
	 * hasn't got a live session. Waits until no other request has the session
	 * open, then holds its lock until the handle is closed or downgraded, or
	 * the request ends.
	 * <p>
	 * Sets the session cookie when a new session is created, so in that case
	 * it must be called before the response headers are sent.
	 *
	 * @param request the request
	 * @return a writable handle
	 * @throws IllegalStateException if this request already has the session
	 * open for writing, or a new session is needed and the headers were
	 * already sent
	 */
	@SuppressWarnings("deprecation")
	public SessionHandle openSession(HTTPRequest request) {
		if (openHandles.containsKey(request)) {
			throw new IllegalStateException("This request already has the session open for writing");
		}
		while (true) {
			Session session = currentSession(request);
			if (session == null) {
				checkCanSetCookie(request);
				session = newSession();
				setCookie(request, session.getSessionId(), cookieMaxAge);
			} else {
				refreshCookie(request, session);
			}
			session.exclusive.acquireUninterruptibly();
			if (session.isDeleted()) {
				// deleted while we waited for it
				session.exclusive.release();
				continue;
			}
			SessionHandle handle = new SessionHandle(this, request, session, true);
			openHandles.put(request, handle);
			request.addCleanupTask(handle::close);
			return handle;
		}
	}

	/**
	 * Returns a copy of the request's session values, without holding a
	 * lock. Waits if another request has the session open for writing, so
	 * the copy never has half of another request's changes. Counts as a use
	 * of the session, which keeps it from expiring. Never creates a session
	 * or sets a cookie: if the client hasn't got a live session, the handle
	 * has no values and {@link SessionHandle#exists()} is false.
	 *
	 * @param request the request
	 * @return a read-only handle
	 */
	@SuppressWarnings("deprecation")
	public SessionHandle readSession(HTTPRequest request) {
		SessionHandle open = openHandles.get(request);
		if (open != null) {
			// this request holds the lock, so copy from its handle
			return new SessionHandle(this, request, open.getSession(), false);
		}
		Session session = currentSession(request);
		if (session == null) {
			return new SessionHandle(this, request, null, false);
		}
		session.exclusive.acquireUninterruptibly();
		try {
			if (session.isDeleted()) {
				return new SessionHandle(this, request, null, false);
			}
			// records the new last use, at most once a minute when unchanged
			session.save();
			refreshCookie(request, session);
			return new SessionHandle(this, request, session, false);
		} finally {
			session.exclusive.release();
		}
	}

	/**
	 * Deletes the request's session, for example when the user logs out, and
	 * clears the session cookie on the client. Waits if another request has
	 * the session open for writing. If this request has it open, that handle
	 * becomes read-only and empty.
	 * <p>
	 * If the response headers were already sent, the session is still deleted
	 * but the client keeps its now useless cookie.
	 *
	 * @param request the request
	 */
	public void deleteSession(HTTPRequest request) {
		deleteCurrent(request);
		if (request.cookies().containsKey(cookieName(request)) && !request.alreadyWrote()) {
			setCookie(request, "", 0L);
		}
	}

	/**
	 * Deletes the request's session and starts a new one with a new ID, for
	 * example when the user logs in, so that a session ID someone else may
	 * have learned before the login is useless. If this request has the old
	 * session open, that handle becomes read-only and empty.
	 *
	 * @param request the request
	 * @return a writable handle on the new session
	 * @throws IllegalStateException if the response headers were already
	 * sent, so the new session's cookie can't be set
	 */
	@SuppressWarnings("deprecation")
	public SessionHandle resetSession(HTTPRequest request) {
		checkCanSetCookie(request);
		deleteCurrent(request);
		Session session = newSession();
		setCookie(request, session.getSessionId(), cookieMaxAge);
		session.exclusive.acquireUninterruptibly();
		SessionHandle handle = new SessionHandle(this, request, session, true);
		openHandles.put(request, handle);
		request.addCleanupTask(handle::close);
		return handle;
	}

	/**
	 * Deletes the request's current session, if it has one, while holding
	 * its lock.
	 */
	@SuppressWarnings("deprecation")
	private void deleteCurrent(HTTPRequest request) {
		SessionHandle open = openHandles.get(request);
		if (open != null) {
			// this request already holds the lock
			open.getSession().delete();
			open.sessionDeleted();
			return;
		}
		Session session = currentSession(request);
		if (session == null) {
			return;
		}
		session.exclusive.acquireUninterruptibly();
		try {
			session.delete();
		} finally {
			session.exclusive.release();
		}
	}

	void handleReleased(HTTPRequest request, SessionHandle handle) {
		openHandles.remove(request, handle);
	}

	/**
	 * Finds the live session named by the request's session cookie.
	 */
	@SuppressWarnings("deprecation")
	private Session currentSession(HTTPRequest request) {
		String sessionId = request.cookies().get(cookieName(request));
		return sessionId == null ? null : get(sessionId);
	}

	private String cookieName(HTTPRequest request) {
		return legacy ? request.server().getSessionCookieName() : cookieName;
	}

	/**
	 * Sends the cookie for an existing session again if it has a Max-Age, so
	 * it lasts that long from now. Skipped if the headers were already sent.
	 */
	@SuppressWarnings("deprecation")
	private void refreshCookie(HTTPRequest request, Session session) {
		if (cookieMaxAge > 0L && !request.alreadyWrote()) {
			setCookie(request, session.getSessionId(), cookieMaxAge);
		}
	}

	private static void checkCanSetCookie(HTTPRequest request) {
		if (request.alreadyWrote()) {
			throw new IllegalStateException("Headers already sent, cannot set the session cookie");
		}
	}

	/**
	 * Sets the session cookie.
	 *
	 * @param maxAge the Max-Age in seconds, or -1 to leave it out
	 */
	private void setCookie(HTTPRequest request, String sessionId, long maxAge) {
		checkCanSetCookie(request);
		StringBuilder cookie = new StringBuilder();
		cookie.append(cookieName(request)).append('=').append(sessionId);
		if (legacy) {
			// the format HTTPRequest.getSession() has always used
			cookie.append("; path=/; HttpOnly;");
			if (request.isSecure()) {
				cookie.append(" Secure;");
			}
			if (maxAge >= 0L) {
				cookie.append(" Max-Age=").append(maxAge).append(';');
			}
		} else {
			cookie.append("; Path=/; HttpOnly");
			if (request.isSecure()) {
				cookie.append("; Secure");
			}
			if (sameSite != null) {
				cookie.append("; SameSite=").append(sameSite.getValue());
			}
			if (maxAge >= 0L) {
				cookie.append("; Max-Age=").append(maxAge);
			}
		}
		request.response.addHeader("Set-Cookie", cookie.toString());
	}

	public abstract boolean sessionExists(String sessionId);

	public abstract Session newSession();

	/**
	 * Gets an existing session.
	 *
	 * @param sessionId the session ID
	 * @return the session, or null if there's no live session with that ID.
	 * Unknown, expired and deleted IDs return null rather than creating a
	 * session, so a client can't choose its own session ID. Use
	 * {@link #newSession()} to start a session.
	 */
	public abstract Session get(String sessionId);

	public final boolean hasExpired(Session session) {
		if (session == null) {
			return true;
		}
		return hasExpired(session.lastUse);
	}

	public final boolean hasExpired(long time) {
		long now = System.currentTimeMillis();
		return now - time > expireTime;
	}

	void save(Session session) {
	}

	boolean shouldSave() {
		return false;
	}

	abstract void delete(Session session);

	static boolean validateSessionId(String sessionId) {
		if (sessionId == null) {
			return false;
		}
		return sessionId.matches("[0-9A-Fa-f]{1,}");
	}

	static String write(Map.Entry<String, String> line) {
		StringBuilder out = new StringBuilder();
		String key = line.getKey();
		String val = line.getValue();
		write(out, key);
		if (!val.isEmpty()) {
			out.append('=');
			write(out, val);
		}
		return out.toString();
	}

	static void write(StringBuilder out, String line) {
		try {
			CharArrayReader car = new CharArrayReader(line.toCharArray());
			int c;
			while ((c = car.read()) != -1) {
				char cc = (char) c;
				if (cc == '\r') {
					out.append('\\');
					out.append('r');
				} else if (cc == '\n') {
					out.append('\\');
					out.append('n');
				} else if (cc == '\t') {
					out.append('\\');
					out.append('t');
				} else if (cc == '=') {
					out.append('\\');
					out.append('=');
				} else if (cc == '\\') {
					out.append('\\');
					out.append('\\');
				} else {
					out.append(cc);
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	static Map.Entry<String, String> read(String line) {
		try {
			StringBuilder key = new StringBuilder();
			StringBuilder val = new StringBuilder();
			StringBuilder write = key;
			CharArrayReader car = new CharArrayReader(line.toCharArray());
			int c;
			while ((c = car.read()) != -1) {
				char cc = (char) c;
				if (cc == '=' && write == key) {
					write = val;
				} else if (cc == '\\') {
					int read = car.read();
					if (read == -1) {
						continue;
					}
					char readC = (char) read;
					if (readC == 'r') {
						write.append('\r');
					} else if (readC == 'n') {
						write.append('\n');
					} else if (readC == 't') {
						write.append('\t');
					} else {
						write.append(readC);
					}
				} else {
					write.append(cc);
				}
			}
			return new E(key.toString(), val.toString());
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	private static class E implements Map.Entry<String, String> {

		private final String key, value;

		private E(String key, String value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public String getKey() {
			return key;
		}

		@Override
		public String getValue() {
			return value;
		}

		@Override
		public String setValue(String value) {
			throw new UnsupportedOperationException("You can't change this!");
		}
	}

	public static String randomSessionId() {
		SecureRandom random = new SecureRandom();
		StringBuilder sb = new StringBuilder();
		char[] c = "0123456789abcdef".toCharArray();
		for (int i = 0; i < 64; i++) {
			sb.append(c[random.nextInt(c.length)]);
		}
		return sb.toString();
	}
}
