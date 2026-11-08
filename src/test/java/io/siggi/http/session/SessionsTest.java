package io.siggi.http.session;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
