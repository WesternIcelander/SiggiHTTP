package io.siggi.http.util;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParseHeaderArgumentsTest {

	private static String filename(String header) {
		Map<String, String> args = Util.parseHeaderArguments(header);
		return args.get("filename");
	}

	@Test
	void invalidEscapeIsLiteralPercent() {
		assertEquals("100%.txt", filename("form-data; name=\"f\"; filename=\"100%.txt\""));
		assertEquals("100%#a.txt", filename("form-data; name=\"f\"; filename=\"100%#a.txt\""));
		assertEquals("a%zz", filename("form-data; filename=\"a%zz\""));
	}

	@Test
	void trailingPercentIsKept() {
		assertEquals("a%", filename("form-data; filename=a%"));
		assertEquals("a%4", filename("form-data; filename=a%4"));
	}

	@Test
	void validEscapeIsDecodedAsUtf8() {
		assertEquals("a b", filename("form-data; filename=\"a%20b\""));
		assertEquals("é.txt", filename("form-data; filename=\"%C3%A9.txt\""));
	}

	@Test
	void otherArgumentsAreUnaffected() {
		Map<String, String> args = Util.parseHeaderArguments("form-data; name=\"f\"; filename=\"100%.txt\"");
		assertEquals("f", args.get("name"));
	}
}
