package io.siggi.http.util;

import io.siggi.http.exception.TooBigException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class Util {

	private static String readLine(InputStream in, int sizeLimit) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(sizeLimit > 0 ? Math.min(16384, sizeLimit) : 16384);
		int c;
		boolean cr = false;
		boolean r = false;
		while ((c = in.read()) != -1) {
			if (baos.size() >= sizeLimit) {
				throw new TooBigException();
			}
			r = true;
			if (c == 0x0D) {
				cr = true;
			} else if (c == 0x0A) {
				break;
			} else {
				if (cr) {
					cr = false;
					baos.write(0x0D);
				}
				baos.write(c);
			}
		}
		if (!r) {
			return null;
		}
		return new String(baos.toByteArray(), "UTF-8");
	}

	public static Map<String, List<String>> readHeaders(InputStream in, int sizeLimit) throws IOException {
		Map<String, List<String>> headers = new CaseInsensitiveHashMap<>();
		if (!readHeaders(in, headers, sizeLimit)) {
			return null;
		}
		return Collections.unmodifiableMap(headers);
	}

	public static boolean readHeaders(InputStream in, Map<String, List<String>> headers, int sizeLimit) throws IOException {
		int totalSize = 0;
		String line;
		String key = null;
		String val = null;
		boolean readSomething = false;
		while ((line = readLine(in, sizeLimit)) != null) {
			totalSize += line.length() + 2;
			if (sizeLimit > 0 && totalSize > sizeLimit) {
				throw new TooBigException();
			}
			readSomething = true;
			if (line.isEmpty()) {
				break;
			}
			String trim = line.trim();
			if (trim.isEmpty()) {
				continue;
			}
			if (line.charAt(0) != trim.charAt(0)) {
				val += trim;
			} else {
				if (key != null && val != null) {
					List<String> h = headers.get(key);
					if (h == null) {
						headers.put(key, h = new ArrayList<>());
					}
					h.add(val);
					key = val = null;
				}
				int pos = line.indexOf(":");
				if (pos == -1) {
					continue;
				}
				key = line.substring(0, pos).trim();
				val = line.substring(pos + 1).trim();
			}
		}
		if (key != null && val != null) {
			List<String> h = headers.get(key);
			if (h == null) {
				headers.put(key, h = new ArrayList<>());
			}
			h.add(val);
		}
		return readSomething;
	}

	public static Map<String, String> parseQueryString(String str) {
		try {
			return parseQueryString(new ByteArrayInputStream(str.getBytes()));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void parseQueryString(String str, Map<String, String> map) {
		try {
			parseQueryString(new ByteArrayInputStream(str.getBytes()), map);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static Map<String, String> parseQueryString(InputStream in) throws IOException {
		Map<String, String> map = new HashMap<>();
		parseQueryString(in, map);
		return map;
	}

	public static void parseQueryString(InputStream in, Map<String, String> map) throws IOException {
		BufferedInputStream inn = new BufferedInputStream(in);
		ByteArrayOutputStream key = new ByteArrayOutputStream();
		ByteArrayOutputStream val = new ByteArrayOutputStream();
		ByteArrayOutputStream writeTo = key;
		int c;
		try {
			while ((c = inn.read()) != -1) {
				if (c == 0x3D && writeTo == key) { // =
					writeTo = val;
				} else if (c == 0x25) { // %
					inn.mark(2);
					int a = inn.read();
					int b = inn.read();
					try {
						String s = new String(new byte[]{(byte) a, (byte) b});
						writeTo.write(Integer.parseInt(s, 16));
					} catch (NumberFormatException e) {
						writeTo.write(c);
						inn.reset();
					}
				} else if (c == 0x2B) { // +
					writeTo.write(0x20);
				} else if (c == 0x26) { // &
					String k = new String(key.toByteArray(), "UTF-8");
					String v = new String(val.toByteArray(), "UTF-8");
					if (!k.isEmpty() || !v.isEmpty()) {
						map.put(k, v);
					}
					writeTo = key;
					key.reset();
					val.reset();
				} else {
					writeTo.write(c);
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
		String k = new String(key.toByteArray(), "UTF-8");
		String v = new String(val.toByteArray(), "UTF-8");
		if (!k.isEmpty() || !v.isEmpty()) {
			map.put(k, v);
		}
	}

	public static String headerUrlEncode(String str) {
		return urlencode(str, " ").replace("\"", "%22");
	}

	public static String urlencode(String str) {
		return urlencode(str, "+");
	}

	public static String urlencode(String str, String spaceEncoding) {
		byte[] bytes;
		try {
			bytes = str.getBytes("UTF-8");
		} catch (UnsupportedEncodingException ex) {
			throw new RuntimeException(ex);
		}
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			if (b == 0x20) {
				sb.append(spaceEncoding);
			} else if (b == (byte) 0x24 // $
					|| b == (byte) 0x26 // &
					|| b == (byte) 0x2B // +
					|| b == (byte) 0x2C // ,
					|| b == (byte) 0x2F // /
					|| b == (byte) 0x3A // :
					|| b == (byte) 0x3B // ;
					|| b == (byte) 0x3D // =
					|| b == (byte) 0x3F // ?
					|| b == (byte) 0x40 // @
					|| b == (byte) 0x20 // space
					|| b == (byte) 0x22 // "
					|| b == (byte) 0x3C // <
					|| b == (byte) 0x3E // >
					|| b == (byte) 0x23 // #
					|| b == (byte) 0x25 // %
					|| b == (byte) 0x7B // {
					|| b == (byte) 0x7D // }
					|| b == (byte) 0x7C // |
					|| b == (byte) 0x5C // \
					|| b == (byte) 0x5E // ^
					|| b == (byte) 0x7E // ~
					|| b == (byte) 0x5B // [
					|| b == (byte) 0x5D // ]
					|| b == (byte) 0x60 // `
					|| b <= (byte) 0x1F // non printable
					|| b >= (byte) 0x7F // non ASCII
					) {
				int a = b & 0xff;
				sb.append("%");
				if (a < 16) {
					sb.append("0");
				}
				sb.append(Integer.toString(a, 16).toUpperCase());
			} else {
				sb.append((char) b);
			}
		}
		return sb.toString();
	}

	public static String urldecode(String str) {
		return urldecode(str, true);
	}

	public static String urldecode(String str, boolean convertPlusToSpace) {
		byte[] bytes;
		try {
			bytes = str.getBytes("UTF-8");
		} catch (UnsupportedEncodingException ex) {
			throw new RuntimeException(ex);
		}
		BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(bytes));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int c;
		try {
			while ((c = in.read()) != -1) {
				if (c == 0x2B && convertPlusToSpace) { // +
					out.write(0x20);
				} else if (c == 0x25) { // %
					in.mark(2);
					int a1 = in.read();
					int a2 = in.read();
					if (a1 != -1 && a2 != -1) {
						try {
							int a3 = Integer.parseInt(new String(new byte[]{(byte) a1, (byte) a2}), 16);
							out.write(a3);
						} catch (NumberFormatException nfe) {
							out.write(c);
							in.reset();
						}
					}
				} else {
					out.write(c);
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		try {
			return new String(out.toByteArray(), "UTF-8");
		} catch (UnsupportedEncodingException ex) {
			throw new RuntimeException(ex);
		}
	}

	public static byte[] readFully(InputStream in) throws IOException {
		return readFully(in, -1);
	}

	public static byte[] readFully(InputStream in, int maxLength) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int c;
		byte[] buffer = new byte[4096];
		while ((c = in.read(buffer, 0, buffer.length)) != -1) {
			baos.write(buffer, 0, c);
			if (maxLength >= 0 && baos.size() > maxLength) {
				throw new TooBigException();
			}
		}
		return baos.toByteArray();
	}

	public static void readFullyToBlackHole(InputStream in) throws IOException {
		int c;
		byte[] buffer = new byte[4096];
		do {
			c = in.read(buffer, 0, buffer.length);
		} while (c != -1);
	}

	public static String readFullyAsString(InputStream in) throws IOException {
		return new String(readFully(in), "UTF-8");
	}

	public static String readFullyAsString(InputStream in, int maxLength) throws IOException {
		return new String(readFully(in, maxLength), "UTF-8");
	}

	public static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[4096];
		int c;
		while ((c = in.read(buffer, 0, buffer.length)) != -1) {
			out.write(buffer, 0, c);
		}
	}

	public static String intToCommaString(int i) {
		String s = Integer.toString(i);
		if (s.length() <= 3) {
			return s;
		}
		String s1 = "";
		int j = s.length() % 3 - 3;
		for (int k = j; k < s.length(); k += 3) {
			s1 = s1 + (s1.length() <= 0 ? "" : ",") + s.substring(Math.max(0, k), Math.min(k + 3, s.length()));
		}
		return s1;
	}

	public static boolean isNoContent(int code) {
		switch (code) {
			case 204:
			case 304:
				return true;
		}
		return false;
	}

	public static void writeCRLF(String str, OutputStream out) throws IOException {
		out.write(str.getBytes("UTF-8"));
		out.write(0x0D);
		out.write(0x0A);
	}

	public static <T> Iterator<T> iterator(final Enumeration<T> enumeration) {
		return new Iterator<T>() {

			@Override
			public boolean hasNext() {
				return enumeration.hasMoreElements();
			}

			@Override
			public T next() {
				return enumeration.nextElement();
			}
		};
	}

	public static <T> Iterable<T> iterable(Enumeration<T> enumeration) {
		return iterable(iterator(enumeration));
	}

	public static <T> Iterable<T> iterable(final Iterator<T> iterator) {
		return () -> iterator;
	}

	public static String formatDate(long date) {
		return HTMLUtils.getSimpleDateFormat().format(new Date(date));
	}

	public static String getFileExtension(String file) {
		int slashPos = file.lastIndexOf("/");
		int dotPos = file.lastIndexOf(".");
		if (dotPos == -1 || dotPos < slashPos) {
			return "";
		}
		return file.substring(dotPos + 1);
	}

	private static final char[] randomDataCharset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
	public static String randomChars(int length) {
		SecureRandom sr = new SecureRandom();
		char[] result = new char[length];
		for (int i = 0; i < result.length; i++) {
			result[i] = randomDataCharset[sr.nextInt(randomDataCharset.length)];
		}
		return new String(result);
	}

	public static <V> V getFirstInList(List<V> list) {
		if (list == null) return null;
		return list.get(0);
	}

	public static Map<String,String> parseHeaderArguments(String header) {
		Map<String, String> map = new CaseInsensitiveHashMap<>();
		boolean inQuotes = false;
		boolean usedQuotes = false;
		StringBuilder key = new StringBuilder();
		ByteArrayOutputStream val = new ByteArrayOutputStream();
		OutputStreamWriter valWriter = new OutputStreamWriter(val, StandardCharsets.UTF_8);
		boolean readingKey = false;
		try {
			for (int i = 0; i < header.length(); i++) {
				char c = header.charAt(i);
				if (readingKey) {
					if (c == '=') {
						readingKey = false;
						inQuotes = false;
						usedQuotes = false;
						continue;
					}
					key.append(c);
				} else {
					if (c == '%') {
						i += 2;
						if (i >= header.length()) break;
						valWriter.write(Integer.parseInt(header.substring(i - 1, i + 1), 16));
						continue;
					}
					if (c == '"') {
						inQuotes = !inQuotes;
						if (!usedQuotes) {
							valWriter.flush();
							val.reset();
							usedQuotes = true;
						}
						continue;
					}
					if (inQuotes) {
						valWriter.write(c);
					} else if (c == ';') {
						valWriter.flush();
						String value = new String(val.toByteArray(), StandardCharsets.UTF_8);
						if (!usedQuotes) value = value.trim();
						map.put(key.toString().trim(), value);
						key.delete(0, key.length());
						val.reset();
						readingKey = true;
					} else if (!usedQuotes) {
						valWriter.write(c);
					}
				}
			}
			if (!readingKey) {
				valWriter.flush();
				String value = new String(val.toByteArray(), StandardCharsets.UTF_8);
				if (!usedQuotes) value = value.trim();
				map.put(key.toString().trim(), value);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return map;
	}

	public static MessageDigest sha1() {
		return digest("SHA1");
	}

	public static MessageDigest sha256() {
		return digest("SHA-256");
	}

	private static MessageDigest digest(String type) {
		try {
			return MessageDigest.getInstance(type);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private static final char[] hexCharset = "0123456789abcdef".toCharArray();

	public static String bytesToHex(byte[] data) {
		char[] characters = new char[data.length * 2];
		for (int i = 0; i < data.length; i++) {
			int pos = i * 2;
			characters[pos] = hexCharset[(data[i] >> 4) & 0xf];
			characters[pos + 1] = hexCharset[data[i] & 0xf];
		}
		return new String(characters);
	}

	public static byte[] hexToBytes(String data) {
		try {
			int length = data.length();
			if (length % 2 != 0) throw new IllegalArgumentException("Invalid hex");
			byte[] output = new byte[length / 2];
			for (int i = 0; i < output.length; i++) {
				int pos = i * 2;
				output[i] = (byte) Integer.parseInt(data.substring(pos, pos + 2), 16);
			}
			return output;
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Invalid hex");
		}
	}
}
