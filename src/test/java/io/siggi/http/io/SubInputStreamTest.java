package io.siggi.http.io;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubInputStreamTest {

	private static InputStream bytes(int length) {
		return new ByteArrayInputStream(new byte[length]);
	}

	@Test
	void readsExactlyTheLengthThenEnds() throws IOException {
		InputStream in = new SubInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 3L);
		byte[] buffer = new byte[10];
		assertEquals(3, in.read(buffer));
		assertArrayEquals(new byte[]{1, 2, 3}, java.util.Arrays.copyOf(buffer, 3));
		assertEquals(-1, in.read(buffer));
		assertEquals(-1, in.read());
	}

	@Test
	void zeroLengthEndsWithoutReading() throws IOException {
		InputStream in = new SubInputStream(bytes(0), 0L);
		assertEquals(-1, in.read());
		assertEquals(-1, in.read(new byte[10]));
	}

	@Test
	void underlyingStreamEndingEarlyThrowsOnBulkRead() throws IOException {
		InputStream in = new SubInputStream(bytes(100), 101L);
		byte[] buffer = new byte[1000];
		assertEquals(100, in.read(buffer));
		assertThrows(EOFException.class, () -> in.read(buffer));
	}

	@Test
	void underlyingStreamEndingEarlyThrowsOnSingleByteRead() throws IOException {
		InputStream in = new SubInputStream(bytes(2), 3L);
		assertEquals(0, in.read());
		assertEquals(0, in.read());
		assertThrows(EOFException.class, in::read);
	}
}
