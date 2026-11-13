package io.siggi.http.session;

import io.siggi.http.util.Util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("removal") // uses Session, which will become package-private
final class SessionsDisk extends Sessions {

	SessionsDisk(SessionsBuilder builder) {
		super(builder);
		this.directory = builder.directory;
		if (!directory.exists()) {
			directory.mkdirs();
		}
	}

	private final File directory;

	private File getSessionFile(String sessionId) {
		return new File(
                directory,
				Util.bytesToHex(Util.sha256().digest(sessionId.getBytes(StandardCharsets.UTF_8))) + ".txt"
		);
	}

	private final Map<String, WeakReference<Session>> sessions = new HashMap<>();

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
			memoryCheck:
			{
				WeakReference<Session> sessionRef = sessions.get(sessionId);
				if (sessionRef == null) {
					break memoryCheck;
				}
				Session session = sessionRef.get();
				if (session == null) {
					break memoryCheck;
				}
				if (hasExpired(session.lastUse)) {
					return false;
				}
				return true;
			}
			fileCheck:
			{
				File f = getSessionFile(sessionId);
				if (!f.exists()) {
					return false;
				}
				long lastUse = readLastUse(f);
				if (hasExpired(lastUse)) {
					return false;
				}
			}
			return true;
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
			sessions.put(sessionId, new WeakReference<>(session));
			session.lastUse=System.currentTimeMillis();
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
		readLock:
		try {
			WeakReference<Session> sessionRef = sessions.get(sessionId);
			if (sessionRef == null) {
				break readLock;
			}
			Session session = sessionRef.get();
			if (session == null) {
				break readLock;
			}
			if (hasExpired(session.lastUse)) {
				break readLock;
			}
			session.lastUse = System.currentTimeMillis();
			return session;
		} finally {
			readLock.unlock();
		}
		writeLock.lock();
		writeLock:
		try {
			sweep();
			existCheck:
			{
				memoryCheck:
				{
					WeakReference<Session> sessionRef = sessions.get(sessionId);
					if (sessionRef == null) {
						break memoryCheck;
					}
					Session session = sessionRef.get();
					if (session == null) {
						break memoryCheck;
					}
					if (hasExpired(session.lastUse)) {
						break existCheck;
					}
					session.lastUse = System.currentTimeMillis();
					return session;
				}
				fileCheck:
				{
					File f = getSessionFile(sessionId);
					if (!f.exists()) {
						break existCheck;
					}
					long lastUse = readLastUse(f);
					if (hasExpired(lastUse)) {
						f.delete();
						break existCheck;
					}
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
						Session session = new Session(this, sessionId);
						session.load(reader);
						sessions.put(sessionId, new WeakReference<>(session));
						session.lastUse = System.currentTimeMillis();
						return session;
					} catch (IOException ioe) {
						break existCheck;
					}
				}
			}
			return null;
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	void save(Session session) {
		readLock.lock();
		try {
			File sessionFile = getSessionFile(session.sessionId);
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(sessionFile), StandardCharsets.UTF_8))) {
				session.save(writer);
			} catch (IOException ioe) {
				System.err.println("SiggiHTTP: couldn't save session to " + sessionFile + ": " + ioe);
			}
		} finally {
			readLock.unlock();
		}
	}

	@Override
	boolean shouldSave() {
		return true;
	}

	@Override
	void delete(Session session) {
		writeLock.lock();
		try {
			File sessionFile = getSessionFile(session.sessionId);
			WeakReference<Session> ref = sessions.get(session.sessionId);
			if (ref != null) {
				Session s = ref.get();
				if (session == s) {
					sessions.remove(session.sessionId);
					sessionFile.delete();
				}
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
		try (DirectoryStream<Path> newDirectoryStream = Files.newDirectoryStream(directory.toPath())) {
			for (Path path : newDirectoryStream) {
				File file = path.toFile();
				if (file.getName().endsWith(".txt")) {
					sweep(file);
				}
			}
		} catch (IOException ex) {
		}
	}

	private void sweep(File f) {
		if (hasExpired(readLastUse(f))) {
			f.delete();
		}
	}

	private long readLastUse(File f) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
			return Long.parseLong(reader.readLine());
		} catch (IOException | NumberFormatException e) {
		}
		return 0L;
	}

}
