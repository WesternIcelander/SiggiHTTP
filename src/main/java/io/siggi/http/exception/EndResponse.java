package io.siggi.http.exception;

public class EndResponse extends Exception {
	private static boolean debug = false;
	private static final EndResponse INSTANCE = new EndResponse();

	public static void setDebug(boolean debug) {
		EndResponse.debug = debug;
	}

	public static EndResponse get() {
		if (debug) {
			return new EndResponse();
		} else {
			return INSTANCE;
		}
	}

	private EndResponse() {
	}

	@Override
	public Throwable fillInStackTrace() {
		if (debug) return super.fillInStackTrace();
		return this;
	}
}
