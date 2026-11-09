package io.siggi.http;

/**
 * Customises the error responses the server builds itself, before or
 * outside a responder: 400 Bad Request for a malformed request, 408 Request
 * Timeout, 413 Payload Too Large, 414 URI Too Long, and 500 Internal Server
 * Error when a responder throws. Use it to add headers, such as a
 * Content-Security-Policy, or to replace the page.
 * <p>
 * Set it with {@link HTTPServer#setErrorPageHandler(ErrorPageHandler)}. It
 * may be called on any connection's thread, at the same time for different
 * connections. If it throws, the server logs the exception and sends its
 * built-in page.
 */
@FunctionalInterface
public interface ErrorPageHandler {

	/**
	 * Customises an error response before it's sent.
	 *
	 * @param page the response, which starts with the built-in page
	 * @throws Exception to send the built-in page instead
	 */
	void handle(ErrorPage page) throws Exception;
}
