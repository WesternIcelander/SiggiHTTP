package io.siggi.http;

import io.siggi.http.exception.EndResponse;
import io.siggi.http.session.Session;
import io.siggi.http.session.Sessions;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * This object provides details on the request from an HTTP client.
 */
public class HTTPRequest {

	public final HTTPServer server;

	final HTTPHandler handler;
	/**
	 * The object to send responses to. This object extends
	 * <code>OutputStream</code> so you can send data here.
	 */
	public final HTTPResponse response;
	/**
	 * The HTTP request method.
	 */
	public final String method;
	/**
	 * The decoded form of the path of the requested URI. The query string is
	 * not included.
	 */
	public final String url;
	/**
	 * The decoded form of the path of the requested URI. The query string is
	 * also included.
	 */
	public final String fullUrl;
	/**
	 * The data passed in the query string. This may be content from a form
	 * submitted with the <code>GET</code> method.
	 */
	public final Map<String, String> get;
	/**
	 * The data passed in the content data. This may be content from a form
	 * submitted with the <code>POST</code> method.
	 */
	public final Map<String, String> post;
	/**
	 * The data passed in the Cookies header.
	 */
	public final Map<String, String> cookies;
	/**
	 * The HTTP request headers
	 */
	public final Map<String, List<String>> headers;
	/**
	 * The files uploaded. Files will only appear in this list if form
	 * enctype="multipart/form-data" and form is submitted with POST.
	 */
	public final Map<String, UploadedFile> uploadedFiles;
	/**
	 * The data in the Host request header.
	 */
	public final String host;
	/**
	 * The page that referred the client to this page.
	 */
	public final String referer;
	/**
	 * The user agent of the browser requesting the page.
	 */
	public final String userAgent;
	/**
	 * Post data exactly as it was sent to the server.
	 * (application/x-www-form-urlencoded only)
	 */
	public final String postData;
	/**
	 * Input Stream for post data other than application/x-www-form-urlencoded
	 * and multipart/form-data. This is null if server processed postdata by
	 * itself. You SHOULD completely read from this stream before writing a
	 * response!
	 */
	public final InputStream inStream;

	/**
	 * Get the mime type of the specified extension.
	 *
	 * @param extension the extension
	 * @return the mime type
	 */
	public String getMimeType(String extension) {
		return handler.server.getMimeType(extension);
	}

	/**
	 * Get the server signature.
	 *
	 * @return the server signature
	 */
	public String getServerSignature() {
		return handler.server.getServerSignature(host);
	}

	/**
	 * Returns the User Agent's IP address
	 *
	 * @return the IP address
	 */
	public String getIPAddress() {
		return handler.ip;
	}

	/**
	 * Returns the Address that the User Agent has connected to.
	 *
	 * @return the InetSocketAddress
	 */
	public InetSocketAddress getLocalAddress() {
		return handler.getLocalAddress();
	}

	/**
	 * Returns the Map of the current connection. NOTE: Every page load is NOT
	 * guaranteed to keep the same Map. Use this for caching purposes only.
	 *
	 * @return the caching map
	 */
	public Map<String, String> getCacheMap() {
		return handler.getCacheMap();
	}

	/**
	 * Get a header.
	 *
	 * @param header the key
	 * @return the header
	 */
	public String getHeader(String header) {
		List<String> g = headers.get(header);
		if (g != null && !g.isEmpty()) {
			return g.get(0);
		}
		return null;
	}

	/**
	 * Get a list of headers.
	 *
	 * @param header the key
	 * @return the headers
	 */
	public List<String> getHeaders(String header) {
		return headers.get(header);
	}

	/**
	 * Returns true if any data at all was written back to this request.
	 *
	 * @return true if any data was written
	 */
	public boolean alreadyWrote() {
		return handler.wrote;
	}

	/**
	 * Returns the ServerSocket object that accepted connection that this
	 * request was received from.
	 *
	 * @return ServerSocket that accepted this connection
	 * @deprecated Use {@link HTTPRequest#getLocalAddress()} instead to
	 * determine the local port
	 */
	@Deprecated
	public ServerSocket getSourceSocket() {
		return handler.sourceSocket;
	}

	Session session;

	/**
	 * Get the current session.
	 *
	 * @return the session
	 */
	public Session getSession() {
		if (session == null || session.isDeleted()) {
			String sessionCookieName = handler.server.getSessionCookieName();
			if (handler.wrote) {
				throw new IllegalStateException("Headers already sent, cannot modify for session cookie!");
			}
			Sessions sessions = handler.server.getSessions();
			String sessionId = cookies.get(sessionCookieName);
			if (sessionId == null) {
				session = sessions.newSession();
				sessionId = session.getSessionId();
			} else {
				session = sessions.get(sessionId);
			}
			response.addHeader("Set-Cookie", sessionCookieName + "=" + sessionId + "; path=/; HttpOnly;" + (isSecure() ? " Secure;" : ""));
		}
		return session;
	}

	/**
	 * Get the current session if there does exist one for this connection, but
	 * does not create one if it doesn't exist.
	 *
	 * @return
	 */
	public Session getSessionIfExists() {
		if (session == null || session.isDeleted()) {
			String sessionCookieName = handler.server.getSessionCookieName();
			Sessions sessions = handler.server.getSessions();
			String sessionId = cookies.get(sessionCookieName);
			if (sessionId == null) {
				return null;
			} else {
				session = sessions.get(sessionId);
			}
		}
		return session;
	}

	/**
	 * Creates a new session and a new session ID. The old session will be
	 * deleted. This may be called when a new session is needed for security
	 * reasons, such as if a login state is changed.
	 *
	 * @return a new session
	 */
	public Session resetSession() {
		if (handler.wrote) {
			throw new IllegalStateException("Headers already sent, cannot modify for session cookie!");
		}
		Session oldSession = getSession();
		oldSession.delete();
		String sessionCookieName = handler.server.getSessionCookieName();
		Sessions sessions = handler.server.getSessions();
		session = sessions.newSession();
		response.addHeader("Set-Cookie", sessionCookieName + "=" + session.getSessionId() + "; path=/; HttpOnly;" + (isSecure() ? " Secure;" : ""));
		return session;
	}

	public boolean isSecure() {
		String fwd = getHeader("X-Forwarded-Proto");
		if (fwd != null && fwd.equalsIgnoreCase("https")) {
			return true;
		}
		return handler.isSocketSecure();
	}

	@SuppressWarnings("deprecation")
	void saveSession() {
		if (session != null) {
			session.save();
		}
	}

	HTTPRequest(HTTPHandler handler, String method, String url, String fullUrl, Map<String, String> get, Map<String, String> post, Map<String, String> cookies, Map<String, List<String>> headers, Map<String, UploadedFile> uploadedFiles, String host, String referer, String userAgent, InputStream inStream) {
		this.server = handler.server;
		this.handler = handler;
		this.response = new HTTPResponse(this);
		this.method = method;
		this.url = url;
		this.fullUrl = fullUrl;
		this.get = get;
		this.post = post;
		this.cookies = cookies;
		this.headers = headers;
		this.uploadedFiles = Collections.unmodifiableMap(uploadedFiles);
		this.host = host;
		this.referer = referer;
		this.userAgent = userAgent;
		this.postData = handler.postData;
		this.inStream = inStream;
	}

	public String getRequestEtag() {
		try {
			List<String> clientETag = headers.get("If-None-Match");
			if (clientETag != null && !clientETag.isEmpty()) {
				String theETag = clientETag.get(0);
				if (theETag.startsWith("\"") && theETag.endsWith("\"")) {
					theETag = theETag.substring(1, theETag.length() - 1);
				}
				return theETag;
			}
		} catch (Exception e) {
		}
		return null;
	}

	/**
	 * Open (or if already opened, returns the previously opened one) an HTTP response for this request. The first
	 * call must be done on the Thread the request originated on. When a response is opened using this method instead
	 * of accessing the {@link #response} field, the response will not be automatically closed, the response must be
	 * explicitly closed when it is finished. This means you can return from the
	 * {@link HTTPResponder#respond(HTTPRequest)} method before the request is complete and hand off the request to a
	 * different thread. Take care to eventually close the response and not leave it dangling if your program ends up
	 * in an error state.
	 *
	 * @return
	 */
	public HTTPResponse openResponse() throws IOException {
		if (response.isClosed()) {
			throw new IOException("Response already closed.");
		}
		handler.makeCleanupExplicit();
		return response;
	}

	/**
	 * Specify what methods are allowed. If the request is OPTIONS or a method not on the list, an appropriate response
	 * is generated and EndResponse is thrown, otherwise this method does not do anything else.
	 */
	public void allowedMethods(String... methods) throws IOException, EndResponse {
		allowedMethods(Arrays.asList(methods));
	}

	/**
	 * Specify what methods are allowed. If the request is OPTIONS or a method not on the list, an appropriate response
	 * is generated and EndResponse is thrown, otherwise this method does not do anything else.
	 */
	public void allowedMethods(List<String> methods) throws IOException, EndResponse {
		String checkedMethod = this.method;
		if (checkedMethod.equals("HEAD")) checkedMethod = "GET";
		boolean allowedMethod = false;
		boolean options = false;
		if (checkedMethod.equals("OPTIONS")) {
			allowedMethod = true;
			options = true;
		} else {
			for (String method : methods) {
				if (method.equals(checkedMethod)) {
					allowedMethod = true;
				}
			}
		}
		if (!allowedMethod || options) {
			try (HTTPResponse response = openResponse()) {
				response.setHeader(options ? "200 OK" : "405 Method Not Allowed");
				response.setHeader("Allow", String.join(", ", methods));
				response.contentLength(0);
				response.sendHeaders();
			}
			throw EndResponse.get();
		}
	}
}
