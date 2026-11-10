package io.siggi.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTPResponse.abort() ends a response from another thread, even while a
 * write to a client that stopped reading is blocked.
 */
class AbortTest {

	private ServerSocket serverSocket;
	private final AtomicReference<HTTPResponse> response = new AtomicReference<>();
	private final AtomicLong lastWrite = new AtomicLong();
	private final AtomicReference<Throwable> writerError = new AtomicReference<>();
	private final CountDownLatch writerDone = new CountDownLatch(1);
	private final CountDownLatch closeReturned = new CountDownLatch(1);

	@BeforeEach
	void setUp() throws IOException {
		HTTPServer server = new HTTPServerBuilder().build();
		server.getResponderRegistry(null).register("/stalled", request -> {
			HTTPResponse r = request.response;
			response.set(r);
			r.closeDelimited();
			r.setBuffered(false);
			try {
				writeForever(r);
			} catch (IOException e) {
				writerError.set(e);
				throw e;
			} finally {
				writerDone.countDown();
			}
		});
		server.getResponderRegistry(null).register("/stalled-detached", request -> {
			HTTPResponse r = request.openResponse();
			response.set(r);
			r.closeDelimited();
			r.setBuffered(false);
			Thread writer = new Thread(() -> {
				try {
					writeForever(r);
				} catch (IOException e) {
					writerError.set(e);
				} finally {
					writerDone.countDown();
					try {
						r.close();
						closeReturned.countDown();
					} catch (IOException e) {
						writerError.set(new AssertionError("close() after abort threw", e));
					}
				}
			});
			writer.start();
		});
		server.getResponderRegistry(null).register("/abort-first", request -> {
			request.response.abort();
			try {
				request.response.write("never sent".getBytes(StandardCharsets.UTF_8));
			} catch (IOException e) {
				writerError.set(e);
				throw e;
			} finally {
				writerDone.countDown();
			}
		});
		serverSocket = new ServerSocket(0);
		Thread acceptor = new Thread(() -> {
			while (!serverSocket.isClosed()) {
				try {
					server.handle(serverSocket.accept());
				} catch (IOException e) {
				}
			}
		});
		acceptor.setDaemon(true);
		acceptor.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		serverSocket.close();
	}

	private void writeForever(HTTPResponse r) throws IOException {
		byte[] chunk = new byte[65536];
		while (true) {
			r.write(chunk);
			r.flush();
			lastWrite.set(System.nanoTime());
		}
	}

	/**
	 * Waits until the response exists and its writer has made no progress for
	 * half a second, meaning it's blocked on the full send buffer.
	 */
	private void awaitBlockedWriter() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			Thread.sleep(100L);
			long last = lastWrite.get();
			if (response.get() != null && last != 0L && System.nanoTime() - last > TimeUnit.MILLISECONDS.toNanos(500)) {
				assertEquals(1L, writerDone.getCount(), "the writer stopped before abort()");
				return;
			}
		}
		throw new AssertionError("the writer never blocked");
	}

	private static String captureErrors(ThrowingRunnable action) throws Exception {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(captured, true));
		try {
			action.run();
			Thread.sleep(500L);
		} finally {
			System.setErr(originalErr);
		}
		return captured.toString();
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/**
	 * Sends the request, reads a little of the response, then stops reading
	 * until abort() has ended the blocked write. Returns the client socket,
	 * which must have been closed by the server.
	 */
	private void stallThenAbort(String path) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setReceiveBufferSize(16384);
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			InputStream in = socket.getInputStream();
			assertTrue(in.read(new byte[100]) > 0);
			awaitBlockedWriter();

			long start = System.nanoTime();
			response.get().abort();
			assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "abort() blocked");
			assertTrue(writerDone.await(2, TimeUnit.SECONDS), "the blocked write didn't fail");
			assertNotNull(writerError.get());
			assertTrue(writerError.get() instanceof IOException, String.valueOf(writerError.get()));

			assertConnectionEnds(in);
		}
	}

	private static void assertConnectionEnds(InputStream in) {
		byte[] buffer = new byte[65536];
		try {
			while (in.read(buffer) != -1) {
			}
		} catch (IOException e) {
			// A reset is how an aborted connection is meant to end
			assertFalse(e instanceof java.net.SocketTimeoutException, "the server didn't close the connection");
		}
	}

	@Test
	void abortUnblocksTheHandlerThreadsWrite() throws Exception {
		String errors = captureErrors(() -> stallThenAbort("/stalled"));
		assertFalse(errors.contains("error while handling"), errors);
	}

	@Test
	void abortUnblocksAWriterOnAnotherThread() throws Exception {
		String errors = captureErrors(() -> stallThenAbort("/stalled-detached"));
		assertFalse(errors.contains("error while handling"), errors);
		assertTrue(closeReturned.await(2, TimeUnit.SECONDS), "close() after abort() didn't return");
		assertTrue(writerError.get() instanceof IOException, String.valueOf(writerError.get()));
	}

	@Test
	void abortBeforeTheHeadersSendsNothing() throws Exception {
		String errors = captureErrors(() -> {
			try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("GET /abort-first HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				InputStream in = socket.getInputStream();
				try {
					assertEquals(-1, in.read());
				} catch (IOException e) {
					assertFalse(e instanceof java.net.SocketTimeoutException, "the server didn't close the connection");
				}
			}
		});
		assertTrue(writerDone.await(2, TimeUnit.SECONDS));
		assertTrue(writerError.get() instanceof IOException, String.valueOf(writerError.get()));
		assertFalse(errors.contains("error while handling"), errors);
	}
}
