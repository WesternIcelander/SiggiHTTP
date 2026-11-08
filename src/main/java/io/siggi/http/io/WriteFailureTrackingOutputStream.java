package io.siggi.http.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps a socket's OutputStream and remembers whether a write or flush has
 * failed, so a failure caused by the client going away can be told apart from
 * other errors.
 */
public final class WriteFailureTrackingOutputStream extends OutputStream {

	private final OutputStream out;
	private volatile boolean failed = false;

	public WriteFailureTrackingOutputStream(OutputStream out) {
		this.out = out;
	}

	/**
	 * Returns whether a write or flush has thrown an IOException.
	 *
	 * @return true if writing to the underlying stream has failed
	 */
	public boolean hasFailed() {
		return failed;
	}

	@Override
	public void write(int b) throws IOException {
		try {
			out.write(b);
		} catch (IOException e) {
			failed = true;
			throw e;
		}
	}

	@Override
	public void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		try {
			out.write(b, off, len);
		} catch (IOException e) {
			failed = true;
			throw e;
		}
	}

	@Override
	public void flush() throws IOException {
		try {
			out.flush();
		} catch (IOException e) {
			failed = true;
			throw e;
		}
	}

	@Override
	public void close() throws IOException {
		out.close();
	}
}
