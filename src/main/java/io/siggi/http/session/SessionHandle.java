package io.siggi.http.session;

import io.siggi.http.HTTPRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A request's view of its session, from {@link Sessions#openSession},
 * {@link Sessions#readSession} or {@link Sessions#resetSession}.
 * <p>
 * A writable handle holds the session's lock, so no other request can read
 * or write the session until it's closed or downgraded. Close it as soon as
 * you're done writing; it's closed automatically when the request ends, but
 * a long response would keep other requests for the session waiting until
 * then. A read-only handle holds a copy of the session's values and no lock,
 * so it doesn't need closing.
 */
@SuppressWarnings("deprecation")
public final class SessionHandle implements AutoCloseable {

	private final Sessions sessions;
	private final HTTPRequest request;
	private final Session session;
	private boolean writable;
	private Map<String, String> copy;

	SessionHandle(Sessions sessions, HTTPRequest request, Session session, boolean writable) {
		this.sessions = sessions;
		this.request = request;
		this.session = session;
		this.writable = writable;
		if (!writable) {
			this.copy = session == null ? Collections.<String, String>emptyMap() : session.snapshot();
		}
	}

	Session getSession() {
		return session;
	}

	/**
	 * Returns whether there's a session. A handle from
	 * {@link Sessions#readSession} has none if the client hasn't got a live
	 * session; it then has no values and a null session ID.
	 *
	 * @return true if there's a session
	 */
	public boolean exists() {
		return session != null;
	}

	/**
	 * Returns the session ID.
	 *
	 * @return the session ID, or null if there's no session
	 */
	public String getSessionId() {
		return session == null ? null : session.getSessionId();
	}

	/**
	 * Returns whether this handle can change the session.
	 *
	 * @return true until the handle is downgraded or closed
	 */
	public synchronized boolean isWritable() {
		return writable;
	}

	/**
	 * Reads a value.
	 *
	 * @param key the key
	 * @return the value, or null if there isn't one
	 */
	public synchronized String get(String key) {
		return writable ? session.get(key) : copy.get(key);
	}

	/**
	 * Returns all values, as a map that can't be changed. For a writable
	 * handle, it's a copy taken now.
	 *
	 * @return the values
	 */
	public synchronized Map<String, String> getAll() {
		return writable ? session.snapshot() : copy;
	}

	/**
	 * Returns whether the session has no values.
	 *
	 * @return true if there are no values
	 */
	public synchronized boolean isEmpty() {
		return writable ? session.isEmpty() : copy.isEmpty();
	}

	/**
	 * Sets a value. Setting null removes it.
	 *
	 * @param key the key
	 * @param value the value, or null to remove it
	 * @return the previous value, or null if there wasn't one
	 * @throws IllegalStateException if this handle isn't writable
	 */
	public synchronized String set(String key, String value) {
		checkWritable();
		return session.set(key, value);
	}

	/**
	 * Removes all values.
	 *
	 * @throws IllegalStateException if this handle isn't writable
	 */
	public synchronized void clear() {
		checkWritable();
		session.clear();
	}

	private void checkWritable() {
		if (!writable) {
			throw new IllegalStateException("This session handle is read-only");
		}
	}

	/**
	 * Saves the session and releases its lock, keeping a copy of its values
	 * to read. Does nothing if this handle isn't writable.
	 *
	 * @return this handle
	 */
	public SessionHandle downgrade() {
		synchronized (this) {
			if (!writable) {
				return this;
			}
			copy = session.snapshot();
			writable = false;
			try {
				session.save();
			} finally {
				session.exclusive.release();
			}
		}
		sessions.handleReleased(request, this);
		return this;
	}

	/**
	 * Saves the session and releases its lock, the same as
	 * {@link #downgrade()}. The values can still be read afterwards. Safe to
	 * call more than once, and on a read-only handle.
	 */
	@Override
	public void close() {
		downgrade();
	}
}
