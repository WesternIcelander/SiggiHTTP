package io.siggi.http;

import io.siggi.http.session.Sessions;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

public final class HTTPServerBuilder {

	private Sessions sessions;
	private String sessionCookieName;
	private File tmpDir;
	private Executor executor;

	public HTTPServerBuilder() {
		tmpDir = new File(System.getProperty("java.io.tmpdir"));
	}

	/**
	 * Sets the Sessions used by the deprecated session methods of
	 * {@link HTTPRequest}.
	 *
	 * @param sessions the Sessions
	 * @return this builder
	 * @deprecated The server will no longer hold the Sessions. Sessions are now used directly: build one with {@link io.siggi.http.session.SessionsBuilder}, which also sets the cookie name, keep it in your application, and call its methods with the request.
	 */
	@Deprecated(since = "1.11", forRemoval = true)
	public HTTPServerBuilder setSessions(Sessions sessions) {
		this.sessions = sessions;
		return this;
	}

	/**
	 * Sets the name of the session cookie used by the deprecated session
	 * methods of {@link HTTPRequest}.
	 *
	 * @param sessionCookieName the cookie name
	 * @return this builder
	 * @deprecated The cookie name is now set on the Sessions, with
	 * {@link io.siggi.http.session.SessionsBuilder#setCookieName(String)}.
	 */
	@Deprecated(since = "1.11", forRemoval = true)
	public HTTPServerBuilder setSessionCookieName(String sessionCookieName) {
		this.sessionCookieName = sessionCookieName;
		return this;
	}

	public HTTPServerBuilder setTmpDir(File tmpDir) {
		this.tmpDir = tmpDir;
		return this;
	}

	public HTTPServerBuilder setExecutor(Executor executor) {
		this.executor = executor;
		return this;
	}

	public HTTPServer build() {
		return new HTTPServer(sessions, sessionCookieName, tmpDir, executor);
	}
}
