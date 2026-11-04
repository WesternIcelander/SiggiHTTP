package io.siggi.http.io;

import io.siggi.http.util.Util;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkedInputStreamTest {

	private static InputStream chunked(String s) {
		return new ChunkedInputStream(new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	void completeBodyIsRead() throws Exception {
		assertEquals("hello world", Util.readFullyAsString(chunked("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")));
	}

	@Test
	void bodyCutOffMidChunkThrows() {
		assertThrows(EOFException.class, () -> Util.readFully(chunked("a\r\nhello")));
	}
}
