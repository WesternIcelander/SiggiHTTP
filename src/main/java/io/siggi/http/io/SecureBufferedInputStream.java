package io.siggi.http.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that is possible to rewind with mark() with the use of a
 * buffer. We call this the SecureBufferedInputStream because not only is it
 * buffered, it also has a security feature where you can erase the buffer after
 * you've been using it to handle sensitive information like a password.
 *
 * @author Siggi
 */
public class SecureBufferedInputStream extends InputStream {

	public SecureBufferedInputStream(InputStream in) {
		this(in, 8192);
	}

	public SecureBufferedInputStream(InputStream in, int bufferSize) {
		if (in == null) {
			throw new NullPointerException();
		}
		if (bufferSize <= 0) {
			// a zero-length buffer could never grow by doubling
			throw new IllegalArgumentException("Buffer size must be positive: " + bufferSize);
		}
		this.in = in;
		this.buffer = new byte[bufferSize];
	}

	private static final int maxBufferSize = Integer.MAX_VALUE - 8;

	private InputStream in;
	private byte[] buffer;
	private int readPos = 0;
	private int writePos = 0;
	private int markPos = -1;
	private int markLimit = 0;

	private InputStream getIn() {
		InputStream i = in;
		if (i == null) {
			throw new NullPointerException("Stream closed");
		}
		return i;
	}

	private byte[] getBuffer() {
		byte[] b = buffer;
		if (b == null) {
			throw new NullPointerException("Stream closed");
		}
		return b;
	}

	private void fill() throws IOException {
		// fill() should only be called if readPos == writePos
		// and so fill() will assume it is so
		if (markPos < 0) {
			writePos = 0;
		} else if (writePos >= buffer.length) {
			if (markPos > 0) {
				// slide the buffer so that the mark position is at the beginning of the buffer
				System.arraycopy(buffer, markPos, buffer, 0, buffer.length - markPos);
				writePos -= markPos;
				markPos = 0;
			} else if (buffer.length >= markLimit) {
				// discard the mark, we've kept it longer than we needed
				markPos = -1;
				markLimit = 0;
				writePos = 0;
			} else if (buffer.length >= maxBufferSize) {
				throw new OutOfMemoryError("Required array size too large");
			} else {
				// double the buffer, without going over the maximum
				int newSize = buffer.length > maxBufferSize / 2 ? maxBufferSize : buffer.length * 2;
				byte[] newBuffer = new byte[newSize];
				System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
				// erase the old buffer
				for (int i = 0; i < buffer.length; i++) {
					buffer[i] = (byte) 0;
				}
				buffer = newBuffer;
			}
		}
		readPos = writePos;
		int amountRead = getIn().read(buffer, writePos, buffer.length - writePos);
		if (amountRead > 0) {
			writePos += amountRead;
		}
	}

	@Override
	public int available() throws IOException {
		int available = getIn().available();
		int localAvailable = writePos - readPos;
		return available > (Integer.MAX_VALUE - localAvailable)
				? Integer.MAX_VALUE
				: localAvailable + available;
	}

	@Override
	public int read() throws IOException {
		if (readPos >= writePos) {
			fill();
			if (readPos >= writePos) {
				return -1;
			}
		}
		return getBuffer()[readPos++] & 0xff;
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public int read(byte[] b, int o, int l) throws IOException {
		getIn();
		if (l == 0) {
			return 0;
		}
		if (readPos >= writePos) {
			fill();
			if (readPos >= writePos) {
				return -1;
			}
		}
		int amountToRead = Math.min(l, writePos - readPos);
		System.arraycopy(buffer, readPos, b, o, amountToRead);
		readPos += amountToRead;
		return amountToRead;
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void mark(int limit) {
		this.markLimit = limit;
		this.markPos = readPos;
	}

	@Override
	public void reset() throws IOException {
		getIn();
		if (markPos < 0) {
			throw new IOException("Resetting to invalid mark");
		}
		readPos = markPos;
	}

	@Override
	public long skip(long n) throws IOException {
		getIn();
		if (n <= 0) {
			return 0;
		}
		long avail = writePos - readPos;

		if (avail <= 0) {
			if (markPos < 0) {
				return getIn().skip(n);
			}

			fill();
			avail = writePos - readPos;
			if (avail <= 0) {
				return 0;
			}
		}

		long skipped = (avail < n) ? avail : n;
		readPos += skipped;
		return skipped;
	}

	@Override
	public void close() throws IOException {
		byte[] buf = buffer;
		buffer = null;
		if (buf != null) {
			for (int i = 0; i < buf.length; i++) {
				buf[i] = (byte) 0;
			}
		}
		InputStream i = in;
		in = null;
		if (i != null) {
			i.close();
		}
	}

	/**
	 * Discard the mark so that it can be erased.
	 */
	public void discardMark() {
		markPos = -1;
		markLimit = 0;
	}

	/**
	 * Erase the unused space on the buffer by writing 0's to the unused parts
	 * of the buffer. This method will also discard any mark().
	 */
	public void eraseFreeSpace() {
		markPos = -1;
		markLimit = 0;
		byte[] buf = buffer;
		if (buf == null) {
			return;
		}
		for (int i = 0; i < readPos; i++) {
			buf[i] = (byte) 0;
		}
		for (int i = writePos; i < buf.length; i++) {
			buf[i] = (byte) 0;
		}
	}
}
