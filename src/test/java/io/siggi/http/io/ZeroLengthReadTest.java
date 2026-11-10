package io.siggi.http.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A read of zero bytes returns 0 without reading, as the InputStream contract
 * requires. JDK 9+ readNBytes and readAllBytes depend on this.
 */
class ZeroLengthReadTest {

	/**
	 * Serves the given bytes, then fails any further read, so a zero-length
	 * read that tries to read more is caught instead of blocking.
	 */
	private static InputStream thenFail(byte[] data) {
		return new SequenceInputStream(new ByteArrayInputStream(data), new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("read past the available data");
			}
		});
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	void eofInputStreamMidBody() throws IOException {
		EOFInputStream in = new EOFInputStream(new ByteArrayInputStream(new byte[1000]));
		byte[] buffer = new byte[200];
		assertEquals(100, in.read(buffer, 0, 100));
		assertEquals(0, in.read(buffer, 100, 0));
		assertEquals(100, in.read(buffer, 0, 100));
	}

	@Test
	void eofInputStreamWithEofSequence() throws IOException {
		EOFInputStream in = new EOFInputStream(new ByteArrayInputStream(ascii("abcdef--end--rest")));
		in.setEofSequence(ascii("--end--"));
		byte[] buffer = new byte[20];
		assertEquals(0, in.read(buffer, 0, 0));
		assertEquals(6, in.read(buffer, 0, 20));
		assertEquals(-1, in.read(buffer, 0, 20));
	}

	@Test
	void secureBufferedInputStreamDoesNotFillOnZeroLength() throws IOException {
		SecureBufferedInputStream in = new SecureBufferedInputStream(thenFail(new byte[10]));
		byte[] buffer = new byte[20];
		assertEquals(10, in.read(buffer, 0, 20));
		assertEquals(0, in.read(buffer, 0, 0));
	}

	@Test
	void subInputStreamAtEndOfBody() throws IOException {
		SubInputStream in = new SubInputStream(new ByteArrayInputStream(new byte[10]), 10L);
		byte[] buffer = new byte[20];
		assertEquals(0, in.read(buffer, 0, 0));
		assertEquals(10, in.read(buffer, 0, 20));
		assertEquals(0, in.read(buffer, 0, 0));
		assertEquals(-1, in.read(buffer, 0, 20));
	}

	@Test
	void chunkedInputStreamDoesNotReadTheNextChunkSizeOnZeroLength() throws IOException {
		ChunkedInputStream in = new ChunkedInputStream(thenFail(new byte[0]));
		byte[] buffer = new byte[20];
		assertEquals(0, in.read(buffer, 0, 0));
	}

	@Test
	void readNBytesReadsPastSixteenKibibytes() throws IOException {
		byte[] data = new byte[70000];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) i;
		}
		InputStream in = new EOFInputStream(new SubInputStream(new ByteArrayInputStream(data), data.length));
		assertEquals(data.length, in.readNBytes(300000).length);
	}
}
