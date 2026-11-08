package io.siggi.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RangeTest {

	private static final long LENGTH = 100000L;

	private static void assertRange(String header, long first, long last) {
		assertArrayEquals(new long[]{first, last}, HTTPResponse.parseRange(header, LENGTH), header);
	}

	private static void assertNotSatisfiable(String header) {
		assertSame(HTTPResponse.RANGE_NOT_SATISFIABLE, HTTPResponse.parseRange(header, LENGTH), header);
	}

	private static void assertWholeFile(String header) {
		assertNull(HTTPResponse.parseRange(header, LENGTH), header);
	}

	@Test
	void rangeInsideFile() {
		assertRange("bytes=0-99", 0L, 99L);
		assertRange("bytes=500-500", 500L, 500L);
		assertRange("bytes=99999-99999", 99999L, 99999L);
	}

	@Test
	void openEndedRange() {
		assertRange("bytes=99000-", 99000L, 99999L);
		assertRange("bytes=0-", 0L, 99999L);
	}

	@Test
	void suffixRange() {
		assertRange("bytes=-10", 99990L, 99999L);
		assertRange("bytes=-100000", 0L, 99999L);
	}

	@Test
	void suffixLongerThanFileIsWholeFile() {
		assertRange("bytes=-200000", 0L, 99999L);
		assertRange("bytes=-99999999999999999999999", 0L, 99999L);
	}

	@Test
	void lastPositionPastEndIsClamped() {
		assertRange("bytes=500-1000000", 500L, 99999L);
		assertRange("bytes=500-99999999999999999999999", 500L, 99999L);
	}

	@Test
	void notSatisfiable() {
		assertNotSatisfiable("bytes=100000-");
		assertNotSatisfiable("bytes=100010-100020");
		assertNotSatisfiable("bytes=-0");
		assertNotSatisfiable("bytes=99999999999999999999999-");
	}

	@Test
	void emptyFile() {
		assertSame(HTTPResponse.RANGE_NOT_SATISFIABLE, HTTPResponse.parseRange("bytes=0-", 0L));
		assertSame(HTTPResponse.RANGE_NOT_SATISFIABLE, HTTPResponse.parseRange("bytes=-10", 0L));
	}

	@Test
	void whitespaceAndUnitCase() {
		assertRange("bytes= 0-99 ", 0L, 99L);
		assertRange("Bytes=0-99", 0L, 99L);
	}

	@Test
	void malformedFallsBackToWholeFile() {
		assertWholeFile("bytes=5-2");
		assertWholeFile("bytes=abc");
		assertWholeFile("bytes=500");
		assertWholeFile("bytes=-");
		assertWholeFile("bytes=a-b");
		assertWholeFile("bytes=+5-10");
		assertWholeFile("bytes=-+5");
		assertWholeFile("bytes=--5");
		assertWholeFile("bytes");
		assertWholeFile("items=0-5");
	}

	@Test
	void multipleRangesFallBackToWholeFile() {
		assertWholeFile("bytes=0-1,5-6");
		assertWholeFile("bytes=0-1, 5-6");
	}
}
