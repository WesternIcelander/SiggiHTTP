package io.siggi.http.cookie;

/**
 * Builds the value of a Set-Cookie header.
 * <pre>{@code
 * request.response.addHeader("Set-Cookie", CookieBakery
 *         .name("userLikesCookies")
 *         .value("yes")
 *         .maxAge(3600)
 *         .secure(request.isSecure())
 *         .httpOnly(true)
 *         .bake());
 *
 * request.response.addHeader("Set-Cookie", CookieBakery.name("userLikesCookies").delete());
 * }</pre>
 * The path is {@code /} unless set otherwise. To delete a cookie, set the
 * same path and domain it was created with.
 */
public final class CookieBakery {

	// RFC 6265: a token, which leaves out separators and control characters
	private static final String NAME_PATTERN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

	private final String name;
	private String value = "";
	private String path = "/";
	private String domain = null;
	private long maxAge = -1L;
	private boolean secure = false;
	private boolean httpOnly = false;
	private SameSite sameSite = null;

	private CookieBakery(String name) {
		this.name = name;
	}

	/**
	 * Starts a cookie with this name.
	 *
	 * @param name the cookie name
	 * @return a CookieBakery for the cookie
	 * @throws IllegalArgumentException if the name isn't a valid cookie name
	 */
	public static CookieBakery name(String name) {
		if (name == null || !name.matches(NAME_PATTERN)) {
			throw new IllegalArgumentException("Not a valid cookie name: " + name);
		}
		return new CookieBakery(name);
	}

	/**
	 * Sets the value. The default is empty.
	 *
	 * @param value the value
	 * @return this CookieBakery
	 * @throws IllegalArgumentException if the value has characters a cookie
	 * value can't contain: control characters, spaces, double quotes, commas,
	 * semicolons, backslashes or anything outside ASCII
	 */
	public CookieBakery value(String value) {
		if (value == null) {
			value = "";
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c <= 0x20 || c >= 0x7f || c == '"' || c == ',' || c == ';' || c == '\\') {
				throw new IllegalArgumentException("Not a valid cookie value: " + value);
			}
		}
		this.value = value;
		return this;
	}

	/**
	 * Sets the path the cookie is sent for. The default is {@code /}, the
	 * whole site. Null leaves the attribute out, so the browser uses the
	 * directory of the request that set it.
	 *
	 * @param path the path, or null
	 * @return this CookieBakery
	 */
	public CookieBakery path(String path) {
		this.path = checkAttribute("path", path);
		return this;
	}

	/**
	 * Sets the domain the cookie is sent to, including its subdomains. The
	 * default, null, leaves the attribute out, so the cookie is only sent to
	 * the host that set it.
	 *
	 * @param domain the domain, or null
	 * @return this CookieBakery
	 */
	public CookieBakery domain(String domain) {
		this.domain = checkAttribute("domain", domain);
		return this;
	}

	/**
	 * Sets how long the browser keeps the cookie. By default there's no
	 * Max-Age, so the browser forgets the cookie when it's closed.
	 *
	 * @param seconds the time in seconds, or a negative number for none
	 * @return this CookieBakery
	 */
	public CookieBakery maxAge(long seconds) {
		this.maxAge = seconds < 0L ? -1L : seconds;
		return this;
	}

	/**
	 * Sets whether the browser only sends the cookie over HTTPS. The default
	 * is false.
	 *
	 * @param secure true for HTTPS only
	 * @return this CookieBakery
	 */
	public CookieBakery secure(boolean secure) {
		this.secure = secure;
		return this;
	}

	/**
	 * Sets whether the cookie is hidden from JavaScript. The default is false.
	 *
	 * @param httpOnly true to hide it from JavaScript
	 * @return this CookieBakery
	 */
	public CookieBakery httpOnly(boolean httpOnly) {
		this.httpOnly = httpOnly;
		return this;
	}

	/**
	 * Sets the SameSite attribute. The default, null, leaves it out, so each
	 * browser applies its own default.
	 *
	 * @param sameSite the SameSite value, or null
	 * @return this CookieBakery
	 */
	public CookieBakery sameSite(SameSite sameSite) {
		this.sameSite = sameSite;
		return this;
	}

	/**
	 * Returns the Set-Cookie header value for this cookie.
	 *
	 * @return the header value
	 */
	public String bake() {
		return build(value, maxAge);
	}

	/**
	 * Returns a Set-Cookie header value that deletes this cookie: an empty
	 * value with {@code Max-Age=0}, and the other attributes as set.
	 *
	 * @return the header value
	 */
	public String delete() {
		return build("", 0L);
	}

	private String build(String value, long maxAge) {
		StringBuilder cookie = new StringBuilder();
		cookie.append(name).append('=').append(value);
		if (path != null) {
			cookie.append("; Path=").append(path);
		}
		if (domain != null) {
			cookie.append("; Domain=").append(domain);
		}
		if (httpOnly) {
			cookie.append("; HttpOnly");
		}
		if (secure) {
			cookie.append("; Secure");
		}
		if (sameSite != null) {
			cookie.append("; SameSite=").append(sameSite.getValue());
		}
		if (maxAge >= 0L) {
			cookie.append("; Max-Age=").append(maxAge);
		}
		return cookie.toString();
	}

	private static String checkAttribute(String what, String value) {
		if (value == null) {
			return null;
		}
		if (value.isEmpty()) {
			throw new IllegalArgumentException("The cookie " + what + " can't be empty");
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x20 || c >= 0x7f || c == ';') {
				throw new IllegalArgumentException("Not a valid cookie " + what + ": " + value);
			}
		}
		return value;
	}
}
