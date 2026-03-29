package io.siggi.http;

import io.siggi.http.defaultresponders.DefaultResponder;
import io.siggi.http.exception.TooBigException;
import io.siggi.http.io.ChunkedInputStream;
import io.siggi.http.io.ChunkedOutputStream;
import io.siggi.http.io.ConcatenatedInputStream;
import io.siggi.http.io.EOFInputStream;
import io.siggi.http.io.ReadLimitInputStream;
import io.siggi.http.io.SubInputStream;
import io.siggi.http.registry.HTTPResponderRegistry;
import io.siggi.http.util.CaseInsensitiveHashMap;
import io.siggi.http.util.CloudFlare;
import io.siggi.http.util.HTMLUtils;
import io.siggi.http.util.Util;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocket;

final class HTTPHandler {

	private void processHTTP(HTTPRequest request) throws Exception {
		HTTPResponder responder = server.getResponderRegistry(request.host).getResponder(request.url);
		if (responder != null) {
			responder.respond(request);
			if (noAutoClose && cannotKeepAlive) {
				return;
			}
		}
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
					writeHeaders();
					contentOutStream.write(pageBytes);
					return;
				}
			}
			setHeader("404 Not Found");
			if (responder != null) {
				responder.respond404(request);
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
					writeHeaders();
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
			chunkOutputStream.close();
		} else if (writtenBodyLength < outputContentLength) {
			// website code didn't write the whole body
			// just pad it with zeroes
			mustEndConnection = true;
			if (contentOutStream instanceof BufferedOutputStream) {
				contentOutStream.flush();
			}
		}
	}
	String postData = null;

	private void badRequest() throws IOException {
		resetHeaders();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		new RuntimeException().printStackTrace(new PrintStream(baos, true));
		String req = new String(baos.toByteArray());
		String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>400 Bad Request</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>400 Bad Request</h1><br>\n<br>\n<pre style=\"font-family: Monaco; font-size: 10px\">" + req + "</pre><br>\n<br>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
		byte pageBytes[] = getBytes(page);
		setHeader("400 Bad Request");
		setHeader("Content-Length", Integer.toString(pageBytes.length));
		setHeader("Content-Type", "text/html; charset=utf-8");
		keepAlive(false);
		writeHeaders();
		out.write(pageBytes);
	}

	private void requestUriTooBig() throws IOException {
		resetHeaders();
		String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>414 Request URI Too Long</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>414 Request URI Too Long</h1><br>\nThat's what she said!<br>\n<br>\nThe request URI you submitted is too large for this server to handle!<br>\n<br>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
		byte pageBytes[] = getBytes(page);
		setHeader("414 Request URI Too Long");
		setHeader("Content-Length", Integer.toString(pageBytes.length));
		setHeader("Content-Type", "text/html; charset=utf-8");
		keepAlive(false);
		writeHeaders();
		out.write(pageBytes);
	}

	private void tooBig() throws IOException {
		resetHeaders();
		String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>413 Request Entity Too Large</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>413 Request Entity Too Large</h1><br>\nThat's what she said!<br>\n<br>\nThe data you submitted is too large for this server to handle!<br>\n<br>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
		byte pageBytes[] = getBytes(page);
		setHeader("413 Request Entity Too Large");
		setHeader("Content-Length", Integer.toString(pageBytes.length));
		setHeader("Content-Type", "text/html; charset=utf-8");
		keepAlive(false);
		writeHeaders();
		out.write(pageBytes);
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
		rawIn = socket.getInputStream();
		in = rawIn;
		out = socket.getOutputStream();
		realInetAddress = inetAddress = socket.getInetAddress();
		ip = inetAddress.getHostAddress();
		this.executor = executor;
	}

	HTTPHandler(HTTPServer server, Socket socket, InputStream preRead, Executor executor) throws IOException {
		this.server = server;
		this.sock = socket;
		this.sourceSocket = null;
		rawIn = preRead == null ? socket.getInputStream() : new ConcatenatedInputStream(preRead, socket.getInputStream());
		in = rawIn;
		out = socket.getOutputStream();
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
					bufferDisabled = false;
					keepAlive = false;
					resetHeaders();
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
						try {
							setHeader("408 Request Timeout");
							setHeader("Content-Length", "0");
							setHeader("Connection", "close");
							writeHeaders();
						} catch (Exception ignored) {
						}
					}
					keepAlive = false;
				} catch (Exception e) {
					/*System.err.print("Server error (500 sent to client): ");
					 e.printStackTrace();*/
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					e.printStackTrace(new PrintStream(baos, true));
					String stackTrace = new String(baos.toByteArray());
					if (!wrote) {
						try {
							String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>500 Internal Server Error</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>500 Internal Server Error</h1><br>\n*puke* ugh, I feel sick.<p>An error has occurred. The details of this problem are shown below.<br>\n<hr>\n<pre style=\"font-family: Monaco; font-size: 10px\">" + stackTrace + "</pre>\n<hr>\n" + server.getServerSignature(host) + "<br>\n</body>\n</html>";
							byte pageBytes[] = getBytes(page);
							setHeader("500 Internal Server Error");
							setHeader("Content-Length", Integer.toString(pageBytes.length));
							setHeader("Content-Type", "text/html; charset=utf-8");
							setHeader("Connection", "close");
							writeHeaders();
							out.write(pageBytes);
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
				try {
					sock.close();
				} catch (Exception e) {
				}
			}
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

	void closeRequest() {
		cleanupIsExplicit = true;
		postRequestCleanup();
		if (Thread.currentThread() == handlerThread) {
			cleanedUpOnHandlerThread = true;
			return;
		}
		if (!keepAlive || mustEndConnection) {
			if (!noAutoClose) {
				try {
					sock.close();
				} catch (Exception e) {
				}
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

	private static String deURLEncode(String encoded) {
		byte abyte0[] = encoded.getBytes();
		int i = 0;
		byte abyte1[] = new byte[encoded.length()];
		for (int j = 0; j < abyte0.length; j++) {
			if (abyte0[j] == 37) {
				byte abyte3[] = {
					abyte0[j + 1], abyte0[j + 2]
				};
				byte byte0 = (byte) Integer.parseInt(new String(abyte3), 16);
				j += 2;
				abyte1[i++] = byte0;
			} else {
				abyte1[i++] = abyte0[j];
			}
		}

		byte abyte2[] = new byte[i];
		System.arraycopy(abyte1, 0, abyte2, 0, i);
		return new String(abyte2);
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
			requestURI = deURLEncode(requestURI);
			long incomingContentLength = -1L;
			String incomingContentType = "application/x-octet-stream";
			referer = null;
			host = null;
			userAgent = null;
			String forceHost = null;
			sock.setSoTimeout(60000);
			try {
				Util.readHeaders(in, headers, server.getHeaderSizeLimit());
			} catch (TooBigException e) {
				tooBig();
				return;
			}
			String expect = null;
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
								if (CloudFlare.isCloudFlare(forwardedFor[i]) || server.isIPTrusted(forwardedFor[i])) {
									continue;
								}
								inetAddress = InetAddress.getByName(forwardedFor[i]);
								ip = inetAddress.getHostAddress();
								break;
							}
						}
					} else if (key.equalsIgnoreCase("CF-Connecting-IP")) { // CloudFlare gateway
						if (CloudFlare.isCloudFlare(realInetAddress) || server.isIPTrusted(realInetAddress.getHostAddress())) {
							inetAddress = InetAddress.getByName(val);
							ip = inetAddress.getHostAddress();
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
								ckey = fixString(deURLEncode(ckey));
								cval = fixString(deURLEncode(cval));
								cookies.put(ckey, cval);
							} else {
								cookies.put(fixString(deURLEncode(cookiePart)), "");
							}
						}
					} else if (key.equalsIgnoreCase("Content-Length")) {
						try {
							incomingContentLength = Long.parseLong(val);
						} catch (Exception e) {
							badRequest();
							return;
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
						if (val.equalsIgnoreCase("close")) {
							cannotKeepAlive = true;
						}
					} else if (key.equalsIgnoreCase("Expect")) {
						expect = val;
					}
				}
			}
			if (forceHost != null) {
				host = forceHost;
			}
			if (!requestURI.startsWith("/")) {
				badRequest();
				return;
			}
			int postLimit = server.getPostLimit();
			long uploadLimit = server.getUploadLimit();
			if (expect != null) {
				if (expect.equalsIgnoreCase("100-continue")) {
					if ((uploadLimit > 0 && incomingContentLength > uploadLimit)
							|| (postLimit > 0
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
			boolean incomingIsChunked = false;
			{
				List<String> encodingL = headers.get("Transfer-Encoding");
				if (encodingL != null && !encodingL.isEmpty()) {
					String encoding = encodingL.get(0);
					if (encoding != null && encoding.equals("chunked")) {
						incomingIsChunked = true;
					}
				}
			}
			boolean hasStream = incomingContentLength >= 0 || incomingIsChunked;
			if (hasStream) {
				InputStream stream;
				if (incomingIsChunked) {
					stream = new ChunkedInputStream(in);
				} else {
					stream = new SubInputStream(in, incomingContentLength);
				}
				contentStream = new EOFInputStream(stream);
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
											int fieldNamePos = val.indexOf("name=") + 5;
											int fieldNamePosAfterBoundary = val.indexOf(";", fieldNamePos);
											if (fieldNamePos != 4) { // -1 + 5 = 4, added +5 to fieldNamePos above
												if (fieldNamePosAfterBoundary == -1) {
													fieldName = val.substring(fieldNamePos);
												} else {
													fieldName = val.substring(fieldNamePos, fieldNamePosAfterBoundary);
												}
												if (fieldName.startsWith("\"") && fieldName.endsWith("\"")) {
													fieldName = fieldName.substring(1, fieldName.length() - 1);
												}
											}
											int fileNamePos = val.indexOf("filename=") + 9;
											int fileNamePosAfterBoundary = val.indexOf(";", fileNamePos);
											if (fileNamePos != 8) { // -1 + 9 = 8, added +9 to fileNamePos above
												if (fileNamePosAfterBoundary == -1) {
													fileName = val.substring(fileNamePos);
												} else {
													fileName = val.substring(fileNamePos, fileNamePosAfterBoundary);
												}
												if (fileName.startsWith("\"") && fileName.endsWith("\"")) {
													fileName = fileName.substring(1, fileName.length() - 1);
												}
												fileName = Util.urldecode(fileName, false);
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
										val = Util.readFullyAsString(partReader, postLimit - currentPostSize - fieldName.length() - 2);
									} else {
										val = Util.readFullyAsString(partReader);
									}
									post.put(fieldName, val);
									currentPostSize += fieldName.length() + val.length() + 2;
									if (currentPostSize > postLimit) {
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
				} else if (incomingContentType.toLowerCase().contains("application/x-www-form-urlencoded")) {
					try {
						if (postLimit > 0 && incomingContentLength > postLimit) {
							tooBig();
							return;
						}
						int icl = (int) incomingContentLength;
						byte buffer[] = new byte[icl];
						int writePtr = 0;
						int amountRead;
						for (int i = icl; i > 0; i -= amountRead) {
							amountRead = contentStream.read(buffer, writePtr, icl - writePtr);
							writePtr += amountRead;
						}
						postData = new String(buffer);
						Util.parseQueryString(postData, post);
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
					finalContentStream.setEofSequence(null);
					int read = -1;
					try {
						read = finalContentStream.read();
					} catch (Exception e) {
					}
					if (read != -1) {
						mustEndConnection = true;
					}
				});
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

	void writeHeaders() throws IOException {
		if (wrote) {
			return;
		}
		wrote = true;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Util.writeCRLF("HTTP/1.1 " + responseHeader, baos);
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			String header = entry.getKey();
			List<String> list = entry.getValue();
			for (String str : list) {
				Util.writeCRLF(header + ": " + str, baos);
				if (header.equalsIgnoreCase("Content-Length")) {
					try {
						outputContentLength = Long.parseLong(str);
					} catch (Exception e) {
					}
				}
			}
		}
		Util.writeCRLF("", baos);
		write(baos.toByteArray());
		OutputStream streamToUse;
		if (chunked) {
			streamToUse = chunkOutputStream = new ChunkedOutputStream(out);
		} else {
			streamToUse = out;
		}
		contentOutStream = bufferDisabled ? streamToUse : new BufferedOutputStream(streamToUse);
	}

	void disableBuffer() throws IOException {
		bufferDisabled = true;
		if (contentOutStream instanceof BufferedOutputStream) {
			contentOutStream.flush();
			contentOutStream = chunkOutputStream == null ? out : chunkOutputStream;
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

	private static String fixString(String s) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(s.getBytes()), "UTF-8"));
			s = "";
			String s2;
			while ((s2 = reader.readLine()) != null) {
				s += (s.equals("") ? "" : "\n") + s2;
			}
		} catch (Exception e) {
		}
		return s;
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

	boolean isSocketSecure() {
		return (sock instanceof SSLSocket);
	}

	final HTTPServer server;
	boolean wrote = false;
	long writtenBodyLength = 0L;
	long outputContentLength = -1L;
	private boolean bufferDisabled = false;
	ChunkedOutputStream chunkOutputStream = null;
	OutputStream contentOutStream = null;
	private Socket sock = null;
	private InputStream rawIn = null;
	private InputStream in = null;
	@Deprecated
	ServerSocket sourceSocket = null;
	private OutputStream out = null;
	private InetAddress realInetAddress = null;
	private InetAddress inetAddress = null;
	String ip = null;
	private boolean mustEndConnection = false;
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
