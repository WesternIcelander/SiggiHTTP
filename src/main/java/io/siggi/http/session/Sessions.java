package io.siggi.http.session;

import java.io.CharArrayReader;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;

public abstract class Sessions {

	final long expireTime;
	final String cookieName;
	final SameSite sameSite;
	/**
	 * Made with the deprecated create() methods: the session cookie keeps
	 * the old format, and its name comes from the HTTPServer.
	 */
	final boolean legacy;

	Sessions(SessionsBuilder builder) {
		this.expireTime = builder.expireTime;
		this.cookieName = builder.cookieName;
		this.sameSite = builder.sameSite;
		this.legacy = builder.legacy;
	}

	public static Sessions create(long expireTime) {
		return create(expireTime, null);
	}

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
	 * Returns how long a session lasts without being used.
	 *
	 * @return the time in milliseconds
	 */
	public long getExpireTime() {
		return expireTime;
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
