package io.siggi.http.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureBufferedInputStreamTest {

	private static byte[] data(int length) {
		byte[] data = new byte[length];
		for (int i = 0; i < length; i++) {
			data[i] = (byte) i;
		}
		return data;
	}

	private static int bufferLength(SecureBufferedInputStream in) throws Exception {
		Field field = SecureBufferedInputStream.class.getDeclaredField("buffer");
		field.setAccessible(true);
		return ((byte[]) field.get(in)).length;
	}

	private static byte[] readFully(SecureBufferedInputStream in, int length) throws IOException {
		byte[] result = new byte[length];
		int done = 0;
		while (done < length) {
			int amount = in.read(result, done, length - done);
			if (amount == -1) {
				throw new IOException("ended early");
			}
			done += amount;
		}
		return result;
	}

	@Test
	void bufferDoublesWhenAMarkOutgrowsIt() throws Exception {
		byte[] data = data(1000);
		SecureBufferedInputStream in = new SecureBufferedInputStream(new ByteArrayInputStream(data), 16);
		in.mark(1000);
		assertArrayEquals(Arrays.copyOf(data, 40), readFully(in, 40));
		// Two growths from 16 to hold 40 bytes: 32, then 64
		assertEquals(64, bufferLength(in));
	}

	@Test
	void resetAfterGrowingReturnsTheMarkedBytes() throws Exception {
		byte[] data = data(1000);
		SecureBufferedInputStream in = new SecureBufferedInputStream(new ByteArrayInputStream(data), 16);
		assertArrayEquals(Arrays.copyOf(data, 5), readFully(in, 5));
		in.mark(1000);
		byte[] first = readFully(in, 500);
		in.reset();
		assertArrayEquals(first, readFully(in, 500));
		assertArrayEquals(Arrays.copyOfRange(data, 5, 505), first);
		assertArrayEquals(Arrays.copyOfRange(data, 505, 1000), readFully(in, 495));
		assertEquals(-1, in.read());
		assertTrue(bufferLength(in) <= 1024, "buffer grew to " + bufferLength(in));
	}

	@Test
	void bufferSizeMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new SecureBufferedInputStream(new ByteArrayInputStream(new byte[0]), 0));
		assertThrows(IllegalArgumentException.class, () -> new SecureBufferedInputStream(new ByteArrayInputStream(new byte[0]), -1));
	}
}
