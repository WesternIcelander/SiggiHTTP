package io.siggi.http;

import io.siggi.http.util.CaseInsensitiveHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestBodyLengthTest {

	private static Map<String, List<String>> headers(String... keysAndValues) {
		Map<String, List<String>> headers = new CaseInsensitiveHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			List<String> values = headers.get(keysAndValues[i]);
			if (values == null) {
				headers.put(keysAndValues[i], values = new ArrayList<>());
			}
			values.add(keysAndValues[i + 1]);
		}
		return headers;
	}

	private static void assertRejected(String... keysAndValues) {
		assertThrows(IllegalArgumentException.class, () -> HTTPHandler.getRequestBodyLength(headers(keysAndValues)));
	}

	@Test
	void noBody() {
		assertEquals(-1L, HTTPHandler.getRequestBodyLength(headers()));
	}

	@Test
	void contentLength() {
		assertEquals(42L, HTTPHandler.getRequestBodyLength(headers("Content-Length", "42")));
		assertEquals(0L, HTTPHandler.getRequestBodyLength(headers("content-length", "0")));
	}

	@Test
	void repeatedIdenticalContentLengthIsAccepted() {
		assertEquals(42L, HTTPHandler.getRequestBodyLength(headers("Content-Length", "42", "Content-Length", "42")));
		assertEquals(42L, HTTPHandler.getRequestBodyLength(headers("Content-Length", "42, 42")));
	}

	@Test
	void conflictingContentLengthIsRejected() {
		assertRejected("Content-Length", "42", "Content-Length", "7");
		assertRejected("Content-Length", "42, 7");
	}

	@Test
	void malformedContentLengthIsRejected() {
		assertRejected("Content-Length", "-5");
		assertRejected("Content-Length", "+5");
		assertRejected("Content-Length", "5a");
		assertRejected("Content-Length", "");
		assertRejected("Content-Length", "42,");
		assertRejected("Content-Length", "99999999999999999999");
	}

	@Test
	void chunkedIsCaseInsensitive() {
		assertEquals(HTTPHandler.CHUNKED_BODY, HTTPHandler.getRequestBodyLength(headers("Transfer-Encoding", "chunked")));
		assertEquals(HTTPHandler.CHUNKED_BODY, HTTPHandler.getRequestBodyLength(headers("transfer-encoding", "Chunked")));
		assertEquals(HTTPHandler.CHUNKED_BODY, HTTPHandler.getRequestBodyLength(headers("Transfer-Encoding", " CHUNKED ")));
	}

	@Test
	void unsupportedTransferCodingsAreRejected() {
		assertRejected("Transfer-Encoding", "gzip, chunked");
		assertRejected("Transfer-Encoding", "gzip", "Transfer-Encoding", "chunked");
		assertRejected("Transfer-Encoding", "chunked, gzip");
		assertRejected("Transfer-Encoding", "chunked, chunked");
		assertRejected("Transfer-Encoding", "identity");
		assertRejected("Transfer-Encoding", "");
	}

	@Test
	void transferEncodingWithContentLengthIsRejected() {
		assertRejected("Transfer-Encoding", "chunked", "Content-Length", "42");
		assertRejected("Content-Length", "0", "Transfer-Encoding", "chunked");
	}
}
