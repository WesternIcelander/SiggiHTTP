package io.siggi.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An error response the server is about to send, passed to an
 * {@link ErrorPageHandler}. Its body starts as the server's built-in page.
 * <p>
 * The status code can't be changed, and neither can the headers that frame
 * the response ({@code Content-Length}, {@code Transfer-Encoding},
 * {@code Connection} and {@code Keep-Alive}): the server sets those, and
 * closes the connection after an error response. {@code Content-Type} goes
 * with the body, so it's set with {@link #setBody(byte[], String)}.
 */
public final class ErrorPage {

	private final int statusCode;
	private final String reasonPhrase;
	private final String host;
	private final Throwable exception;
	private final Map<String, List<String>> headers = new LinkedHashMap<>();
	private byte[] body;
	private String contentType;

	ErrorPage(int statusCode, String reasonPhrase, String host, Throwable exception, String defaultHtml) {
		this.statusCode = statusCode;
		this.reasonPhrase = reasonPhrase;
		this.host = host;
		this.exception = exception;
		if (defaultHtml == null) {
			body = new byte[0];
			contentType = null;
		} else {
			setBody(defaultHtml);
		}
	}

	/**
	 * Returns the status code, such as 400.
	 *
	 * @return the status code
	 */
	public int getStatusCode() {
		return statusCode;
	}

	/**
	 * Returns the reason phrase that goes with the status code, such as
	 * "Bad Request".
	 *
	 * @return the reason phrase
	 */
	public String getReasonPhrase() {
		return reasonPhrase;
	}

	/**
	 * Returns the host the request was for, or null if the request didn't get
	 * far enough to say, for example when it timed out or its request line was
	 * too long.
	 *
	 * @return the host, or null
	 */
	public String getHost() {
		return host;
	}

	/**
	 * Returns the exception a responder threw, for a 500 response, or null.
	 *
	 * @return the exception, or null
	 */
	public Throwable getException() {
		return exception;
	}

	/**
	 * Sets a header, replacing any values it already has.
	 *
	 * @param name the header name
	 * @param value the value
	 * @throws IllegalArgumentException if the header is Content-Type or one
	 * the server controls, or the name or value contains a line break
	 */
	public void setHeader(String name, String value) {
		checkHeader(name, value);
		removeHeader(name);
		List<String> values = new ArrayList<>();
		values.add(value);
		headers.put(name, values);
	}

	/**
	 * Adds a value to a header, keeping any values it already has.
	 *
	 * @param name the header name
	 * @param value the value
	 * @throws IllegalArgumentException if the header is Content-Type or one
	 * the server controls, or the name or value contains a line break
	 */
	public void addHeader(String name, String value) {
		checkHeader(name, value);
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				entry.getValue().add(value);
				return;
			}
		}
		List<String> values = new ArrayList<>();
		values.add(value);
		headers.put(name, values);
	}

	/**
	 * Removes a header that was set or added on this page.
	 *
	 * @param name the header name
	 */
	public void removeHeader(String name) {
		headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
	}

	/**
	 * Returns the headers set or added on this page so far.
	 *
	 * @return an unmodifiable view of the headers
	 */
	public Map<String, List<String>> getHeaders() {
		return Collections.unmodifiableMap(headers);
	}

	/**
	 * Replaces the body with an HTML page, sent as
	 * {@code text/html; charset=utf-8}.
	 *
	 * @param html the page
	 */
	public void setBody(String html) {
		setBody(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
	}

	/**
	 * Replaces the body.
	 *
	 * @param body the body, or an empty array for none
	 * @param contentType the Content-Type, or null to send none
	 */
	public void setBody(byte[] body, String contentType) {
		if (body == null) {
			throw new NullPointerException("body");
		}
		if (contentType != null) {
			rejectLineBreaks(contentType);
		}
		this.body = body.clone();
		this.contentType = contentType;
	}

	/**
	 * Returns a copy of the body.
	 *
	 * @return the body
	 */
	public byte[] getBody() {
		return body.clone();
	}

	/**
	 * Returns the Content-Type of the body, or null if there isn't one.
	 *
	 * @return the Content-Type, or null
	 */
	public String getContentType() {
		return contentType;
	}

	byte[] body() {
		return body;
	}

	private static void checkHeader(String name, String value) {
		if (name == null || value == null) {
			throw new NullPointerException("Header name and value can't be null");
		}
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Header name can't be empty");
		}
		rejectLineBreaks(name);
		rejectLineBreaks(value);
		if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")
				|| name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Keep-Alive")) {
			throw new IllegalArgumentException(name + " is set by the server");
		}
		if (name.equalsIgnoreCase("Content-Type")) {
			throw new IllegalArgumentException("Set Content-Type with setBody()");
		}
	}

	private static void rejectLineBreaks(String s) {
		if (s.indexOf('\r') != -1 || s.indexOf('\n') != -1) {
			throw new IllegalArgumentException("Headers can't contain line breaks");
		}
	}
}
