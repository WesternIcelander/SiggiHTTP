package io.siggi.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * X-Forwarded-Proto only counts when the connection comes from a trusted
 * proxy.
 */
class ForwardedProtoTest {

	private ServerSocket serverSocket;

	@AfterEach
	void tearDown() throws IOException {
		if (serverSocket != null) {
			serverSocket.close();
		}
	}

	/**
	 * A real loopback connection that reports a different peer address, since
	 * a test client on loopback is always a trusted proxy.
	 */
	private static final class PeerAddressSocket extends Socket {
		private final Socket socket;
		private final InetAddress peer;

		PeerAddressSocket(Socket socket, InetAddress peer) {
			this.socket = socket;
			this.peer = peer;
		}

		@Override
		public InetAddress getInetAddress() {
			return peer;
		}

		@Override
		public SocketAddress getRemoteSocketAddress() {
			return new InetSocketAddress(peer, socket.getPort());
		}

		@Override
		public SocketAddress getLocalSocketAddress() {
			return socket.getLocalSocketAddress();
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return socket.getInputStream();
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			return socket.getOutputStream();
		}

		@Override
		public synchronized void setSoTimeout(int timeout) throws SocketException {
			socket.setSoTimeout(timeout);
		}

		@Override
		public synchronized int getSoTimeout() throws SocketException {
			return socket.getSoTimeout();
		}

		@Override
		public void setTcpNoDelay(boolean on) throws SocketException {
			socket.setTcpNoDelay(on);
		}

		@Override
		public void setSoLinger(boolean on, int linger) throws SocketException {
			socket.setSoLinger(on, linger);
		}

		@Override
		public void shutdownOutput() throws IOException {
			socket.shutdownOutput();
		}

		@Override
		public synchronized void close() throws IOException {
			socket.close();
		}
	}

	/**
	 * Starts a server that sees every connection as coming from the given
	 * peer, and trusts only the given ranges.
	 */
	private void startServer(String peer, String... trusted) throws IOException {
		HTTPServer server = new HTTPServerBuilder().build();
		for (io.siggi.http.iphelper.IP ip : server.getTrustedIPs()) {
			server.untrustIP(ip.toString());
		}
		for (String range : trusted) {
			server.trustIP(range);
		}
		server.getResponderRegistry(null).register("/", request -> request.response.send(String.valueOf(request.isSecure())));
		InetAddress peerAddress = InetAddress.getByName(peer);
		serverSocket = new ServerSocket(0);
		Thread acceptor = new Thread(() -> {
			while (!serverSocket.isClosed()) {
				try {
					server.handle(new PeerAddressSocket(serverSocket.accept(), peerAddress));
				} catch (IOException e) {
				}
			}
		});
		acceptor.setDaemon(true);
		acceptor.start();
	}

	private String isSecure(String forwardedProto) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(5000);
			String header = forwardedProto == null ? "" : "X-Forwarded-Proto: " + forwardedProto + "\r\n";
			socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: x\r\n" + header + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int amount;
			while ((amount = socket.getInputStream().read(buffer)) != -1) {
				received.write(buffer, 0, amount);
			}
			String response = new String(received.toByteArray(), StandardCharsets.ISO_8859_1);
			return response.substring(response.indexOf("\r\n\r\n") + 4);
		}
	}

	@Test
	void untrustedClientCannotClaimHttps() throws IOException {
		startServer("203.0.113.5", "10.0.0.0/8");
		assertEquals("false", isSecure("https"));
		assertEquals("false", isSecure(null));
	}

	@Test
	void trustedProxyCanReportHttps() throws IOException {
		startServer("10.1.2.3", "10.0.0.0/8");
		assertEquals("true", isSecure("https"));
		assertEquals("true", isSecure("HTTPS"));
		assertEquals("false", isSecure("http"));
		assertEquals("false", isSecure(null));
	}

	@Test
	void loopbackProxyCanReportHttps() throws IOException {
		startServer("127.0.0.1");
		assertEquals("true", isSecure("https"));
		assertEquals("false", isSecure("http"));
	}
}
