package io.siggi.http;

import io.siggi.http.iphelper.IP;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedIPTest {

	private static HTTPServer serverTrusting(String... ranges) {
		HTTPServer server = new HTTPServerBuilder().build();
		for (IP ip : server.getTrustedIPs()) {
			server.untrustIP(ip.toString());
		}
		for (String range : ranges) {
			server.trustIP(range);
		}
		return server;
	}

	@Test
	void defaultRangesAreTrustedInFull() {
		HTTPServer server = new HTTPServerBuilder().build();
		assertTrue(server.isIPTrusted("10.0.0.1"));
		assertTrue(server.isIPTrusted("10.128.0.0"));
		assertTrue(server.isIPTrusted("10.255.255.255"));
		assertTrue(server.isIPTrusted("172.16.0.1"));
		assertTrue(server.isIPTrusted("172.31.255.255"));
		assertTrue(server.isIPTrusted("192.168.0.1"));
		assertTrue(server.isIPTrusted("192.168.200.1"));
		assertTrue(server.isIPTrusted("127.0.0.1"));
	}

	@Test
	void addressesOutsideDefaultRangesAreNotTrusted() {
		HTTPServer server = new HTTPServerBuilder().build();
		assertFalse(server.isIPTrusted("11.0.0.1"));
		assertFalse(server.isIPTrusted("172.15.255.255"));
		assertFalse(server.isIPTrusted("172.32.0.0"));
		assertFalse(server.isIPTrusted("192.169.0.1"));
		assertFalse(server.isIPTrusted("127.0.0.2"));
		assertFalse(server.isIPTrusted("8.8.8.8"));
	}

	@Test
	void ipv6RangeIsTrustedInFull() {
		HTTPServer server = serverTrusting("2001:db8::/32");
		assertTrue(server.isIPTrusted("2001:db8::1"));
		assertTrue(server.isIPTrusted("2001:db8:7fff::1"));
		assertTrue(server.isIPTrusted("2001:db8:8000::1"));
		assertTrue(server.isIPTrusted("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"));
		assertFalse(server.isIPTrusted("2001:db9::1"));
		assertFalse(server.isIPTrusted("10.0.0.1"));
	}

	@Test
	void rangeWithHostBitsSetCoversTheWholeRange() {
		HTTPServer server = serverTrusting("100.64.1.2/10");
		assertTrue(server.isIPTrusted("100.64.0.1"));
		assertTrue(server.isIPTrusted("100.127.255.255"));
		assertFalse(server.isIPTrusted("100.128.0.1"));
	}

	@Test
	void zeroPrefixTrustsEverything() {
		HTTPServer server = serverTrusting("0.0.0.0/0", "::/0");
		assertTrue(server.isIPTrusted("1.2.3.4"));
		assertTrue(server.isIPTrusted("255.255.255.255"));
		assertTrue(server.isIPTrusted("2606:4700::1"));
	}

	@Test
	void singleAddressIsTrustedExactly() {
		HTTPServer server = serverTrusting("203.0.113.5");
		assertTrue(server.isIPTrusted("203.0.113.5"));
		assertFalse(server.isIPTrusted("203.0.113.4"));
		assertFalse(server.isIPTrusted("203.0.113.6"));
	}

	@Test
	void ipv6ScopeIdIsIgnored() {
		HTTPServer server = serverTrusting("fe80::/10");
		assertTrue(server.isIPTrusted("fe80:0:0:0:0:0:0:1%en0"));
		assertTrue(server.isIPTrusted("fe80::1%25"));
		assertFalse(serverTrusting().isIPTrusted("fe80:0:0:0:0:0:0:1%en0"));
	}

	@Test
	void invalidAddressThrows() {
		assertThrows(IllegalArgumentException.class, () -> serverTrusting().isIPTrusted("not-an-ip"));
	}
}
