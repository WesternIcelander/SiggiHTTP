package io.siggi.http.session;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SessionsTest {

	private static final long LIFETIME = 3600000L;

	private static void assertUnknownIdsAreNotCreated(Sessions sessions) {
		assertNull(sessions.get("abcdef0123"));
		// Asking again must not find a session that the first call created
		assertNull(sessions.get("abcdef0123"));
		assertNull(sessions.get("not-hex"));
		assertNull(sessions.get(null));
	}

	private static void assertExistingSessionIsFound(Sessions sessions) {
		Session session = sessions.newSession();
		assertSame(session, sessions.get(session.getSessionId()));
	}

	private static void assertDeletedSessionIsNotRecreated(Sessions sessions) {
		Session session = sessions.newSession();
		session.save();
		String id = session.getSessionId();
		session.delete();
		assertNull(sessions.get(id));
	}

	@Test
	void memoryUnknownIdReturnsNull() {
		assertUnknownIdsAreNotCreated(Sessions.create(LIFETIME));
	}

	@Test
	void memoryExistingSessionIsFound() {
		assertExistingSessionIsFound(Sessions.create(LIFETIME));
	}

	@Test
	void memoryDeletedSessionIsNotRecreated() {
		assertDeletedSessionIsNotRecreated(Sessions.create(LIFETIME));
	}

	@Test
	void diskUnknownIdReturnsNullAndWritesNothing(@TempDir Path dir) {
		Sessions sessions = Sessions.create(LIFETIME, dir.toFile());
		assertUnknownIdsAreNotCreated(sessions);
		assertEquals(0, dir.toFile().list().length);
	}

	@Test
	void diskExistingSessionIsFound(@TempDir Path dir) {
		assertExistingSessionIsFound(Sessions.create(LIFETIME, dir.toFile()));
	}

	@Test
	void diskSavedSessionIsLoadedFromFile(@TempDir Path dir) {
		Session session = Sessions.create(LIFETIME, dir.toFile()).newSession();
		session.set("user", "siggi");
		session.save();
		// A second instance has nothing in memory, so it has to read the file
		Session loaded = Sessions.create(LIFETIME, dir.toFile()).get(session.getSessionId());
		assertNotNull(loaded);
		assertEquals("siggi", loaded.get("user"));
	}

	@Test
	void diskDeletedSessionIsNotRecreated(@TempDir Path dir) {
		File directory = dir.toFile();
		assertDeletedSessionIsNotRecreated(Sessions.create(LIFETIME, directory));
		assertEquals(0, directory.list().length);
	}

	private static int fileCount(File directory) {
		String[] names = directory.list();
		return names == null ? 0 : names.length;
	}

	private static void deleteFiles(File directory) {
		for (File f : directory.listFiles()) {
			assertTrue(f.delete());
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void diskUnchangedSessionIsNotRewritten(@TempDir Path dir) {
		File directory = dir.toFile();
		Session session = Sessions.create(LIFETIME, directory).newSession();
		session.set("user", "siggi");
		session.save();
		assertEquals(1, fileCount(directory));
		deleteFiles(directory);
		session.save();
		assertEquals(0, fileCount(directory));
		session.set("user", "someone else");
		session.save();
		assertEquals(1, fileCount(directory));
	}

	@Test
	@SuppressWarnings("deprecation")
	void diskLastUseIsRewrittenAfterAMinute(@TempDir Path dir) {
		File directory = dir.toFile();
		Session session = Sessions.create(LIFETIME, directory).newSession();
		session.save();
		deleteFiles(directory);
		session.savedLastUse -= Session.SAVE_LAST_USE_INTERVAL;
		session.save();
		assertEquals(1, fileCount(directory));
	}

	@Test
	void diskCachedSessionLookupUpdatesLastUse(@TempDir Path dir) {
		Sessions sessions = Sessions.create(LIFETIME, dir.toFile());
		Session session = sessions.newSession();
		long old = System.currentTimeMillis() - LIFETIME / 2;
		session.lastUse = old;
		// session is still strongly referenced, so this finds the cached copy
		assertSame(session, sessions.get(session.getSessionId()));
		assertTrue(session.lastUse > old + 1000, "lastUse wasn't updated");
	}

	@Test
	@SuppressWarnings("deprecation")
	void diskValuesAreStoredAsUtf8(@TempDir Path dir) throws Exception {
		File directory = dir.toFile();
		String value = "\u00fc \ud83d\ude00 a=b\\c\nd";
		Session session = Sessions.create(LIFETIME, directory).newSession();
		session.set("name", value);
		session.save();
		byte[] file = java.nio.file.Files.readAllBytes(directory.listFiles()[0].toPath());
		assertTrue(new String(file, java.nio.charset.StandardCharsets.UTF_8).contains("\u00fc \ud83d\ude00"));
		assertEquals(value, Sessions.create(LIFETIME, directory).get(session.getSessionId()).get("name"));
	}
}
