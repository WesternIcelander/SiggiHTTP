package io.siggi.http;

import io.siggi.http.util.Util;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The object to send responses to. This object extends
 * <code>ObjectStream</code> so you can send data directly to
 * <code>HTTPResponse</code>.
 * <p>
 * You must write all headers before sending any data.
 */
public class HTTPResponse extends OutputStream {

	private final HTTPRequest request;
	private boolean chunked = true;
	private boolean closed = false;

	private void throwIOIfClosed() throws IOException {
		if (closed) throw new IOException("Already closed");
	}

	private void throwIllegalIfClosed() {
		if (closed) throw new IllegalStateException("Already closed");
	}

	/**
	 * Sets the content length and disables chunked transfer encoding. If you
	 * use -1, then content length will be unset, and chunked transfer encoding
	 * is enabled.
	 *
	 * @param contentLength the content length
	 */
	public void contentLength(long contentLength) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		if (contentLength == -1) {
			request.handler.deleteHeader("Content-Length");
			request.handler.setHeader("Transfer-Encoding", "chunked");
			chunked = true;
			request.handler.chunked = true;
		} else {
			request.handler.setHeader("Content-Length", Long.toString(contentLength));
			request.handler.deleteHeader("Transfer-Encoding");
			chunked = false;
			request.handler.chunked = false;
		}
	}

	/**
	 * Sets the content length and disables chunked transfer encoding. If you
	 * use -1, then content length will be unset, and chunked transfer encoding
	 * is enabled.
	 *
	 * @param contentLength
	 */
	public void contentLength(int contentLength) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		if (contentLength == -1) {
			request.handler.deleteHeader("Content-Length");
			request.handler.setHeader("Transfer-Encoding", "chunked");
			chunked = true;
			request.handler.chunked = true;
		} else {
			request.handler.setHeader("Content-Length", Integer.toString(contentLength));
			request.handler.deleteHeader("Transfer-Encoding");
			chunked = false;
			request.handler.chunked = false;
		}
	}

	public void setContentType(String contentType) {
		throwIllegalIfClosed();
		if (contentType.equals("text/html")) {
			contentType = "text/html; charset=utf-8";
		}
		setHeader("Content-Type", contentType);
	}

	HTTPResponse(HTTPRequest request) {
		this.request = request;
	}

	/**
	 * Sends the file to the client. If the client sent an ETag as part of the
	 * request, and the ETag matches the server computed ETag, this will return
	 * a 304 Not Modified. If the file does not exist, this method does nothing,
	 * allowing a 404 handler to write to the response instead.
	 */
	public void returnFile(File file) throws IOException {
		returnFile(file, null);
	}

	public void returnFile(File file, String contentType) throws IOException {
		throwIOIfClosed();
		if (!file.exists()) {
			return;
		}
		if (!request.method.equals("GET") && !request.method.equals("HEAD")) {
			switch (request.method) {
				case "OPTIONS": {
					setHeader("204 No Content");
					setHeader("Allow", "OPTIONS, GET, HEAD");
					sendHeaders();
				}
				break;
				default: {
					setHeader("405 Method Not Allowed");
					setContentType("text/plain");
					write("405 Method Not Allowed");
				}
				break;
			}
			return;
		}
		boolean partialContent = false;
		long partialStart = 0L;
		long partialEnd = 0L;
		List<String> rangeHeaders = request.headers.get("Range");
		if (rangeHeaders != null && !rangeHeaders.isEmpty()) {
			try {
				if (rangeHeaders.size() == 1) {
					String rangeHeader = rangeHeaders.get(0);
					if (rangeHeader.startsWith("bytes=")) {
						rangeHeader = rangeHeader.substring(6).trim();
						int pos = rangeHeader.indexOf("-");
						if (pos == -1) {
							partialStart = Long.parseLong(rangeHeader);
							partialContent = true;
						} else {
							String l = rangeHeader.substring(0, pos);
							String r = rangeHeader.substring(pos + 1);
							partialStart = Long.parseLong(l);
							if (r.isEmpty()) {
								partialEnd = -1L;
							} else {
								partialEnd = Long.parseLong(r);
							}
							partialContent = true;
						}
					}
				}
			} catch (Exception e) {
			}
		}
		String eTag = null;
		String extension = "";
		String fname = file.getName();
		if (fname.contains(".")) {
			extension = fname.substring(fname.lastIndexOf(".") + 1);
		}
		try { // Compute ETag
			eTag = computeEtag(file);
		} catch (Exception e) {
		}
		boolean sendFile = true;
		if (eTag != null) { // Compare ETag
			List<String> clientETag = request.headers.get("If-None-Match");
			if (clientETag != null && !clientETag.isEmpty()) {
				String theETag = clientETag.get(0);
				if (theETag.equals(eTag) || theETag.equals("\"" + eTag + "\"")) {
					sendFile = false;
				}
			}
			List<String> rangeETag = request.headers.get("If-Range");
			if (rangeETag != null && !rangeETag.isEmpty()) {
				String theETag = rangeETag.get(0);
				if (!theETag.equals(eTag) && !theETag.equals("\"" + eTag + "\"")) {
					partialContent = false;
				}
			}
		}
		List<String> ifModifiedSinceH = request.headers.get("If-Modified-Since");
		if (ifModifiedSinceH != null && !ifModifiedSinceH.isEmpty()) {
			long check = parseDate(ifModifiedSinceH.get(0));
			check -= check % 1000;
			long lastMod = file.lastModified();
			lastMod -= lastMod % 1000;
			if (lastMod <= check) {
				sendFile = false;
			}
		}
		setHeader("Accept-Ranges", "bytes");
		if (eTag != null) {
			setHeader("ETag", "\"" + eTag + "\"");
		}
		if (sendFile) {
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				long fileLength = raf.length();
				if (partialContent) {
					if (partialEnd == -1L) {
						partialEnd = fileLength - 1;
					}
					if (partialEnd < partialStart || partialEnd > fileLength - 1) {
						partialContent = false;
					}
				}
				long amountToWrite = fileLength;
				if (partialContent) {
					raf.seek(partialStart);
					amountToWrite = partialEnd - partialStart + 1;
					setHeader("206 Partial Content");
					setHeader("Content-Range", "bytes " + partialStart + "-" + partialEnd + "/" + fileLength);
				} else {
					setHeader("200 OK");
				}
				setHeader("Content-Length", Long.toString(amountToWrite));
				if (contentType == null) {
					setHeader("Content-Type", request.getMimeType(extension));
				} else {
					setHeader("Content-Type", contentType);
				}
				setHeader("Last-Modified", formatDate(file.lastModified()));
				request.handler.disableBuffer();
				sendHeaders();
				int c = 0;
				byte[] b = new byte[16384];
				long amountWritten = 0L;
				while (amountWritten < amountToWrite && (c = raf.read(b, 0, (int) Math.min((long) b.length, amountToWrite - amountWritten))) != -1) {
					write(b, 0, c);
					amountWritten += c;
				}
			}
		} else {
			setHeader("304 Not Modified");
			sendHeaders();
		}
	}

	public void handleRequestWithFile(File f) throws IOException {
		throwIOIfClosed();
		switch (request.method.toUpperCase()) {
			case "GET":
			case "HEAD": {
				returnFile(f);
			}
			break;
			case "OPTIONS": {
				setHeader("204 No Content");
				setHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE");
				sendHeaders();
			}
			case "PUT": {
				File parentFile = f.getParentFile();
				if (!parentFile.exists()) {
					parentFile.mkdirs();
				}
				File tmpFile = new File(parentFile, f.getName() + ".httpupload." + Util.randomChars(6));
				try {
					try (FileOutputStream out = new FileOutputStream(tmpFile)) {
						Util.copy(request.inStream, out);
					}
					if (tmpFile.exists()) {
						if (f.exists()) {
							f.delete();
						}
						tmpFile.renameTo(f);
					} else {
						setHeader("500 Internal Server Error");
						setContentType("text/plain");
						sendHeaders();
						write("The file was not saved. Please try again.");
						return;
					}
				} finally {
					if (tmpFile.exists())
						tmpFile.delete();
				}
				setHeader("204 No Content");
				sendHeaders();
			}
			break;
			case "DELETE": {
				f.delete();
				setHeader("204 No Content");
				sendHeaders();
			}
			break;
			default: {
				setHeader("405 Method Not Allowed");
				setContentType("text/plain");
				write("405 Method Not Allowed");
			}
			break;
		}
	}

	/**
	 * Disable buffering for this response.
	 *
	 * @throws IOException if something goes wrong
	 */
	public void disableBuffer() throws IOException {
		throwIOIfClosed();
		request.handler.disableBuffer();
	}

	/**
	 * Tells the client that the resource has been moved to a new location
	 * permanently.
	 */
	public void movedPermanently(String url) throws IOException {
		throwIOIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		setHeader("301 Moved Permanently");
		setHeader("Location", url);
		contentLength(0);
		sendHeaders();
		return;
	}

	/**
	 * Redirects the client to another location to complete the request.
	 */
	public void redirect(String url) throws IOException {
		throwIOIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		setHeader("302 Found");
		setHeader("Location", url);
		contentLength(0);
		sendHeaders();
		return;
	}

	/**
	 * Indicates that the request has completed, and the browser go to the
	 * specified location.
	 */
	public void completedRedirect(String url) throws IOException {
		throwIOIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		setHeader("303 See Other");
		setHeader("Location", url);
		contentLength(0);
		sendHeaders();
		return;
	}

	/**
	 * Sets the <code>Keep-Alive</code> and <code>Connection</code> headers as
	 * well as saves a flag in the HTTPServer to keep the current connection
	 * active if <code>keepAlive</code> is true. If the client specifically
	 * requested in the request headers that Keep-Alive be turned off, this
	 * method does nothing.
	 *
	 * @deprecated Whether a connection is kept alive is not up to website code.
	 */
	@Deprecated
	public void keepAlive(boolean keepAlive) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		request.handler.keepAlive(keepAlive);
	}

	/**
	 * Formats a date for use with Set-Cookie or use in an HTTP header. The time
	 * will be returned in GMT.
	 */
	public String formatDate(long date) {
		return request.handler.formatDate(date);
	}

	/**
	 * Parses an HTTP date.
	 */
	public long parseDate(String date) {
		return request.handler.parseDate(date);
	}

	/**
	 * Close the response. This must be called if {@link HTTPRequest#openResponse()} was called.
	 */
	@Override
	public void close() throws IOException {
		if (closed) return;
		markClosed();
		request.handler.closeRequest();
	}

	void markClosed() {
		closed = true;
	}

	boolean isClosed() {
		return closed;
	}

	/**
	 * This will flush unsent data in the connection immediately.
	 */
	@Override
	public void flush() throws IOException {
		throwIOIfClosed();
		if (request.handler.contentOutStream != null)
			request.handler.contentOutStream.flush();
	}

	/**
	 * Writes all bytes in <code>b</code>
	 */
	@Override
	public void write(byte[] b) throws IOException {
		throwIOIfClosed();
		write(b, 0, b.length);
	}

	/**
	 * Writes <code>len</code> bytes in <code>b</code> starting from
	 * <code>off</code>
	 */
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		throwIOIfClosed();
		if (!request.handler.wrote) {
			request.handler.writeHeaders();
		}
		request.handler.prewrite(len);
		if (request.handler.usingHeadMethod || len <= 0) {
			return;
		}
		request.handler.contentOutStream.write(b, off, len);
	}

	/**
	 * Writes <code>b</code> as one byte.
	 */
	@Override
	public void write(int b) throws IOException {
		throwIOIfClosed();
		if (!request.handler.wrote) {
			request.handler.writeHeaders();
		}
		request.handler.prewrite(1);
		if (request.handler.usingHeadMethod) {
			return;
		}
		request.handler.contentOutStream.write(b);
	}

	/**
	 * Send the headers. Calling this is optional, and is done automatically
	 * when you send data, however, if the headers are not sent, the server will
	 * assume not found and produce a 404, so if you're sending a zero length
	 * body, you should call this method.
	 *
	 * @throws IOException if something goes wrong
	 */
	public void sendHeaders() throws IOException {
		throwIOIfClosed();
		if (!request.handler.wrote) {
			request.handler.writeHeaders();
		}
	}

	/**
	 * Writes <code>string</code> as UTF-8 to the output stream.
	 */
	public void write(String string) throws IOException {
		write(getBytes(string));
	}

	/**
	 * Formats the string as UTF-8 and returns it as a byte array.
	 */
	public byte[] getBytes(String str) {
			return str.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Sets the response header. (200 OK, 404 Not Found, etc) Do NOT include
	 * HTTP/1.1 in front.
	 */
	public void setHeader(String responseHeader) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		request.handler.setHeader(responseHeader);
		if (Util.isNoContent(Integer.parseInt(responseHeader.substring(0, responseHeader.indexOf(" "))))) {
			deleteHeader("Transfer-Encoding");
			deleteHeader("Content-Type");
			chunked = false;
			request.handler.chunked = false;
		}
	}

	/**
	 * Adds a header to the result headers. Unlike
	 * <code>setHeader(String,String)</code>, this only adds a header with the
	 * same key, rather than removing all others with the same key.
	 */
	public void addHeader(String key, String value) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		if (key.equalsIgnoreCase("Content-Length")) {
			chunked = false;
			request.handler.chunked = false;
		}
		request.handler.addHeader(key, value);
	}

	/**
	 * When using Set-Cookie, adds one cookie header. With all other headers,
	 * replaces all headers with the key <code>key</code> and with the new
	 * header.
	 */
	public void setHeader(String key, String value) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		if (key.equalsIgnoreCase("Content-Length")) {
			chunked = false;
			request.handler.chunked = false;
		}
		request.handler.setHeader(key, value);
	}

	/**
	 * Get all headers with the specified key.
	 */
	public String[] getHeader(String key) {
		return request.handler.getHeader(key);
	}

	/**
	 * Removes all headers with the specified <code>key</code>.
	 */
	public void deleteHeader(String key) {
		throwIllegalIfClosed();
		if (request.handler.wrote) {
			throw new IllegalStateException("Headers already sent, and cannot be modified!");
		}
		request.handler.deleteHeader(key);
	}

	/**
	 * Returns true if any data at all was written back to this request.
	 *
	 * @return true if any data was written
	 */
	public boolean alreadyWrote() {
		return request.handler.wrote;
	}

	/**
	 * Tell the client not to cache this resource.
	 */
	public void doNotCache() {
		throwIllegalIfClosed();
		request.handler.doNotCache();
	}

	/**
	 * Tell the client to cache this resource for the specified number of
	 * seconds.
	 *
	 * @param maxAge number of seconds
	 */
	public void cache(long maxAge) {
		throwIllegalIfClosed();
		request.handler.cache(maxAge);
	}

	public String computeEtag(File file) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
			String ap = file.getAbsolutePath();
			while (ap.endsWith("/")) {
				ap = ap.substring(0, ap.length() - 1);
			}
			byte[] bytes = (ap + Long.toString(file.lastModified())).getBytes();
			messageDigest.update(bytes);
			byte[] digest = messageDigest.digest();
			messageDigest.reset();
			ByteArrayOutputStream digestStream = new ByteArrayOutputStream();
			for (int i = 0; i < digest.length; i++) {
				String digestByte = Integer.toString(digest[i] & 0xff, 16);
				while (digestByte.length() < 2) {
					digestByte = "0" + digestByte;
				}
				digestStream.write(digestByte.getBytes());
			}
			return new String(digestStream.toByteArray());
		} catch (IOException | NoSuchAlgorithmException e) {
			return null;
		}
	}

	public Socket upgradeConnection(String upgradeHeader) throws IOException {
		throwIOIfClosed();
		setHeader("101 Switching Protocols");
		deleteHeader("Content-Type");
		deleteHeader("Keep-Alive");
		deleteHeader("Transfer-Encoding");
		deleteHeader("Content-Length");
		if (upgradeHeader != null) {
			setHeader("Connection", "Upgrade");
			setHeader("Upgrade", upgradeHeader);
		}
		sendHeaders();
		return request.handler.upgradeSocket();
	}
}
