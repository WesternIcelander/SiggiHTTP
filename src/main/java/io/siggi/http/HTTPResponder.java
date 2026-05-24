package io.siggi.http;

/**
 * Page generators should implement this interface
 */
@FunctionalInterface
public interface HTTPResponder {

	/**
	 * This method is called when a request is received and the request is included in this responder's scope. If the
	 * responder intends to pass the request to another thread, it should call {@link HTTPRequest#makeCleanupExplicit()}
	 * before returning.
	 *
	 * @param request the request to handle
	 * @throws Exception if something goes wrong
	 */
	public void respond(HTTPRequest request) throws Exception;

	/**
	 * If no other responder responds, this method gets called.
	 *
	 * @param request the request to handle
	 * @throws Exception if something goes wrong
	 */
	default public void respond404(HTTPRequest request) throws Exception {
	};
}
