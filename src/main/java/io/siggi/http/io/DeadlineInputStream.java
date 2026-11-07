package io.siggi.http.io;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Wraps a socket's InputStream and, while a deadline is set, makes every read
 * time out once the deadline has passed, no matter how often bytes trickle in.
 * The socket's own read timeout still applies if it's shorter.
 */
public final class DeadlineInputStream extends InputStream {

	private final InputStream in;
	private final Socket socket;
	private boolean hasDeadline = false;
	private long deadline;

	public DeadlineInputStream(InputStream in, Socket socket) {
		this.in = in;
		this.socket = socket;
	}

	/**
	 * Set a deadline the given number of milliseconds from now, unless one is
	 * already set.
	 *
	 * @param timeout milliseconds from now, or 0 or less for no deadline
	 */
	public void startDeadline(long timeout) {
		if (hasDeadline || timeout <= 0L) {
			return;
		}
		hasDeadline = true;
		deadline = System.nanoTime() + timeout * 1000000L;
	}

	public void clearDeadline() {
		hasDeadline = false;
	}

	/**
	 * Clamps the socket timeout to the time left before the deadline, and
	 * returns the previous timeout so it can be restored, or -1 if there's no
	 * deadline.
	 */
	private int applyDeadline() throws IOException {
		if (!hasDeadline) {
			return -1;
		}
		long remaining = (deadline - System.nanoTime()) / 1000000L;
		if (remaining <= 0L) {
			throw new SocketTimeoutException("Request header deadline exceeded");
		}
		int previous = socket.getSoTimeout();
		int timeout = (int) Math.min(remaining, Integer.MAX_VALUE);
		if (previous > 0) {
			timeout = Math.min(timeout, previous);
		}
		socket.setSoTimeout(timeout);
		return previous;
	}

	private void restore(int previous) throws IOException {
		if (previous != -1) {
			socket.setSoTimeout(previous);
		}
	}

	@Override
	public int read() throws IOException {
		int previous = applyDeadline();
		try {
			return in.read();
		} finally {
			restore(previous);
		}
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int previous = applyDeadline();
		try {
			return in.read(b, off, len);
		} finally {
			restore(previous);
		}
	}

	@Override
	public long skip(long n) throws IOException {
		int previous = applyDeadline();
		try {
			return in.skip(n);
		} finally {
			restore(previous);
		}
	}

	@Override
	public int available() throws IOException {
		return in.available();
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
