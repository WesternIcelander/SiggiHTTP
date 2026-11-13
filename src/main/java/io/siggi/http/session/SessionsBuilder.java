package io.siggi.http.session;

import java.io.File;

/**
 * Configures and creates a {@link Sessions}.
 * <pre>{@code
 * Sessions sessions = new SessionsBuilder()
 *         .setExpireTime(24 * 3600000L)
 *         .setDirectory(new File("sessions"))
 *         .build();
 * }</pre>
 */
public final class SessionsBuilder {

	long expireTime = 3600000L;
	File directory = null;
	String cookieName = "SessID";
	SameSite sameSite = SameSite.LAX;
	// set only by the deprecated Sessions.create() methods
	boolean legacy = false;

	/**
	 * Sets how long a session lasts without being used. Each use starts the
	 * time again. The default is one hour.
	 *
	 * @param expireTime the time in milliseconds
	 * @return this builder
	 */
	public SessionsBuilder setExpireTime(long expireTime) {
		if (expireTime <= 0L) {
			throw new IllegalArgumentException("Expire time must be positive: " + expireTime);
		}
		this.expireTime = expireTime;
		return this;
	}

	/**
	 * Keeps sessions in files in this directory, so they survive a restart.
	 * The directory is created if it doesn't exist. The default, null, keeps
	 * them in memory only.
	 *
	 * @param directory the directory, or null to keep sessions in memory
	 * @return this builder
	 */
	public SessionsBuilder setDirectory(File directory) {
		this.directory = directory;
		return this;
	}

	/**
	 * Sets the name of the session cookie. The default is {@code SessID}.
	 *
	 * @param cookieName the cookie name
	 * @return this builder
	 */
	public SessionsBuilder setCookieName(String cookieName) {
		if (cookieName == null || !cookieName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
			throw new IllegalArgumentException("Not a valid cookie name: " + cookieName);
		}
		this.cookieName = cookieName;
		return this;
	}

	/**
	 * Sets the SameSite attribute of the session cookie. The default is
	 * {@link SameSite#LAX}. Null leaves the attribute out, so each browser
	 * applies its own default.
	 *
	 * @param sameSite the SameSite value, or null for none
	 * @return this builder
	 */
	public SessionsBuilder setSameSite(SameSite sameSite) {
		this.sameSite = sameSite;
		return this;
	}

	/**
	 * Creates the Sessions.
	 *
	 * @return a new Sessions with these settings
	 */
	public Sessions build() {
		if (directory == null) {
			return new SessionsMemory(this);
		} else {
			return new SessionsDisk(this);
		}
	}
}
