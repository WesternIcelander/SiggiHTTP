package io.siggi.http.session;

import io.siggi.http.HTTPRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A session's values.
 *
 * @deprecated Use {@link Sessions#openSession}, {@link Sessions#readSession}
 * and {@link Sessions#resetSession}, which return a {@link SessionHandle}.
 * This class will become package-private.
 */
@Deprecated(since = "1.11", forRemoval = true)
public final class Session {

	private final Map<String, String> values = new HashMap<>();
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final Lock readLock = lock.readLock();
	private final Lock writeLock = lock.writeLock();

	private boolean deleted = false;

	private final Sessions sessions;
	final String sessionId;
	// volatile: lookups update it while holding only a read lock
	volatile long lastUse;
	// the last use recorded in storage, so an unchanged session is only
	// rewritten to record a new last use once per SAVE_LAST_USE_INTERVAL
	long savedLastUse;

	static final long SAVE_LAST_USE_INTERVAL = 60000L;

	private boolean changed = false;

	// held by a writable SessionHandle; not a ReentrantLock, because a
	// response handed to another thread may close its handle from there
	final Semaphore exclusive = new Semaphore(1);

	Session(Sessions sessions, String sessionId) {
		this.sessions = sessions;
		this.sessionId = sessionId;
	}

	/**
	 * Write the session to storage backend. This is done automatically by the
	 * server and should not be called by website code.
	 *
	 * @deprecated Not public API
	 */
	@Deprecated
	public void save() {
		writeLock.lock();
		try {
			if (deleted) {
				return;
			}
			if (sessions.shouldSave() && (changed || lastUse - savedLastUse >= SAVE_LAST_USE_INTERVAL)) {
				sessions.save(this);
			}
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Read a value from this session.
	 *
	 * @param key the key
	 * @return the value
	 */
	public String get(String key) {
		readLock.lock();
		try {
			if (deleted) {
				throw new IllegalStateException("Session was deleted.");
			}
			return values.get(key);
		} finally {
			readLock.unlock();
		}
	}

	/**
	 * Set a value on this session
	 *
	 * @param key the key
	 * @param val the value
	 * @return the previous value, or null if there wasn't a previous value.
	 */
	public String set(String key, String val) {
		writeLock.lock();
		try {
			if (deleted) {
				throw new IllegalStateException("Session was deleted.");
			}
			String old = values.get(key);
			if (old == null ? val == null : old.equals(val)) {
				return old;
			}
			changed = true;
			if (val == null) {
				values.remove(key);
			} else {
				values.put(key, val);
			}
			return old;
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Check if this session is empty (no values).
	 *
	 * @return true if it is empty
	 */
	public boolean isEmpty() {
		readLock.lock();
		try {
			return values.isEmpty();
		} finally {
			readLock.unlock();
		}
	}

	void load(BufferedReader reader) throws IOException {
		try {
			String l = reader.readLine();
			if (l == null) {
				return;
			}
			lastUse = savedLastUse = Long.parseLong(l);
		} catch (NumberFormatException nfe) {
			return;
		}
		String line;
		values.clear();
		while ((line = reader.readLine()) != null) {
			Map.Entry<String, String> read = Sessions.read(line);
			values.put(read.getKey(), read.getValue());
		}
	}

	void save(Writer writer) throws IOException {
		long lastUseNow = lastUse;
		writer.write(Long.toString(lastUseNow) + "\n");
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String write = Sessions.write(entry);
			writer.write(write);
			writer.write("\n");
		}
		writer.flush();
		changed = false;
		savedLastUse = lastUseNow;
	}

	/**
	 * Clear the values on this session.
	 */
	public void clear() {
		writeLock.lock();
		try {
			changed = true;
			values.clear();
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Delete this session. Calling this does not clear the session cookie from
	 * the client, see {@link HTTPRequest#resetSession()} if you need to change
	 * the session ID for a security reason.
	 */
	public void delete() {
		writeLock.lock();
		try {
			deleted = true;
			values.clear();
			sessions.delete(this);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Check to see if this session is deleted.
	 *
	 * @return true if it was deleted
	 */
	public boolean isDeleted() {
		readLock.lock();
		try {
			return deleted;
		} finally {
			readLock.unlock();
		}
	}

	/**
	 * Get the session ID.
	 *
	 * @return the session ID
	 */
	public String getSessionId() {
		return sessionId;
	}

	@SuppressWarnings("deprecation")
	public void resetExpiry() {
		writeLock.lock();
		try {
			lastUse = System.currentTimeMillis();
		} finally {
			writeLock.unlock();
		}
		save();
	}

	public Map<String, String> snapshot() {
		readLock.lock();
		try {
			Map<String,String> snapshot = new HashMap<>();
			snapshot.putAll(values);
			return Collections.unmodifiableMap(snapshot);
		} finally {
			readLock.unlock();
		}
	}
}
