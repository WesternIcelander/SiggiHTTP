package io.siggi.http.simpleresponders;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponder;
import io.siggi.http.defaultresponders.DefaultResponder;
import io.siggi.http.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.util.Arrays;

import static io.siggi.http.util.HTMLUtils.htmlentities;

public class VirtualFileSystemResponder implements HTTPResponder {

	/**
	 * The path that the directory or file at the real path is mounted at on the
	 * virtual filesystem on the HTTP server.
	 */
	public final String mountPath;
	/**
	 * The real path to the directory of file that is mounted on the virtual
	 * filesystem on the HTTP server.
	 */
	public final String realPath;

	private boolean allowWriteAccess = false;
	private HTTPResponder filter = null;

	/**
	 * Creates a simple responder that links to a file
	 * @param mountPath path to mount this filesystem at
	 * @param realPath the path to the real filesystem
	 */
	public VirtualFileSystemResponder(String mountPath, String realPath) {
		while (mountPath.endsWith("/")) {
			mountPath = mountPath.substring(0, mountPath.length() - 1);
		}
		while (realPath.endsWith("/")) {
			realPath = realPath.substring(0, realPath.length() - 1);
		}
		this.mountPath = mountPath;
		this.realPath = realPath;
	}

	/**
	 * Writes a response to the HTTPRequest
	 * @param request the request to process
	 * @throws Exception if something goes wrong
	 */
	@Override
	public void respond(HTTPRequest request) throws Exception {
		if (filter != null) {
			filter.respond(request);
			if (request.alreadyWrote()) {
				return;
			}
		}
		if (request.url.toLowerCase().startsWith(mountPath + "/") || request.url.toLowerCase().equals(mountPath)) {
			String access = request.url.substring(mountPath.length());
			File file = new File(realPath + access);
			if (access.contains("/../") || access.endsWith("/..")) {
				String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>400 Bad Request</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>400 Bad Request</h1><br>\nQuit tryna hack me!<br>\n<br>\n<hr>\n" + request.getServerSignature() + "<br>\n</body>\n</html>";
				byte pageBytes[] = request.response.getBytes(page);
				request.response.setHeader("400 Bad Request");
				request.response.contentLength((long) pageBytes.length);
				request.response.setHeader("Content-Type", "text/html; charset=utf-8");
				request.response.write(pageBytes);
				return;
			}
			if (access.endsWith("/")) {
				File indexFile1 = new File(file, "index.html");
				File indexFile2 = new File(file, "index.htm");
				if (indexFile1.isFile()) {
					file = indexFile1;
					access += "index.html";
				} else if (indexFile2.isFile()) {
					file = indexFile2;
					access += "index.htm";
				}
			}
			if (file.isDirectory()) {
				if (!access.endsWith("/")) {
					request.response.setHeader("302 Found");
					request.response.setHeader("Location", mountPath + access + "/");
					request.response.contentLength(0);
					request.response.setHeader("Content-Type", "text/plain");
					request.response.write(new byte[0]);
				} else {
					File checkListing = new File(file, "denylist");
					if (checkListing.exists()) {
						String page = "<!DOCTYPE html>\n<html>\n<head>\n<title>403 Forbidden</title>\n" + DefaultResponder.STYLE + "</head>\n<body>\n<h1>403 Forbidden</h1><br>\nThe contents of this directory cannot be listed.<br>\n<br>\n<hr>\n" + request.getServerSignature() + "<br>\n</body>\n</html>";
						byte pageBytes[] = request.response.getBytes(page);
						request.response.setHeader("403 Forbidden");
						request.response.contentLength((long) pageBytes.length);
						request.response.setHeader("Content-Type", "text/html; charset=utf-8");
						request.response.write(pageBytes);
					} else {
						// Generate a directory listing...
						ByteArrayOutputStream out = new ByteArrayOutputStream();
						OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
						writer.write("<!DOCTYPE html>\n<html>\n<head>\n<title>Index of " + htmlentities(request.url) + "</title>\n");
						writer.write(DefaultResponder.STYLE);
						writer.write("</head>\n");
						writer.write("<body>\n");
						writer.write("<h1>Index of " + htmlentities(request.url) + "</h1><br>\n");
						if (new File(file, ".htaccess").exists()) {
							writer.write("<b>WARNING:</b> This HTTP server does not support Apache .htaccess files.  To remove this message, delete the .htaccess file from this directory.<br>\n");
						}
						File[] fileList = file.listFiles();
						Arrays.sort(fileList, (a, b) -> {
							if (a.isDirectory()) {
								if (!b.isDirectory()) {
									return -1;
								}
							} else if (b.isDirectory()) {
								return 1;
							}
							return a.getName().compareTo(b.getName());
						});
						writer.write("<table>\n<tr>\n<td>File name</td><td>Size</td>\n</tr>\n");
						if (!request.url.equals("/")) {
							writer.write("<tr>\n<td><a href=\"..\">Up a directory</a></td><td>--</td>\n</tr>\n");
						}
						for (File fileToList : fileList) {
							String fileName = fileToList.getName();
							if (fileName.startsWith(".") || fileName.contains(".httpupload.")) {
								continue;
							}
							String href = htmlentities(Util.urlencode(fileName, "%20"));
							String displayName = htmlentities(fileName);
							if (fileToList.isDirectory()) {
								writer.write("<tr>\n<td><a href=\"" + href + "/\">" + displayName + "</a></td><td>--</td>\n</tr>\n");
							} else if (fileToList.isFile()) {
								long fileSize = fileToList.length();
								String sizeString = fileSize + " bytes";
								if (fileSize == 1L) {
									sizeString = "1 byte";
								} else if (fileSize >= (1024L * 1024L * 1024L * 4L)) {
									double theSize = ((double) fileSize) / (1024.0 * 1024.0 * 1024.0);
									theSize = Math.floor(theSize * 100.0) / 100.0;
									sizeString = theSize + " GB";
								} else if (fileSize >= (1024L * 1024L * 4L)) {
									double theSize = ((double) fileSize) / (1024.0 * 1024.0);
									theSize = Math.floor(theSize * 100.0) / 100.0;
									sizeString = theSize + " MB";
								} else if (fileSize >= (1024L * 4L)) {
									double theSize = ((double) fileSize) / (1024.0);
									theSize = Math.floor(theSize * 100.0) / 100.0;
									sizeString = theSize + " kB";
								}

								writer.write("<tr>\n<td><a href=\"" + href + "\">" + displayName + "</a></td><td>" + sizeString + "</td>\n");
							}
						}
						writer.write("</table>\n<br>\n");
						writer.write("<br>\n<hr>\n" + request.getServerSignature() + "<br>\n</body>\n</html>\n");
						writer.flush();
						byte[] pageBytes = prepareHtml(new String(out.toByteArray(), "UTF-8"), request).getBytes("UTF-8");
						request.response.setHeader("200 OK");
						request.response.contentLength((long) pageBytes.length);
						request.response.write(pageBytes);
					}
				}
			} else {
				if (!access.endsWith("/")) {
					if (allowWriteAccess)
						request.response.handleRequestWithFile(file);
					else
						request.response.returnFile(file);
				}
			}
		}
	}
	private HTTPResponder responder404 = null;

	public void set404(HTTPResponder responder) {
		responder404 = responder;
	}

	@Override
	public void respond404(HTTPRequest request) throws Exception {
		if (responder404 != null) {
			responder404.respond404(request);
		}
	}

	protected String prepareHtml(String html, HTTPRequest request) {
		return html;
	}

	public VirtualFileSystemResponder setAllowWriteAccess(boolean allowWriteAccess) {
		this.allowWriteAccess = allowWriteAccess;
		return this;
	}

	public VirtualFileSystemResponder setFilter(HTTPResponder filter) {
		this.filter = filter;
		return this;
	}
}
