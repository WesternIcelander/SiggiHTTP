package io.siggi.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * A real loopback connection that reports a different peer address, so a
 * test can act as a client from any address.
 */
final class PeerAddressSocket extends Socket {
	private final Socket socket;
	private final InetAddress peer;

	PeerAddressSocket(Socket socket, InetAddress peer) {
		this.socket = socket;
		this.peer = peer;
	}

	@Override
	public InetAddress getInetAddress() {
		return peer;
	}

	@Override
	public SocketAddress getRemoteSocketAddress() {
		return new InetSocketAddress(peer, socket.getPort());
	}

	@Override
	public SocketAddress getLocalSocketAddress() {
		return socket.getLocalSocketAddress();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return socket.getInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return socket.getOutputStream();
	}

	@Override
	public synchronized void setSoTimeout(int timeout) throws SocketException {
		socket.setSoTimeout(timeout);
	}

	@Override
	public synchronized int getSoTimeout() throws SocketException {
		return socket.getSoTimeout();
	}

	@Override
	public void setTcpNoDelay(boolean on) throws SocketException {
		socket.setTcpNoDelay(on);
	}

	@Override
	public void setSoLinger(boolean on, int linger) throws SocketException {
		socket.setSoLinger(on, linger);
	}

	@Override
	public void shutdownOutput() throws IOException {
		socket.shutdownOutput();
	}

	@Override
	public synchronized void close() throws IOException {
		socket.close();
	}
}
