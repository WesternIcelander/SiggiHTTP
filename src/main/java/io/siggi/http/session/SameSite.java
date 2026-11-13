package io.siggi.http.session;

/**
 * The SameSite attribute of the session cookie, which controls whether
 * browsers send it on requests that come from other sites.
 */
public enum SameSite {
	/**
	 * Only send the cookie on requests from the same site.
	 */
	STRICT("Strict"),
	/**
	 * Also send the cookie when the user follows a link from another site,
	 * but not on other cross-site requests such as a POST from another
	 * site's form. The default.
	 */
	LAX("Lax"),
	/**
	 * Send the cookie on all requests. Browsers only accept this on a cookie
	 * that's also Secure, so it only works over HTTPS.
	 */
	NONE("None");

	private final String value;

	SameSite(String value) {
		this.value = value;
	}

	/**
	 * Returns the attribute value as it appears in Set-Cookie.
	 *
	 * @return Strict, Lax or None
	 */
	public String getValue() {
		return value;
	}
}
