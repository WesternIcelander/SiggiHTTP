package io.siggi.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanupTaskTest {

	private ServerSocket serverSocket;
	private final List<String> ran = new CopyOnWriteArrayList<>();
	private final CountDownLatch done = new CountDownLatch(1);
	private volatile boolean ranBeforeClose;

	@BeforeEach
	void setUp() throws IOException {
		HTTPServer server = new HTTPServerBuilder().build();
		server.getResponderRegistry(null).register("/tasks", request -> {
			request.addCleanupTask(() -> ran.add("first"));
			request.addCleanupTask(() -> {
				throw new RuntimeException("cleanup task failure for the test");
			});
			request.addCleanupTask(() -> {
				ran.add("third");
				done.countDown();
			});
			request.response.send("ok");
		});
		server.getResponderRegistry(null).register("/detached", request -> {
			request.addCleanupTask(() -> {
				ran.add("detached");
				done.countDown();
			});
			HTTPResponse response = request.openResponse();
			new Thread(() -> {
				try {
					Thread.sleep(200L);
					ranBeforeClose = !ran.isEmpty();
					response.send("ok");
					response.close();
				} catch (Exception e) {
				}
			}).start();
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

	private void get(String path) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			while (socket.getInputStream().read(new byte[4096]) != -1) {
			}
		}
	}

	@Test
	void tasksRunInOrderAndAFailureDoesNotStopTheRest() throws Exception {
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(errors, true));
		try {
			get("/tasks");
			assertTrue(done.await(2, TimeUnit.SECONDS), "the last task didn't run");
		} finally {
			System.setErr(originalErr);
		}
		assertEquals(java.util.Arrays.asList("first", "third"), ran);
		assertTrue(errors.toString().contains("cleanup task failure for the test"), errors.toString());
	}

	@Test
	void tasksRunWhenADetachedResponseIsClosed() throws Exception {
		get("/detached");
		assertTrue(done.await(2, TimeUnit.SECONDS), "the task didn't run");
		assertTrue(!ranBeforeClose, "the task ran before the response was closed");
	}
}
