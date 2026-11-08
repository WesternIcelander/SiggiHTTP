package io.siggi.http.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads exactly {@code maxRead} bytes from another stream, then reports end of
 * stream. If the other stream ends before then, reading throws an
 * EOFException, because the data is incomplete.
 */
public final class SubInputStream extends InputStream {

	private final InputStream in;
	private long left;

	public SubInputStream(InputStream in, long maxRead) {
		this.in = in;
		this.left = maxRead;
	}

	@Override
	public int read() throws IOException {
		if (left == 0L) {
			return -1;
		}
		int read = in.read();
		if (read == -1) {
			throw endedEarly();
		}
		left -= 1;
		return read;
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public int read(byte[] b, int offset, int len) throws IOException {
		if (left == 0L) {
			return -1;
		}
		int max = Math.min(len, (int) Math.min(((long) Integer.MAX_VALUE), left));
		int amountRead = in.read(b, offset, max);
		if (amountRead == -1) {
			throw endedEarly();
		}
		left -= (long) amountRead;
		return amountRead;
	}

	/**
	 * Returns how many bytes are still to be read.
	 *
	 * @return the number of bytes left
	 */
	public long getRemaining() {
		return left;
	}

	private EOFException endedEarly() {
		return new EOFException("Stream ended with " + left + " bytes still expected");
	}

}
