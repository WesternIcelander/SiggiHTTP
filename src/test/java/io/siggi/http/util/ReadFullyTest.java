package io.siggi.http.util;

import io.siggi.http.exception.TooBigException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadFullyTest {

	private static InputStream bytes(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
	}

	@Test
	void noLimitReadsEverything() throws Exception {
		assertEquals("hello", Util.readFullyAsString(bytes("hello")));
		assertArrayEquals(new byte[10000], Util.readFully(new ByteArrayInputStream(new byte[10000])));
	}

	@Test
	void withinLimitIsAccepted() throws Exception {
		assertEquals("hello", Util.readFullyAsString(bytes("hello"), 5));
	}

	@Test
	void overLimitThrows() {
		assertThrows(TooBigException.class, () -> Util.readFully(bytes("hello"), 4));
		assertThrows(TooBigException.class, () -> Util.readFully(bytes("x"), 0));
	}
}
