package io.siggi.http;

import io.siggi.http.defaultresponders.DefaultResponder;
import io.siggi.http.exception.EndResponse;
import io.siggi.http.exception.TooBigException;
import io.siggi.http.io.ChunkedInputStream;
import io.siggi.http.io.ChunkedOutputStream;
import io.siggi.http.io.ConcatenatedInputStream;
import io.siggi.http.io.DeadlineInputStream;
import io.siggi.http.io.EOFInputStream;
import io.siggi.http.io.ReadLimitInputStream;
import io.siggi.http.io.SubInputStream;
import io.siggi.http.io.ToggleableBufferedOutputStream;
import io.siggi.http.io.WriteFailureTrackingOutputStream;
import io.siggi.http.registry.HTTPResponderRegistry;
import io.siggi.http.util.CaseInsensitiveHashMap;
import io.siggi.http.util.CloudFlare;
import io.siggi.http.util.HTMLUtils;
import io.siggi.http.util.Util;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocket;

final class HTTPHandler {

	private void processHTTP(HTTPRequest request) throws Exception {
		HTTPResponder responder = server.getResponderRegistry(request.host).getResponder(request.url);
		if (responder != null) {
			try {
				responder.respond(request);
			} catch (EndResponse ignored) {
			}
			if (noAutoClose && cannotKeepAlive) {
				return;
			}
		}
		if (!request.response.alreadyWrote() && request.url.equals("*") && !request.method.equals("OPTIONS")) {
			badRequest();
		}
		if (!cleanupIsExplicit)
			handlePostRequest(request, responder);
	}

	private void handlePostRequest(HTTPRequest request, HTTPResponder responder) throws Exception {
		if (wrote) {
			finishBody();
		} else {
			if (isRedirectCode(responseCode)) {
				List<String> list = headers.get(headerName("Location"));
				if (list != null && list.size() == 1) {
					String location = list.get(0);
					String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>" + responseHeader + "</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>" + responseHeader + "</h1><br>\nThe resource you requested has moved to a new location.  <a href=\"" + HTMLUtils.htmlentities(location) + "\">Click here to go to the new location.</a><br>\n<hr>\n" + request.getServerSignature() + "<br>\n</body>\n</html>";
					byte[] pageBytes = getBytes(page);
					setHeader("Content-Length", Integer.toString(pageBytes.length));
					setHeader("Content-Type", "text/html; charset=utf-8");
					writeHeaders(true);
					contentOutStream.write(pageBytes);
					return;
				}
			}
			setHeader("404 Not Found");
			if (responder != null) {
				try {
					responder.respond404(request);
				} catch (EndResponse ignored) {
				}
				if (wrote) {
					finishBody();
					return;
				}
			}
			if (!wrote) {
				server.getDefaultHandler(404).respond(request);
				if (wrote) {
					finishBody();
				} else {
					String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>500 Internal Server Error</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>500 Internal Server Error</h1><br>\nThe server failed to produce a 404 response.  Check the server code to make sure it's working properly!<br>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
					byte pageBytes[] = getBytes(page);
					setHeader("500 Internal Server Error");
					setHeader("Content-Length", Integer.toString(pageBytes.length));
					setHeader("Content-Type", "text/html; charset=utf-8");
					writeHeaders(true);
					contentOutStream.write(pageBytes);
				}
			}
		}
	}

	void prewrite(long amount) throws IOException {
		if (outputContentLength >= 0L) {
			long left = outputContentLength - writtenBodyLength;
			if (amount > left) {
				throw new IOException("Too much data written!");
			}
		}
		writtenBodyLength += amount;
	}

	private void finishBody() throws IOException {
		if (usingHeadMethod) {
			return;
		}
		if (chunked) {
			if (contentOutStream instanceof BufferedOutputStream) {
				contentOutStream.flush();
			}
			if (chunkOutputStream != null)
				chunkOutputStream.close();
		} else if (writtenBodyLength < outputContentLength) {
			// website code didn't write the whole body
			// this cannot be recovered from, so we must close the connection
			mustEndConnection = true;
			if (contentOutStream instanceof BufferedOutputStream) {
				contentOutStream.flush();
			}
		}
	}
	String postData = null;

	/**
	 * Read an application/x-www-form-urlencoded request body.
	 *
	 * @param in the request body stream
	 * @param contentLength the declared Content-Length, or -1 if the body is chunked
	 * @param postLimit the maximum body size in bytes, or 0 or less for no limit
	 * @return the body
	 * @throws TooBigException if the body is larger than postLimit
	 * @throws EOFException if the stream ends before contentLength bytes were read
	 */
	static byte[] readFormBody(InputStream in, long contentLength, int postLimit) throws IOException {
		if (postLimit > 0 && contentLength > postLimit) {
			throw new TooBigException();
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int amountRead;
		while ((amountRead = in.read(buffer, 0, buffer.length)) != -1) {
			baos.write(buffer, 0, amountRead);
			if (postLimit > 0 && baos.size() > postLimit) {
				throw new TooBigException();
			}
		}
		if (contentLength >= 0 && baos.size() < contentLength) {
			throw new EOFException("Request body ended after " + baos.size() + " of " + contentLength + " bytes");
		}
		return baos.toByteArray();
	}

	/**
	 * Returned by {@link #getRequestBodyLength(Map)} when the request body is chunked.
	 */
	static final long CHUNKED_BODY = -2L;

	/**
	 * Work out how a request body is framed, following RFC 9112 section 6. Anything ambiguous is rejected rather than
	 * guessed at, because a proxy in front of the server might guess differently and let a second request be smuggled
	 * inside the body of the first.
	 *
	 * @param headers the request headers, with case-insensitive keys
	 * @return the length of the body, -1 if there is no body, or {@link #CHUNKED_BODY} if the body is chunked
	 * @throws IllegalArgumentException if the request has both Transfer-Encoding and Content-Length, uses a transfer
	 * coding other than a single chunked, or has a Content-Length that is invalid or has conflicting values
	 */
	static long getRequestBodyLength(Map<String, List<String>> headers) {
		List<String> transferEncodings = headers.get("Transfer-Encoding");
		List<String> contentLengths = headers.get("Content-Length");
		if (transferEncodings != null && !transferEncodings.isEmpty()) {
			if (contentLengths != null && !contentLengths.isEmpty()) {
				throw new IllegalArgumentException("Both Transfer-Encoding and Content-Length are present");
			}
			List<String> codings = new ArrayList<>();
			for (String value : transferEncodings) {
				for (String coding : value.split(",")) {
					coding = coding.trim();
					if (!coding.isEmpty()) {
						codings.add(coding);
					}
				}
			}
			// No other transfer codings are supported, and chunked can only be applied once
			if (codings.size() != 1 || !codings.get(0).equalsIgnoreCase("chunked")) {
				throw new IllegalArgumentException("Unsupported Transfer-Encoding: " + codings);
			}
			return CHUNKED_BODY;
		}
		if (contentLengths == null || contentLengths.isEmpty()) {
			return -1L;
		}
		long length = -1L;
		for (String value : contentLengths) {
			// A repeated Content-Length is allowed if every copy has the same value
			for (String item : value.split(",", -1)) {
				item = item.trim();
				if (item.isEmpty()) {
					throw new IllegalArgumentException("Empty Content-Length");
				}
				for (int i = 0; i < item.length(); i++) {
					char c = item.charAt(i);
					if (c < '0' || c > '9') {
						throw new IllegalArgumentException("Invalid Content-Length: " + item);
					}
				}
				long itemLength;
				try {
					itemLength = Long.parseLong(item);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Content-Length is too big: " + item);
				}
				if (length != -1L && length != itemLength) {
					throw new IllegalArgumentException("Conflicting Content-Length values");
				}
				length = itemLength;
			}
		}
		return length;
	}

	private void badRequest() throws IOException {
		resetHeaders();
		String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>400 Bad Request</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>400 Bad Request</h1><br>\nThe server could not understand your request.<br>\n<br>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
		sendErrorPage(400, "Bad Request", page, null);
	}

	private void requestUriTooBig() throws IOException {
		resetHeaders();
		String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>414 Request URI Too Long</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>414 Request URI Too Long</h1><br>\nThat's what she said!<br>\n<br>\nThe request URI you submitted is too large for this server to handle!<br>\n<br>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
		sendErrorPage(414, "Request URI Too Long", page, null);
	}

	private void tooBig() throws IOException {
		resetHeaders();
		String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>413 Request Entity Too Large</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>413 Request Entity Too Large</h1><br>\nThat's what she said!<br>\n<br>\nThe data you submitted is too large for this server to handle!<br>\n<br>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
		sendErrorPage(413, "Request Entity Too Large", page, null);
	}

	/**
	 * Sends an error response the server builds itself, after letting the
	 * error page handler, if there is one, add headers or replace the page.
	 * The connection is closed afterwards.
	 *
	 * @param defaultHtml the built-in page, or null for an empty body
	 * @param exception the exception behind a 500, or null
	 */
	private void sendErrorPage(int code, String reason, String defaultHtml, Throwable exception) throws IOException {
		ErrorPage page = new ErrorPage(code, reason, host, exception, defaultHtml);
		ErrorPageHandler handler = server.getErrorPageHandler();
		if (handler != null) {
			try {
				handler.handle(page);
			} catch (Throwable t) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				t.printStackTrace(new PrintStream(baos, true));
				System.err.print("SiggiHTTP: the error page handler failed, sending the built-in page: " + new String(baos.toByteArray()));
				page = new ErrorPage(code, reason, host, exception, defaultHtml);
			}
		}
		setHeader(code + " " + reason);
		for (Map.Entry<String, List<String>> entry : page.getHeaders().entrySet()) {
			deleteHeader(entry.getKey());
			for (String value : entry.getValue()) {
				addHeader(entry.getKey(), value);
			}
		}
		byte[] body = page.body();
		if (page.getContentType() == null) {
			deleteHeader("Content-Type");
		} else {
			setHeader("Content-Type", page.getContentType());
		}
		setHeader("Content-Length", Integer.toString(body.length));
		keepAlive(false);
		writeHeaders(true);
		out.write(body);
		out.flush();
	}

	private final List<File> purgeable = new LinkedList<>();

	private File createTmpFile() {
		File file;
		do {
			file = new File(server.getTmpDir(), Util.randomChars(20) + ".dat");
		} while (file.exists());
		purgeable.add(file);
		return file;
	}

	private void purgeTmpFiles() {
		for (Iterator<File> it = purgeable.iterator(); it.hasNext();) {
			File next = it.next();
			if (next.exists()) {
				next.delete();
			}
			it.remove();
		}
	}

	@Deprecated
	HTTPHandler(HTTPServer server, Socket socket, ServerSocket sourceSocket, Executor executor) throws IOException {
		this.server = server;
		this.sock = socket;
		this.sourceSocket = sourceSocket;
		in = deadlineIn = new DeadlineInputStream(socket.getInputStream(), socket);
		rawOut = clientOut = new WriteFailureTrackingOutputStream(socket.getOutputStream());
		out = new ToggleableBufferedOutputStream(rawOut);
		realInetAddress = inetAddress = socket.getInetAddress();
		ip = inetAddress.getHostAddress();
		this.executor = executor;
	}

	HTTPHandler(HTTPServer server, Socket socket, InputStream preRead, Executor executor) throws IOException {
		this.server = server;
		this.sock = socket;
		this.sourceSocket = null;
		deadlineIn = new DeadlineInputStream(socket.getInputStream(), socket);
		in = preRead == null ? deadlineIn : new ConcatenatedInputStream(preRead, deadlineIn);
		rawOut = clientOut = new WriteFailureTrackingOutputStream(socket.getOutputStream());
		out = new ToggleableBufferedOutputStream(rawOut);
		realInetAddress = inetAddress = socket.getInetAddress();
		ip = inetAddress.getHostAddress();
		this.executor = executor;
	}

	private boolean started = false;
	private final Executor executor;

	void start() {
		if (started) {
			return;
		}
		started = true;
		startHandlingNewRequest();
	}

	private static int clientHandlerID = 0;

	private static synchronized String nextClientHandler() {
		return "HTTPServer-ClientHandler-" + (clientHandlerID++);
	}

	private String toString(byte[] b) {
		char[] ch = new char[b.length];
		for (int i = 0; i < b.length; i++) {
			ch[i] = (char) (((int) b[i]) & 0xff);
		}
		return new String(ch);
	}

	private String readCRLF(long timeout, int maxLength) throws IOException, HTTPTimedOutException {
		String line = readLine(timeout, maxLength);
		if (line == null) {
			return null;
		}
		if (line.substring(line.length() - 1).equals("\r")) {
			line = line.substring(0, line.length() - 1);
		}
		return line;
	}

	private String readLine(long timeout, int maxLength) throws IOException, HTTPTimedOutException {
		sock.setSoTimeout((int) timeout);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
		boolean readAny = false;
		try {
			for (int i; (i = in.read()) != -1 && i != 10;) {
				if (!readAny) {
					// The whole request line and headers must arrive within this
					// time of the first byte, however slowly they trickle in.
					deadlineIn.startDeadline(server.getRequestHeaderTimeout());
				}
				readAny = true;
				if (baos.size() >= maxLength) {
					throw new TooBigException();
				}
				baos.write(i);
			}
		} catch (SocketTimeoutException e) {
			throw new HTTPTimedOutException();
		} finally {
			sock.setSoTimeout(0);
		}
		if (!readAny) {
			return null;
		}
		return new String(baos.toByteArray());
	}

	private void write(String data) throws IOException {
		write(data.getBytes("UTF-8"));
	}

	private void write(byte[] data) throws IOException {
		out.write(data);
		out.flush();
	}

	private void run() {
		try {
			theLoop:
			while (keepAlive && !mustEndConnection) {
				boolean send408 = false;
				try {
					cleanupIsExplicit = false;
					cleanedUpOnHandlerThread = false;
					handlerThread = Thread.currentThread();
					wrote = false;
					writtenBodyLength = 0L;
					outputContentLength = -1L;
					responseBuffered = true;
					keepAlive = false;
					resetHeaders();
					// Cleared so an error before the next request's Host header
					// doesn't report the previous request's host
					host = null;
					deadlineIn.clearDeadline();
					String request = "";
					send408 = true;
					try {
						while (request.trim().equals("")) {
							if (keepAliveTime != -1) {
								request = readCRLF((((long) keepAliveTime) * 1000L) + 10000L /*Wait an extra 10 seconds*/, server.getRequestURISizeLimit());
							} else {
								request = readCRLF(60000L, server.getRequestURISizeLimit());
							}
							if (request == null) {
								break theLoop;
							}
						}
					} catch (TooBigException ex) {
						requestUriTooBig();
						break;
					} catch (IOException ex) {
						// The connection was reset, or the socket was closed under us, while waiting
						// for a request. That's a normal way for an idle keep-alive connection to end.
						break theLoop;
					}
					send408 = false;
					int s = request.indexOf(" ");
					if (s == -1) {
						s = request.length();
					}
					String method = request.substring(0, s).toUpperCase();
					usingHeadMethod = method.equals("HEAD");
					cleanupTasks.clear();
					processRequest(request);
				} catch (SocketTimeoutException | HTTPTimedOutException ste) {
					// Timed out
					if (send408) {
						requestTimeout();
					}
					keepAlive = false;
				} catch (Exception e) {
					if (clientOut.hasFailed()) {
						// Writing to the client failed, so it has gone away, for example a viewer
						// leaving a stream. That's not a server error, so end the connection quietly.
						cannotKeepAlive = true;
						keepAlive = false;
						continue;
					}
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					e.printStackTrace(new PrintStream(baos, true));
					String stackTrace = new String(baos.toByteArray());
					System.err.print("SiggiHTTP: error while handling a request: " + stackTrace);
					if (!wrote) {
						try {
							String details;
							if (server.isDevMode()) {
								details = "An error has occurred. The details of this problem are shown below.<br>\n<hr>\n<pre style=\"font-family: Monaco; font-size: 10px\">" + HTMLUtils.htmlentities(stackTrace, false) + "</pre>\n";
							} else {
								details = "An error has occurred. The details have been logged on the server.<br>\n";
							}
							String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>500 Internal Server Error</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>500 Internal Server Error</h1><br>\n*puke* ugh, I feel sick.<p>" + details + "<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
							sendErrorPage(500, "Internal Server Error", page, e);
						} catch (Exception e2) {
						}
					}
					cannotKeepAlive = true;
					keepAlive = false;
				} finally {
					if (!cleanupIsExplicit)
						postRequestCleanup();
				}
				if (cleanupIsExplicit && !cleanedUpOnHandlerThread) {
					break;
				}
			}
		} finally {
			if (!noAutoClose && !cleanupIsExplicit) {
				lingeringClose();
			}
		}
	}

	private static final long LINGER_TIME = 2000L;
	private static final long LINGER_READ_LIMIT = 1048576L;

	/**
	 * Closes the connection without throwing away the response. Closing a
	 * socket that still has unread request bytes, such as a body the
	 * responder didn't read, makes the OS send a reset, and the client can
	 * lose the end of the response with it. So first stop sending, which the
	 * client sees as the end of the response, then read and discard what it
	 * sends until it closes its side, for up to two seconds or 1 MiB, and
	 * only then close.
	 */
	private void lingeringClose() {
		try {
			try {
				out.flush();
			} catch (Exception ignored) {
			}
			if (!(sock instanceof SSLSocket)) {
				// TLS sockets can't half-close
				sock.shutdownOutput();
			}
			deadlineIn.clearDeadline();
			deadlineIn.startDeadline(LINGER_TIME);
			sock.setSoTimeout((int) LINGER_TIME);
			byte[] buffer = new byte[8192];
			long discarded = 0L;
			int amount;
			while (discarded < LINGER_READ_LIMIT && (amount = in.read(buffer)) != -1) {
				discarded += amount;
			}
		} catch (Exception ignored) {
			// Timed out, reset, or already closed: close it now either way
		} finally {
			try {
				sock.close();
			} catch (Exception ignored) {
			}
		}
	}

	private void requestTimeout() {
		try {
			sendErrorPage(408, "Request Timeout", null, null);
		} catch (Exception ignored) {
		}
	}

	private void startHandlingNewRequest() {
		if (executor == null) {
			new Thread(this::run, nextClientHandler()).start();
		} else {
			executor.execute(this::run);
		}
	}

	void makeCleanupExplicit() {
		cleanupIsExplicit = true;
	}

	void closeRequest() throws IOException {
		cleanupIsExplicit = true;
		finishBody();
		postRequestCleanup();
		if (Thread.currentThread() == handlerThread) {
			cleanedUpOnHandlerThread = true;
			return;
		}
		if (!keepAlive || mustEndConnection) {
			if (!noAutoClose) {
				lingeringClose();
			}
		} else {
			startHandlingNewRequest();
		}
	}

	private void postRequestCleanup() {
		purgeTmpFiles();
		for (Runnable runnable : cleanupTasks) {
			runnable.run();
		}
		cleanupTasks.clear();
	}

	/**
	 * Returns the value of the parameter called {@code name} in a
	 * Content-Disposition header, with surrounding quotes removed, or null if
	 * it isn't there. Parameter names must match exactly (ignoring case), so
	 * looking up {@code name} doesn't find {@code filename}, and a {@code ;}
	 * inside a quoted value doesn't end it.
	 */
	static String dispositionParam(String header, String name) {
		int length = header.length();
		int i = header.indexOf(';');
		if (i == -1) return null;
		while (i < length) {
			i++; // skip the ;
			int keyStart = i;
			while (i < length && header.charAt(i) != '=' && header.charAt(i) != ';') i++;
			String key = header.substring(keyStart, i).trim();
			if (i >= length || header.charAt(i) == ';') continue; // no value
			i++; // skip the =
			while (i < length && header.charAt(i) == ' ') i++;
			String value;
			if (i < length && header.charAt(i) == '"') {
				int end = header.indexOf('"', i + 1);
				if (end == -1) end = length;
				value = header.substring(i + 1, end);
				i = header.indexOf(';', end);
				if (i == -1) i = length;
			} else {
				int end = header.indexOf(';', i);
				if (end == -1) end = length;
				value = header.substring(i, end).trim();
				i = end;
			}
			if (key.equalsIgnoreCase(name)) return value;
		}
		return null;
	}

	private static String deURLEncode(String encoded) {
		byte[] in = encoded.getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[in.length];
		int length = 0;
		for (int i = 0; i < in.length; i++) {
			int high, low;
			if (in[i] == '%' && i + 2 < in.length
					&& (high = Character.digit(in[i + 1], 16)) != -1
					&& (low = Character.digit(in[i + 2], 16)) != -1) {
				out[length++] = (byte) ((high << 4) | low);
				i += 2;
			} else {
				// An invalid escape is kept as a literal %
				out[length++] = in[i];
			}
		}
		return new String(out, 0, length, StandardCharsets.UTF_8);
	}

	void keepAlive(boolean keepAlive) {
		int timeout = 15;
		if (cannotKeepAlive) {
			keepAlive = false;
		}
		this.keepAlive = keepAlive;
		if (keepAlive) {
			keepAliveTime = timeout;
			setHeader("Keep-Alive", "timeout=" + timeout);
			setHeader("Connection", "Keep-Alive");
		} else {
			deleteHeader("Keep-Alive");
			setHeader("Connection", "close");
		}
	}

	private void processRequest(String request) throws Exception {
		requestBody = null;
		EOFInputStream contentStream = null;
		HTTPRequest req = null;
		try {
			String method = request.substring(0, request.indexOf(" "));
			String requestURI;
			String httpVersion = "HTTP/0.0";
			if (request.substring(request.lastIndexOf(" ") + 1).startsWith("HTTP/")) {
				requestURI = request.substring(request.indexOf(" ") + 1, request.lastIndexOf(" "));
				httpVersion = request.substring(request.lastIndexOf(" ") + 1);
			} else {
				out.write(("Please send a full HTTP request.  If you are seeing this message, you may have an out of date browser.").getBytes());
				return;
			}
			Map<String, String> get = new HashMap<>();
			Map<String, String> post = new HashMap<>();
			Map<String, String> cookies = new HashMap<>();
			Map<String, List<String>> headers = new CaseInsensitiveHashMap<>();
			Map<String, UploadedFile> uploadedFiles = new HashMap<>();
			String fullRequestURI = requestURI;
			if (requestURI.contains("?")) {
				String getRaw = requestURI.substring(requestURI.indexOf("?") + 1);
				requestURI = requestURI.substring(0, requestURI.indexOf("?"));
				Util.parseQueryString(getRaw, get);
			}
			// An encoded slash can't be told apart from a real one after decoding
			boolean hasEncodedSlash = requestURI.toLowerCase(Locale.ROOT).contains("%2f");
			requestURI = deURLEncode(requestURI);
			long incomingContentLength = -1L;
			String incomingContentType = "application/x-octet-stream";
			referer = null;
			host = null;
			userAgent = null;
			String forceHost = null;
			sock.setTcpNoDelay(true);
			sock.setSoTimeout(60000);
			try {
				Util.readHeaders(in, headers, server.getHeaderSizeLimit());
			} catch (TooBigException e) {
				tooBig();
				return;
			} catch (SocketTimeoutException e) {
				requestTimeout();
				keepAlive = false;
				return;
			} finally {
				deadlineIn.clearDeadline();
			}
			String expect = null;
			boolean clientAskedForKeepAlive = false;
			for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
				String key = entry.getKey();
				for (String val : entry.getValue()) {
					if (key.equalsIgnoreCase("X-Forwarded-For")) { // Loopback gateway
						if (realInetAddress.isLoopbackAddress() || CloudFlare.isCloudFlare(realInetAddress) || server.isIPTrusted(realInetAddress.getHostAddress())) {
							String[] forwardedFor = val.split(",");
							for (int i = 0; i < forwardedFor.length; i++) {
								forwardedFor[i] = forwardedFor[i].trim();
							}

							for (int i = forwardedFor.length - 1; i >= 0; i--) {
								InetAddress forwardedAddress = Util.parseIPLiteral(forwardedFor[i]);
								if (forwardedAddress == null) {
									break;
								}
								inetAddress = forwardedAddress;
								ip = inetAddress.getHostAddress();
								if (CloudFlare.isCloudFlare(ip) || server.isIPTrusted(ip)) {
									continue;
								}
								break;
							}
						}
					} else if (key.equalsIgnoreCase("CF-Connecting-IP")) { // CloudFlare gateway
						if (CloudFlare.isCloudFlare(realInetAddress) || server.isIPTrusted(realInetAddress.getHostAddress())) {
							InetAddress connectingAddress = Util.parseIPLiteral(val);
							if (connectingAddress != null) {
								inetAddress = connectingAddress;
								ip = inetAddress.getHostAddress();
							}
						}
					} else if (key.equalsIgnoreCase("X-Forwarded-Host")) { // Loopback gateway
						if (realInetAddress.isLoopbackAddress() || server.isIPTrusted(realInetAddress.getHostAddress())) {
							forceHost = val;
						}
					} else if (key.equalsIgnoreCase("Cookie")) {
						String cookieParts[] = val.split(";");
						for (String cookiePart : cookieParts) {
							if (cookiePart.contains("=")) {
								String ckey = cookiePart.substring(0, cookiePart.indexOf("=")).trim();
								String cval = cookiePart.substring(cookiePart.indexOf("=") + 1).trim();
								while (ckey.contains("+")) {
									ckey = ckey.substring(0, ckey.indexOf("+")) + "%20" + ckey.substring(ckey.indexOf("+") + 1);
								}
								while (cval.contains("+")) {
									cval = cval.substring(0, cval.indexOf("+")) + "%20" + cval.substring(cval.indexOf("+") + 1);
								}
								ckey = deURLEncode(ckey);
								cval = deURLEncode(cval);
								cookies.put(ckey, cval);
							} else {
								cookies.put(deURLEncode(cookiePart), "");
							}
						}
					} else if (key.equalsIgnoreCase("Content-Type")) {
						incomingContentType = val;
					} else if (key.equalsIgnoreCase("Referer")) {
						referer = val;
					} else if (key.equalsIgnoreCase("Host")) {
						host = val;
					} else if (key.equalsIgnoreCase("User-Agent")) {
						userAgent = val;
					} else if (key.equalsIgnoreCase("Connection")) {
						for (String option : val.split(",")) {
							option = option.trim();
							if (option.equalsIgnoreCase("close")) {
								cannotKeepAlive = true;
							} else if (option.equalsIgnoreCase("keep-alive")) {
								clientAskedForKeepAlive = true;
							}
						}
					} else if (key.equalsIgnoreCase("Expect")) {
						expect = val;
					}
				}
			}
			if (forceHost != null) {
				host = forceHost;
			}
			if (!httpVersion.equals("HTTP/1.1") && !clientAskedForKeepAlive) {
				// Before HTTP/1.1, a connection only persists if the client asks for it
				// (RFC 9112 section 9.3)
				cannotKeepAlive = true;
			}
			try {
				incomingContentLength = getRequestBodyLength(headers);
			} catch (IllegalArgumentException e) {
				badRequest();
				return;
			}
			boolean incomingIsChunked = incomingContentLength == CHUNKED_BODY;
			if (incomingIsChunked) {
				incomingContentLength = -1L;
				if (!httpVersion.equals("HTTP/1.1")) {
					// HTTP/1.0 has no Transfer-Encoding, so an intermediary may have framed this differently
					// (RFC 9112 section 6.1)
					cannotKeepAlive = true;
				}
			}
			if (host == null && !httpVersion.equals("HTTP/1.0") && !httpVersion.equals("HTTP/0.9")) {
				// Host is mandatory from HTTP/1.1 onwards (RFC 9112 section 3.2)
				badRequest();
				return;
			}
			if (hasEncodedSlash || (!requestURI.startsWith("/") && !requestURI.equals("*"))) {
				badRequest();
				return;
			}
			int postLimit = server.getPostLimit();
			long uploadLimit = server.getUploadLimit();
			if (expect != null) {
				if (expect.equalsIgnoreCase("100-continue")) {
					if ((uploadLimit > 0 && incomingContentLength > uploadLimit)
							|| (postLimit > 0
							&& method.equals("POST")
							&& incomingContentType != null
							&& incomingContentType.equalsIgnoreCase("application/x-www-form-urlencoded")
							&& incomingContentLength > postLimit)) {
						tooBig();
						return;
					}
					write("HTTP/1.1 100 Continue\r\n\r\n");
				}
			}
			postData = null;
			boolean hasStream = incomingContentLength >= 0 || incomingIsChunked;
			if (hasStream) {
				InputStream stream;
				if (incomingIsChunked) {
					stream = new ChunkedInputStream(in);
				} else {
					stream = new SubInputStream(in, incomingContentLength);
				}
				contentStream = new EOFInputStream(stream);
				requestBody = stream;
			}
			if (contentStream != null) {
				if (!server.isIgnoringMultipartFormData() && incomingContentType.toLowerCase().contains("multipart/form-data")) { // MULTIPART FORM
					if (uploadLimit > 0L && incomingContentLength > uploadLimit) {
						tooBig();
						return;
					}
					int currentPostSize = 0;
					int boundaryPos = incomingContentType.toLowerCase().indexOf("boundary=");
					if (boundaryPos == -1) {
						badRequest();
						return;
					}
					boundaryPos += 9;
					int afterBoundaryPos = incomingContentType.indexOf(";", boundaryPos);
					String boundary;
					if (afterBoundaryPos == -1) {
						boundary = incomingContentType.substring(boundaryPos);
					} else {
						boundary = incomingContentType.substring(boundaryPos, afterBoundaryPos);
					}
					contentStream.setEofSequence(("\r\n--" + boundary + "--\r\n").getBytes());
					try {
						InputStream readingStream = uploadLimit > 0 ? new ReadLimitInputStream(contentStream, uploadLimit) : contentStream;
						EOFInputStream partReader = new EOFInputStream(readingStream);
						partReader.setEofSequence(("--" + boundary + "\r\n").getBytes());
						Util.readFullyToBlackHole(partReader);
						partReader.setEofSequence(("\r\n--" + boundary + "\r\n").getBytes());
						partReader.nextEofSequence();
						Map<String, List<String>> partHeaders;
						while ((partHeaders = Util.readHeaders(partReader, server.getHeaderSizeLimit())) != null) {
							try {
								String fieldName = null;
								String fileName = null;
								int contentLength = -1;
								String contentType = "text/plain";
								for (Map.Entry<String, List<String>> entry : partHeaders.entrySet()) {
									String key = entry.getKey();
									for (String val : entry.getValue()) {
										if (key.equalsIgnoreCase("Content-Disposition")) {
											String name = dispositionParam(val, "name");
											if (name != null) {
												fieldName = name;
											}
											String file = dispositionParam(val, "filename");
											if (file != null) {
												fileName = Util.urldecode(file, false);
											}
										} else if (key.equalsIgnoreCase("Content-Length")) {
											contentLength = Integer.parseInt(val);
										} else if (key.equalsIgnoreCase("Content-Type")) {
											contentType = val;
										}
									}
								}
								if (fieldName == null) {
									badRequest();
									return;
								}
								if (fileName == null) {
									String val;
									if (postLimit > 0) {
										val = Util.readFullyAsString(partReader, Math.max(0, postLimit - currentPostSize - fieldName.length() - 2));
									} else {
										val = Util.readFullyAsString(partReader);
									}
									post.put(fieldName, val);
									currentPostSize += fieldName.length() + val.length() + 2;
									if (postLimit > 0 && currentPostSize > postLimit) {
										throw new TooBigException();
									}
								} else {
									File tmpFile = createTmpFile();
									try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
										Util.copy(partReader, fos);
										UploadedFile uf = uploadedFiles.put(fieldName, new UploadedFile(fileName, tmpFile, contentType));
										if (uf != null) {
											uf.delete();
										}
									}
								}
							} finally {
								partReader.nextEofSequence();
							}
						}
					} catch (TooBigException ex) {
						tooBig();
						return;
					} finally {
						if (keepAlive) {
							contentStream.setEofSequence(null);
							Util.readFullyToBlackHole(contentStream);
						}
						// we consumed it
						contentStream = null;
					}
				} else if (method.equals("POST") && incomingContentType.toLowerCase().contains("application/x-www-form-urlencoded")) {
					// only POST bodies are forms; a PUT with this Content-Type (curl --data-binary's default) is raw data
					try {
						postData = new String(readFormBody(contentStream, incomingContentLength, postLimit));
						Util.parseQueryString(postData, post);
					} catch (TooBigException ex) {
						tooBig();
						return;
					} catch (EOFException ex) {
						badRequest();
						return;
					} finally {
						if (keepAlive) {
							contentStream.setEofSequence(null);
							Util.readFullyToBlackHole(contentStream);
						}
						// we consumed it
						contentStream = null;
					}
				}
			}

			req = new HTTPRequest(this, method, requestURI, fullRequestURI, get, post, cookies, headers, uploadedFiles, host, referer, userAgent, contentStream);
			cleanupTasks.add(req::saveSession);
			cleanupTasks.add(() -> {
				if (contentOutStream != null) {
					try {
						contentOutStream.flush();
					} catch (Exception ignored) {
					}
					contentOutStream = null;
				}
				if (chunkOutputStream != null) {
					chunkOutputStream = null;
				}
				try {
					out.flush();
				} catch (Exception ignored) {
				}
			});
			if (contentStream != null) {
				final EOFInputStream finalContentStream = contentStream;
				cleanupTasks.add(() -> {
					if (!keepAlive) {
						// The connection is closing anyway, so don't read the rest
						return;
					}
					// The response promised keep-alive, which writeHeaders() only
					// does when what's left of the body is small, so read the
					// rest of it to reach the next request.
					finalContentStream.setEofSequence(null);
					try {
						int oldTimeout = sock.getSoTimeout();
						sock.setSoTimeout(UNREAD_BODY_DRAIN_TIMEOUT);
						try {
							byte[] buffer = new byte[4096];
							long drained = 0L;
							int amount;
							while ((amount = finalContentStream.read(buffer)) != -1) {
								drained += amount;
								if (drained > UNREAD_BODY_DRAIN_LIMIT) {
									mustEndConnection = true;
									return;
								}
							}
						} finally {
							sock.setSoTimeout(oldTimeout);
						}
					} catch (Exception e) {
						mustEndConnection = true;
					}
				});
			}
			if (cannotKeepAlive) {
				// resetHeaders() already offered keep-alive before the request said otherwise
				keepAlive(false);
			}
			processHTTP(req);
		} finally {
			if (req != null && !cleanupIsExplicit) {
				req.response.markClosed();
			}
		}
	}

	private Thread handlerThread = null;
	private final List<Runnable> cleanupTasks = new LinkedList<>();
	private boolean cleanupIsExplicit = false;
	private boolean cleanedUpOnHandlerThread = false;

	private byte[] toBytes(String str) {
		char[] ch = str.toCharArray();
		byte[] b = new byte[ch.length];
		for (int i = 0; i < b.length; i++) {
			b[i] = (byte) ch[i];
		}
		return b;
	}

	private String headerName(String header) {
		for (String h : headers.keySet()) {
			if (h.equalsIgnoreCase(header)) {
				return h;
			}
		}
		return header;
	}

	private void resetHeaders() {
		if (wrote) {
			throw new RuntimeException("Headers already sent, and cannot be modified!");
		}
		responseCode = 200;
		responseHeader = "200 OK";
		headers.clear();
		String currentDate = formatDate(System.currentTimeMillis());
		setHeader("Content-Type", "text/html; charset=utf-8");
		setHeader("Date", currentDate);
		setHeader("Server", server.serverName);
		setHeader("Transfer-Encoding", "chunked");
		keepAlive(true);
		chunked = true;
	}

	void setHeader(String response) {
		rejectLineBreaks(response);
		if (!response.contains(" ")) {
			throw new IllegalArgumentException("Response must be a number followed by the name of that response type.");
		}
		try {
			responseCode = Integer.parseInt(response.split(" ")[0]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Response must be a number followed by the name of that response type.");
		}
		responseHeader = response;
	}

	void setHeader(String key, String val) {
		rejectLineBreaks(key);
		rejectLineBreaks(val);
		if (key.equalsIgnoreCase("Set-Cookie")) {
			addHeader(key, val);
			return;
		}
		if (key.equalsIgnoreCase("Content-Length")) {
			chunked = false;
			deleteHeader("Transfer-Encoding");
		}
		key = headerName(key);
		ArrayList<String> list = new ArrayList<>();
		headers.put(key, list);
		list.add(val);
	}

	void addHeader(String key, String val) {
		rejectLineBreaks(key);
		rejectLineBreaks(val);
		if (key.equalsIgnoreCase("Content-Length")) {
			chunked = false;
			deleteHeader("Transfer-Encoding");
		}
		key = headerName(key);
		List<String> list = headers.get(key);
		if (list == null) {
			list = new ArrayList<>();
			headers.put(key, list);
		}
		list.add(val);
	}

	/**
	 * Rejects CR and LF in the status line and in header names and values, so
	 * request data placed in a header can't start a new header line.
	 */
	private static void rejectLineBreaks(String str) {
		if (str.indexOf('\r') >= 0 || str.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("Headers must not contain CR or LF characters.");
		}
	}

	void closeDelimited() {
		deleteHeader("Content-Length");
		deleteHeader("Transfer-Encoding");
		chunked = false;
		// The body only ends when the connection does, so it can't be reused
		cannotKeepAlive = true;
		keepAlive(false);
	}

	void deleteHeader(String key) {
		key = headerName(key);
		headers.remove(key);
	}

	boolean hasHeader(String key) {
		key = headerName(key);
		return headers.containsKey(key);
	}

	String[] getHeader(String key) {
		key = headerName(key);
		ArrayList header = (ArrayList) headers.get(key);
		if (header == null) {
			return new String[0];
		}
		return (String[]) header.toArray(new String[header.size()]);
	}

	void doNotCache() {
		setHeader("Pragma", "no-cache");
		setHeader("Cache-Control", "no-cache");
		setHeader("Expires", "-1");
	}

	void cache(long maxAge) {
		String expire = formatDate(System.currentTimeMillis() + (maxAge * 1000L));

		deleteHeader("Pragma");
		setHeader("Cache-Control", "public, max-age=" + maxAge);
		setHeader("Expires", expire);
	}

	void writeHeaders(boolean delayFlush) throws IOException {
		if (wrote) {
			return;
		}
		if (keepAlive && !requestBodyCanBeDrained()) {
			// Too much of the request body is unread to skip over it after the
			// response, so the connection will have to close; say so now,
			// rather than promise keep-alive and break it
			cannotKeepAlive = true;
			keepAlive(false);
		}
		wrote = true;
		out.startBuffering();
		Util.writeCRLF("HTTP/1.1 " + responseHeader, out);
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			String header = entry.getKey();
			List<String> list = entry.getValue();
			for (String str : list) {
				Util.writeCRLF(header + ": " + str, out);
				if (header.equalsIgnoreCase("Content-Length")) {
					try {
						outputContentLength = Long.parseLong(str);
					} catch (Exception e) {
					}
				}
			}
		}
		Util.writeCRLF("", out);
		if (usingHeadMethod) {
			out.stopBuffering(false);
		} else if (chunked) {
			out.stopBuffering(delayFlush);
			contentOutStream = chunkOutputStream = new ChunkedOutputStream(out, false);
			chunkOutputStream.setBuffering(responseBuffered);
		} else {
			if (!responseBuffered) out.stopBuffering(delayFlush);
			contentOutStream = out;
			chunkOutputStream = null;
		}
	}

	void setBuffered(boolean buffered) throws IOException {
		responseBuffered = buffered;
		if (!wrote) return;
		if (chunkOutputStream != null) {
			chunkOutputStream.setBuffering(responseBuffered);
		} else if (responseBuffered) {
			out.startBuffering();
		} else {
			out.stopBuffering();
		}
	}

	private SimpleDateFormat simpleDateFormat;

	SimpleDateFormat getSimpleDateFormat() {
		if (simpleDateFormat == null) {
			simpleDateFormat = HTMLUtils.getSimpleDateFormat();
		}
		return simpleDateFormat;
	}

	String formatDate(long date) {
		return getSimpleDateFormat().format(new Date(date));
	}

	long parseDate(String date) {
		try {
			return getSimpleDateFormat().parse(date).getTime();
		} catch (ParseException ex) {
			return -1L;
		}
	}

	private static byte[] getBytes(String str) {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		try (Writer writer = new OutputStreamWriter(os, "UTF-8")) {
			writer.write(str);
			writer.flush();
			return os.toByteArray();
		} catch (Exception e) {
		}
		return new byte[]{};
	}

	Map<String, String> getCacheMap() {
		if (cacheMap == null) {
			cacheMap = new HashMap<>();
		}
		return cacheMap;
	}

	InetSocketAddress getLocalAddress() {
		return (InetSocketAddress) sock.getLocalSocketAddress();
	}

	InetSocketAddress getNextHopAddress() {
		return (InetSocketAddress) sock.getRemoteSocketAddress();
	}

	boolean isSocketSecure() {
		return (sock instanceof SSLSocket);
	}

	final HTTPServer server;
	boolean wrote = false;
	long writtenBodyLength = 0L;
	long outputContentLength = -1L;
	private boolean responseBuffered = true;
	ChunkedOutputStream chunkOutputStream = null;
	OutputStream contentOutStream = null;
	private final Socket sock;
	private final InputStream in;
	private final DeadlineInputStream deadlineIn;
	@Deprecated
	final ServerSocket sourceSocket;
	private final OutputStream rawOut;
	private final WriteFailureTrackingOutputStream clientOut;
	private final ToggleableBufferedOutputStream out;
	private final InetAddress realInetAddress;
	private InetAddress inetAddress;
	String ip;
	private boolean mustEndConnection = false;

	/**
	 * The most request body that's read and thrown away after the response,
	 * when the responder didn't read it, to keep the connection open.
	 */
	static final long UNREAD_BODY_DRAIN_LIMIT = 65536L;
	private static final int UNREAD_BODY_DRAIN_TIMEOUT = 5000;

	/**
	 * The current request's body as framed on the wire, before any wrapping,
	 * or null if there isn't one.
	 */
	private InputStream requestBody = null;

	/**
	 * Returns whether what's left of the request body is small enough to
	 * read and throw away after the response.
	 */
	private boolean requestBodyCanBeDrained() {
		if (requestBody instanceof SubInputStream) {
			return ((SubInputStream) requestBody).getRemaining() <= UNREAD_BODY_DRAIN_LIMIT;
		} else if (requestBody instanceof ChunkedInputStream) {
			// Chunked bodies don't say how much is left
			return ((ChunkedInputStream) requestBody).isFinished();
		}
		return true;
	}

	private boolean noAutoClose = false;
	private boolean keepAlive = true;
	private boolean cannotKeepAlive = false;
	private int keepAliveTime = -1;
	private String host = null;
	private String referer = null;
	private String userAgent = null;
	boolean usingHeadMethod = false;
	private Map<String, String> cacheMap = null;
	private int responseCode = 0;
	private String responseHeader = null;
	private final Map<String, List<String>> headers = new HashMap<>();
	boolean chunked = true;

	private boolean isRedirectCode(int code) {//301, 302, 303, 307, 308
		switch (code) {
			case 301: // Moved Permanently
			case 302: // Found
			case 303: // See Other
			case 307: // Temporary Redirect
			case 308: // Permanent Redirect
				return true;
			default:
				return false;
		}
	}

	Socket upgradeSocket() {
		cannotKeepAlive = true;
		keepAlive = false;
		noAutoClose = true;
		return sock;
	}
}
