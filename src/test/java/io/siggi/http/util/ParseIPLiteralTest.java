package io.siggi.http.util;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParseIPLiteralTest {

	private static String parse(String address) {
		InetAddress parsed = Util.parseIPLiteral(address);
		return parsed == null ? null : parsed.getHostAddress();
	}

	@Test
	void ipv4() {
		assertEquals("203.0.113.7", parse("203.0.113.7"));
		assertEquals("0.0.0.0", parse(" 0.0.0.0 "));
		assertEquals("255.255.255.255", parse("255.255.255.255"));
	}

	@Test
	void ipv6() {
		assertEquals("0:0:0:0:0:0:0:1", parse("::1"));
		assertEquals("0:0:0:0:0:0:0:1", parse("[::1]"));
		assertEquals("0:0:0:0:0:0:0:0", parse("::"));
		assertEquals("2001:db8:0:0:0:0:0:1", parse("2001:db8::1"));
		assertEquals("2001:db8:0:0:0:0:0:0", parse("2001:DB8::"));
		assertEquals("2001:db8:1:2:3:4:5:6", parse("2001:db8:1:2:3:4:5:6"));
		assertEquals("0:0:0:0:0:0:cb00:7107", parse("::203.0.113.7"));
		assertEquals("203.0.113.7", parse("::ffff:203.0.113.7"));
	}

	@Test
	void hostnamesAreRejected() {
		assertNull(parse("example.com"));
		assertNull(parse("localhost"));
		assertNull(parse("unknown"));
		assertNull(parse("1.2.3.com"));
	}

	@Test
	void malformedAddressesAreRejected() {
		assertNull(parse(null));
		assertNull(parse(""));
		assertNull(parse("1.2.3"));
		assertNull(parse("1.2.3.4.5"));
		assertNull(parse("256.1.1.1"));
		assertNull(parse("1..2.3"));
		assertNull(parse("-1.2.3.4"));
		assertNull(parse("1.2.3.4:80"));
		assertNull(parse("[1.2.3.4]"));
		assertNull(parse("1:2:3:4:5:6:7"));
		assertNull(parse("1:2:3:4:5:6:7:8:9"));
		assertNull(parse("1:2:3:4::5:6:7:8"));
		assertNull(parse("1::2::3"));
		assertNull(parse("1:::2"));
		assertNull(parse("12345::"));
		assertNull(parse("fe80::1%eth0"));
		assertNull(parse("::1.2.3.4:5"));
		assertNull(parse("１.2.3.4"));
	}
}
