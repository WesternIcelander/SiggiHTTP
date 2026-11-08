package io.siggi.http.util;

import io.siggi.http.iphelper.IP;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Recognises Cloudflare's IP ranges, so forwarded client IPs from Cloudflare
 * are trusted. This only works if a file called {@code cloudflare-ips.txt}
 * exists in the working directory when the class loads. The file is then kept
 * up to date from cloudflare.com once a week. Without the file, every address
 * is treated as not being Cloudflare's, and nothing is downloaded or written.
 *
 * @deprecated This will be removed in 2.0. Pass the ranges of any proxies you
 * use, Cloudflare's included, to {@link io.siggi.http.HTTPServer#trustIP(String)}
 * instead.
 */
@Deprecated(since = "1.11", forRemoval = true)
public class CloudFlare {

	private static final File RANGES_FILE = new File("cloudflare-ips.txt");
	private static final boolean enabled = RANGES_FILE.exists();

	public static boolean isCloudFlare(InetAddress add) {
		String address = add.getHostAddress();
		return isCloudFlare(address);
	}
	private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private static final Lock readLock = lock.readLock();
	private static final Lock writeLock = lock.writeLock();

	private static final List<IP> remoteCloudFlareRanges = new ArrayList<>();
	private static long lastRemoteUpdate = 0L;
	private static boolean updatingRemoteRanges = false;

	static {
		writeLock.lock();
		try {
			if (enabled) {
				try (BufferedReader reader = new BufferedReader(new FileReader(RANGES_FILE))) {
					String line = reader.readLine();
					lastRemoteUpdate = Long.parseLong(line);
					while ((line = reader.readLine()) != null) {
						remoteCloudFlareRanges.add(IP.getIP(line));
					}
				} catch (Exception e) {
				}
			}
		} finally {
			writeLock.unlock();
		}
	}

	public static boolean isCloudFlare(String address) {
		if (!enabled) {
			return false;
		}
		do {
			a:
			{
				readLock.lock();
				try {
					if (shouldUpdateRemoteRanges()) {
						break a;
					}
					IP check;
					try {
						check = IP.getIP(address);
					} catch (IllegalArgumentException e) {
						return false;
					}
					for (IP ip : remoteCloudFlareRanges) {
						if (ip.contains(check)) {
							return true;
						}
					}
					return false;
				} finally {
					readLock.unlock();
				}
			}
			writeLock.lock();
			try {
				if (shouldUpdateRemoteRanges()) {
					updateRemoteRanges();
				}
			} finally {
				writeLock.unlock();
			}
		} while (true);
	}

	private static boolean shouldUpdateRemoteRanges() {
		return !updatingRemoteRanges && (System.currentTimeMillis() - lastRemoteUpdate) > (86400000L * 7L);
	}

	private static void updateRemoteRanges() {
		updatingRemoteRanges = true;
		new Thread(CloudFlare::doUpdateRemoteRanges, "CloudFlare-Update").start();
	}

	private static void doUpdateRemoteRanges() {
		try {
			List<IP> newOnes = new LinkedList<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					new URL("https://www.cloudflare.com/ips-v4").openConnection().getInputStream()
			))) {
				String line;
				while ((line = reader.readLine()) != null) {
					try {
						newOnes.add(IP.getIP(line));
					} catch (Exception e) {
					}
				}
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					new URL("https://www.cloudflare.com/ips-v6").openConnection().getInputStream()
			))) {
				String line;
				while ((line = reader.readLine()) != null) {
					try {
						newOnes.add(IP.getIP(line));
					} catch (Exception e) {
					}
				}
			}
			writeLock.lock();
			try {
				remoteCloudFlareRanges.clear();
				remoteCloudFlareRanges.addAll(newOnes);
				try (FileWriter fw = new FileWriter(RANGES_FILE)) {
					fw.write(Long.toString(System.currentTimeMillis()) + "\n");
					for (IP ip : newOnes) {
						fw.write(ip.toString() + "\n");
					}
				} catch (Exception e) {
				}
			} finally {
				writeLock.unlock();
			}
		} catch (Exception e) {
		} finally {
			writeLock.lock();
			try {
				lastRemoteUpdate = System.currentTimeMillis();
				updatingRemoteRanges = false;
			} finally {
				writeLock.unlock();
			}
		}
	}
}
