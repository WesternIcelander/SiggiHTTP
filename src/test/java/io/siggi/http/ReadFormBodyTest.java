package io.siggi.http;

import io.siggi.http.exception.TooBigException;
import io.siggi.http.io.ChunkedInputStream;
import io.siggi.http.io.SubInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadFormBodyTest {

	private static InputStream bytes(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	void readsChunkedBody() throws Exception {
		InputStream in = new ChunkedInputStream(bytes("3\r\na=1\r\n4\r\n&b=2\r\n0\r\n\r\n"));
		assertArrayEquals(ascii("a=1&b=2"), HTTPHandler.readFormBody(in, -1L, 0));
	}

	@Test
	void chunkedBodyOverLimitIsTooBig() {
		InputStream in = new ChunkedInputStream(bytes("3\r\na=1\r\n4\r\n&b=2\r\n0\r\n\r\n"));
		assertThrows(TooBigException.class, () -> HTTPHandler.readFormBody(in, -1L, 5));
	}

	@Test
	void readsContentLengthBody() throws Exception {
		InputStream in = new SubInputStream(bytes("a=1&b=2EXTRA"), 7L);
		assertArrayEquals(ascii("a=1&b=2"), HTTPHandler.readFormBody(in, 7L, 7));
	}

	@Test
	void declaredContentLengthOverLimitIsTooBig() {
		InputStream in = new SubInputStream(bytes("a=1&b=2"), 7L);
		assertThrows(TooBigException.class, () -> HTTPHandler.readFormBody(in, 7L, 6));
	}

	@Test
	void truncatedContentLengthBodyThrowsEOF() {
		InputStream in = new SubInputStream(bytes("a=1"), 100L);
		assertThrows(EOFException.class, () -> HTTPHandler.readFormBody(in, 100L, 0));
	}
}
