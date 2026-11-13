package io.siggi.http.cookie;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CookieBakeryTest {

	@Test
	void bakesACookieWithAllAttributes() {
		assertEquals("userLikesCookies=yes; Path=/; HttpOnly; Secure; Max-Age=3600", CookieBakery
				.name("userLikesCookies")
				.value("yes")
				.maxAge(3600)
				.secure(true)
				.httpOnly(true)
				.bake());
		assertEquals("a=b; Path=/app; Domain=example.com; SameSite=Strict", CookieBakery
				.name("a").value("b").path("/app").domain("example.com").sameSite(SameSite.STRICT).bake());
	}

	@Test
	void defaults() {
		assertEquals("a=; Path=/", CookieBakery.name("a").bake());
		assertEquals("a=1", CookieBakery.name("a").value("1").path(null).bake());
		assertEquals("a=1; Path=/", CookieBakery.name("a").value("1").maxAge(-5).secure(false).httpOnly(false).bake());
	}

	@Test
	void deletesACookie() {
		assertEquals("userLikesCookies=; Path=/; Max-Age=0", CookieBakery.name("userLikesCookies").delete());
		assertEquals("a=; Path=/app; Domain=example.com; HttpOnly; Secure; SameSite=Lax; Max-Age=0", CookieBakery
				.name("a").value("ignored").maxAge(3600).path("/app").domain("example.com")
				.httpOnly(true).secure(true).sameSite(SameSite.LAX).delete());
	}

	@Test
	void sameSiteValues() {
		assertEquals("a=; Path=/; SameSite=None", CookieBakery.name("a").sameSite(SameSite.NONE).bake());
		assertEquals("a=; Path=/; SameSite=Lax", CookieBakery.name("a").sameSite(SameSite.LAX).bake());
	}

	@Test
	void invalidInputIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name(null));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name(""));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a b"));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a=b"));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a;b"));
		for (String value : new String[]{"a b", "a;b", "a,b", "a\"b", "a\\b", "a\r\nX-Injected: yes", "ü"}) {
			assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a").value(value), value);
		}
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a").path("/; Domain=evil.example"));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a").path("/\r\n"));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a").path(""));
		assertThrows(IllegalArgumentException.class, () -> CookieBakery.name("a").domain("a.example; Secure"));
	}
}
