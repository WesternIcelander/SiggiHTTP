package io.siggi.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which peers may set the forwarding headers: Cloudflare and loopback,
 * besides the trusted IPs.
 */
class TrustedProxyTest {

	private static final String CLOUDFLARE_PEER = "203.0.113.5";
	private static final String HEADERS = "X-Forwarded-For: 198.51.100.7\r\nX-Forwarded-Proto: https\r\nX-Forwarded-Host: forwarded.example\r\n";

	private ServerSocket serverSocket;
	private HTTPServer server;

	@AfterEach
	void tearDown() throws IOException {
		if (serverSocket != null) {
			serverSocket.close();
		}
	}

	/**
	 * Starts a server that sees every connection as coming from the peer,
	 * and reports what it made of the forwarding headers.
	 */
	@SuppressWarnings("removal") // request.host is the only place X-Forwarded-Host shows
	private void startServer(String peer) throws IOException {
		server = new HTTPServerBuilder().build();
		// stands in for cloudflare-ips.txt
		server.cloudflareRanges = CLOUDFLARE_PEER::equals;
		server.getResponderRegistry(null).register("/", request ->
				request.response.send(request.getIPAddress() + " " + request.isSecure() + " " + request.host));
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

	private String get(String extraHeaders) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: direct.example\r\n" + extraHeaders + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
	void cloudflareIsTrustedByDefault() throws IOException {
		startServer(CLOUDFLARE_PEER);
		assertEquals("198.51.100.7 true direct.example", get(HEADERS));
		assertEquals("198.51.100.8 false direct.example", get("CF-Connecting-IP: 198.51.100.8\r\n"));
	}

	@Test
	void clearTrustedIPsStopsTrustingCloudflare() throws IOException {
		startServer(CLOUDFLARE_PEER);
		server.clearTrustedIPs();
		assertEquals(CLOUDFLARE_PEER + " false direct.example", get(HEADERS));
		assertEquals(CLOUDFLARE_PEER + " false direct.example", get("CF-Connecting-IP: 198.51.100.8\r\n"));
	}

	@Test
	void cloudflareCanBeTrustedAgainWithTrustIP() throws IOException {
		startServer(CLOUDFLARE_PEER);
		server.clearTrustedIPs();
		server.trustIP(CLOUDFLARE_PEER);
		assertEquals("198.51.100.8 false direct.example", get("CF-Connecting-IP: 198.51.100.8\r\n"));
	}
}
