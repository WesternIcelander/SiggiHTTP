package io.siggi.http.session;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionsBuilderTest {

	@Test
	void defaults() {
		Sessions sessions = new SessionsBuilder().build();
		assertTrue(sessions instanceof SessionsMemory);
		assertEquals(3600000L, sessions.getExpireTime());
		assertEquals("SessID", sessions.getCookieName());
		assertEquals(SameSite.LAX, sessions.getSameSite());
		assertFalse(sessions.legacy);
	}

	@Test
	void settings(@TempDir Path dir) {
		File directory = new File(dir.toFile(), "sessions");
		Sessions sessions = new SessionsBuilder()
				.setExpireTime(5000L)
				.setDirectory(directory)
				.setCookieName("App_Session")
				.setSameSite(null)
				.build();
		assertTrue(sessions instanceof SessionsDisk);
		assertTrue(directory.isDirectory());
		assertEquals(5000L, sessions.getExpireTime());
		assertEquals("App_Session", sessions.getCookieName());
		assertNull(sessions.getSameSite());
	}

	@Test
	@SuppressWarnings("deprecation")
	void createMakesLegacySessions() {
		assertTrue(Sessions.create(1000L).legacy);
	}

	@Test
	void invalidSettingsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new SessionsBuilder().setExpireTime(0L));
		assertThrows(IllegalArgumentException.class, () -> new SessionsBuilder().setCookieName("a b"));
		assertThrows(IllegalArgumentException.class, () -> new SessionsBuilder().setCookieName("a;b"));
		assertThrows(IllegalArgumentException.class, () -> new SessionsBuilder().setCookieName("a=b"));
		assertThrows(IllegalArgumentException.class, () -> new SessionsBuilder().setCookieName(""));
		assertThrows(IllegalArgumentException.class, () -> new SessionsBuilder().setCookieName(null));
	}
}
