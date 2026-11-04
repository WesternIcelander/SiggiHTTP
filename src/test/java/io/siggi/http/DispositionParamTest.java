package io.siggi.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DispositionParamTest {

	@Test
	void nameAfterFilenameIsFound() {
		String header = "form-data; filename=\"a.txt\"; name=\"upload\"";
		assertEquals("upload", HTTPHandler.dispositionParam(header, "name"));
		assertEquals("a.txt", HTTPHandler.dispositionParam(header, "filename"));
	}

	@Test
	void nameBeforeFilenameIsFound() {
		String header = "form-data; name=\"upload\"; filename=\"a.txt\"";
		assertEquals("upload", HTTPHandler.dispositionParam(header, "name"));
		assertEquals("a.txt", HTTPHandler.dispositionParam(header, "filename"));
	}

	@Test
	void filenameAloneIsNotAName() {
		assertNull(HTTPHandler.dispositionParam("form-data; filename=\"a.txt\"", "name"));
	}

	@Test
	void missingFilenameIsNull() {
		assertNull(HTTPHandler.dispositionParam("form-data; name=\"field\"", "filename"));
	}

	@Test
	void unquotedValuesAndCaseAreHandled() {
		String header = "form-data;NAME=field ;filename=b.txt";
		assertEquals("field", HTTPHandler.dispositionParam(header, "name"));
		assertEquals("b.txt", HTTPHandler.dispositionParam(header, "filename"));
	}

	@Test
	void semicolonInsideQuotesIsKept() {
		String header = "form-data; name=\"f\"; filename=\"a;name=x.txt\"";
		assertEquals("f", HTTPHandler.dispositionParam(header, "name"));
		assertEquals("a;name=x.txt", HTTPHandler.dispositionParam(header, "filename"));
	}
}
