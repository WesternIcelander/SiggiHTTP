package io.siggi.http.session;

import static io.siggi.http.session.Sessions.randomSessionId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class SessionsMemory extends Sessions {

	SessionsMemory(long expireTime) {
		super(expireTime);
	}

	private final Map<String, Session> sessions = new HashMap<>();

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final Lock readLock = lock.readLock();
	private final Lock writeLock = lock.writeLock();

	@Override
	public boolean sessionExists(String sessionId) {
		if (!validateSessionId(sessionId)) {
			return false;
		}
		readLock.lock();
		try {
			Session session = sessions.get(sessionId);
			return session != null && !hasExpired(session);
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Session newSession() {
		writeLock.lock();
		try {
			sweep();
			String sessionId;
			do {
				sessionId = randomSessionId();
			} while (sessionExists(sessionId));
			Session session = new Session(this, sessionId);
			sessions.put(sessionId, session);
			session.lastUse = System.currentTimeMillis();
			return session;
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public Session get(String sessionId) {
		if (!validateSessionId(sessionId)) {
			return null;
		}
		readLock.lock();
		try {
			Session session = sessions.get(sessionId);
			if (session != null && !hasExpired(session)) {
				session.lastUse = System.currentTimeMillis();
				return session;
			}
		} finally {
			readLock.unlock();
		}
		writeLock.lock();
		try {
			sweep();
			for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Session> entry = it.next();
				if (hasExpired(entry.getValue())) {
					it.remove();
				}
			}
			Session session = sessions.get(sessionId);
			if (session != null && !hasExpired(session)) {
				session.lastUse = System.currentTimeMillis();
				return session;
			}
			session = new Session(this, sessionId);
			sessions.put(sessionId, session);
			session.lastUse = System.currentTimeMillis();
			return session;
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	void delete(Session session) {
		writeLock.lock();
		try {
			Session s = sessions.get(session.sessionId);
			if (s == session) {
				sessions.remove(session.sessionId);
			}
		} finally {
			writeLock.unlock();
		}
	}

	private long lastSweep = 0L;

	private void sweep() {
		long now = System.currentTimeMillis();
		if (now - lastSweep < 1800000L) {
			return;
		}
		lastSweep = now;
		for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Session> val = it.next();
			if (hasExpired(val.getValue())) {
				it.remove();
			}
		}
	}

}
