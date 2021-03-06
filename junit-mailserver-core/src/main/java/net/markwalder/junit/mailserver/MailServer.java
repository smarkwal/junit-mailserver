/*
 * Copyright 2022 Stephan Markwalder
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.markwalder.junit.mailserver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.Security;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import net.markwalder.junit.mailserver.auth.Authenticator;
import net.markwalder.junit.mailserver.auth.CramMd5Authenticator;
import net.markwalder.junit.mailserver.auth.DigestMd5Authenticator;
import net.markwalder.junit.mailserver.auth.LoginAuthenticator;
import net.markwalder.junit.mailserver.auth.PlainAuthenticator;
import net.markwalder.junit.mailserver.auth.XOauth2Authenticator;
import net.markwalder.junit.mailserver.utils.Assert;

/**
 * Skeleton for a simulated/virtual SMTP, IMAP, or POP3 server.
 */
@SuppressWarnings("unused")
public abstract class MailServer<T extends MailCommand, S extends MailSession, C extends MailClient, E extends MailException> implements AutoCloseable {

	static {

		// enable SSLv3, TLSv1 and TLSv1.1
		Security.setProperty("jdk.tls.disabledAlgorithms", "");

		// enable Jakarta Mail API debug output
		// System.setProperty("mail.socket.debug", "true");
	}

	protected final Map<String, MailCommand.Parser<T, E>> commands = new ConcurrentHashMap<>();
	private final Map<String, Boolean> enabledCommands = new ConcurrentHashMap<>();

	private final String protocol;
	protected final MailboxStore store;

	/**
	 * Clock used by server to determine current date and time.
	 */
	protected Clock clock = Clock.systemUTC();

	private int port = 0; // 0 = select a free port
	private boolean useSSL = false;
	private String sslProtocol = "TLSv1.2";
	private boolean authenticationRequired = false;
	//TODO: private boolean encryptionRequired = false;

	/**
	 * Registered authenticators.
	 */
	private final Map<String, Authenticator> authenticators = new ConcurrentHashMap<>();

	/**
	 * List of supported authentication types.
	 */
	private final LinkedList<String> authTypes = new LinkedList<>();

	private ServerSocket serverSocket;
	private Thread thread;
	protected C client;
	protected S session;

	/**
	 * History of sessions handled by this server.
	 */
	private final List<S> sessions = new ArrayList<>();

	/**
	 * Flag used to tell the worker thread to stop processing new connections.
	 */
	private final AtomicBoolean stop = new AtomicBoolean(false);

	/**
	 * Communication log with all incoming and outgoing messages.
	 * TODO: move log into session
	 */
	private final StringBuilder log = new StringBuilder();

	protected MailServer(String protocol, MailboxStore store) {
		Assert.isNotEmpty(protocol, "protocol");
		Assert.isNotNull(store, "store");
		this.protocol = protocol;
		this.store = store;

		addAuthenticator(AuthType.LOGIN, new LoginAuthenticator());
		addAuthenticator(AuthType.PLAIN, new PlainAuthenticator());
		addAuthenticator(AuthType.CRAM_MD5, new CramMd5Authenticator());
		addAuthenticator(AuthType.DIGEST_MD5, new DigestMd5Authenticator());
		addAuthenticator(AuthType.XOAUTH2, new XOauth2Authenticator());
	}

	public void addCommand(String command, MailCommand.Parser<T, E> factory) {
		Assert.isNotEmpty(command, "command");
		Assert.isNotNull(factory, "factory");
		command = command.toUpperCase();
		commands.put(command, factory);
	}

	public void removeCommand(String command) {
		Assert.isNotEmpty(command, "command");
		command = command.toUpperCase();
		commands.remove(command);
	}

	public boolean isCommandEnabled(String command) {
		Assert.isNotEmpty(command, "command");
		command = command.toUpperCase();
		return commands.containsKey(command) && enabledCommands.getOrDefault(command, true);
	}

	public void setCommandEnabled(String command, boolean enabled) {
		Assert.isNotEmpty(command, "command");
		command = command.toUpperCase();
		if (enabled) {
			enabledCommands.put(command, true);
		} else {
			enabledCommands.put(command, false);
		}
	}

	public MailboxStore getStore() {
		return store;
	}

	public boolean isUseSSL() {
		return useSSL;
	}

	public void setUseSSL(boolean useSSL) {
		this.useSSL = useSSL;
	}

	public String getSSLProtocol() {
		return sslProtocol;
	}

	public void setSSLProtocol(String sslProtocol) {
		Assert.isNotEmpty(sslProtocol, "sslProtocol");
		this.sslProtocol = sslProtocol;
	}

	// TODO: set cipher suites?

	public boolean isAuthenticationRequired() {
		return authenticationRequired && session.getUsername() == null;
	}

	public void setAuthenticationRequired(boolean authenticationRequired) {
		this.authenticationRequired = authenticationRequired;
	}

	public List<String> getAuthTypes() {
		return new ArrayList<>(authTypes);
	}

	public void setAuthTypes(String... authTypes) {
		Assert.isNotNull(authTypes, "authTypes");
		this.authTypes.clear();
		for (String authType : authTypes) {
			addAuthType(authType);
		}
	}

	public void addAuthType(String authType) {
		Assert.isNotEmpty(authType, "authType");
		if (!authenticators.containsKey(authType)) {
			throw new IllegalArgumentException("Authenticator not found: " + authType);
		}
		authTypes.remove(authType); // remove (if present)
		authTypes.add(authType); // add to end of list
	}

	public void removeAuthType(String authType) {
		Assert.isNotEmpty(authType, "authType");
		authTypes.remove(authType);
	}

	public boolean isAuthTypeSupported(String authType) {
		Assert.isNotEmpty(authType, "authType");
		return authTypes.contains(authType);
	}

	public Authenticator getAuthenticator(String authType) {
		Assert.isNotEmpty(authType, "authType");
		return authenticators.get(authType);
	}

	protected void addAuthenticator(String authType, Authenticator authenticator) {
		Assert.isNotEmpty(authType, "authType");
		Assert.isNotNull(authenticator, "authenticator");
		this.authenticators.put(authType, authenticator);
	}

	public Clock getClock() {
		return clock;
	}

	public void setClock(Clock clock) {
		Assert.isNotNull(clock, "clock");
		this.clock = clock;
	}

	public void start() throws IOException {
		System.out.println("Starting " + protocol + " server ...");

		// open a server socket on a free port
		ServerSocketFactory factory;
		if (useSSL) {
			factory = SSLUtils.createFactoryWithSelfSignedCertificate(sslProtocol);
		} else {
			factory = ServerSocketFactory.getDefault();
		}
		InetAddress localhost = InetAddress.getLoopbackAddress();
		serverSocket = factory.createServerSocket(port, 1, localhost);

		if (serverSocket instanceof SSLServerSocket) {
			SSLServerSocket sslServerSocket = (SSLServerSocket) serverSocket;

			// disable all other SSL/TLS protocols
			sslServerSocket.setEnabledProtocols(new String[] { sslProtocol });

			// TODO: enable/disable cipher suites?
			// sslServerSocket.setEnabledCipherSuites(...);
		}

		// start a new thread to handle client connections
		thread = new Thread(this::run);
		thread.setDaemon(true);
		thread.setName(protocol + "-server-localhost-" + getPort());
		thread.start();

		System.out.println(protocol + " server started");
	}

	/**
	 * Get the port the server is listening on.
	 * If this method is called after the server has been started, the method
	 * will return the actual port the server is listening on.
	 * If this method is called before the server has been started, the method
	 * will return the port that has been configured by calling
	 * {@link #setPort(int)}.
	 *
	 * @return Port the server is listening on.
	 */
	public int getPort() {

		// if server is running, return the actual port
		if (serverSocket != null && serverSocket.isBound()) {
			return serverSocket.getLocalPort();
		}

		// return the configured port
		return port;
	}

	/**
	 * Set the port to use for the server.
	 * If set to {@code 0}, the server will find and use a free port.
	 * Note that this method has no immediate effect if the server has already
	 * been started. It will only take effect when the server is restarted.
	 *
	 * @param port Port to use for the server.
	 */
	public void setPort(int port) {
		Assert.isInRange(port, 0, 65535, "port");
		if (serverSocket != null) {
			// TODO: close and reopen server socket?
		}
		this.port = port;
	}

	public void stop() throws IOException {
		System.out.println("Stopping " + protocol + " server ...");

		// signal thread to stop
		stop.set(true);

		// close server socket
		if (serverSocket != null) {
			serverSocket.close();
			serverSocket = null;
		}

		// wait for thread to have stopped (max 5 seconds)
		if (thread != null) {
			thread.interrupt();
			try {
				thread.join(5 * 1000);
			} catch (InterruptedException e) {
				// ignore
			}
			thread = null;
		}

		System.out.println(protocol + " server stopped");
	}

	@Override
	public void close() throws IOException {
		stop();
	}

	protected void run() {

		stop.set(false);

		// while the server has not been stopped ...
		while (!stop.get()) {

			// wait for incoming connection
			System.out.println("Waiting for " + protocol + " connection on localhost:" + getPort() + (useSSL ? " (" + sslProtocol + ")" : "") + " ...");

			// if (serverSocket instanceof SSLServerSocket) {
			// 	SSLServerSocket sslSocket = (SSLServerSocket) serverSocket;
			// 	System.out.println("Protocols: " + Arrays.toString(sslSocket.getEnabledProtocols()));
			// 	System.out.println("Cipher suites: " + Arrays.toString(sslSocket.getEnabledCipherSuites()));
			// }

			// check if the server is still listening
			if (serverSocket == null || !serverSocket.isBound() || serverSocket.isClosed()) {
				break;
			}

			try (Socket socket = serverSocket.accept()) {

				System.out.println(protocol + " connection from " + getClientInfo(socket));

				// clear log (if there has been a previous connection)
				log.setLength(0);

				client = createClient(socket, log);
				session = createSession();

				// collect information about server and client
				session.setSocketData(socket);

				// add session to history
				synchronized (sessions) {
					sessions.add(session);
				}

				// greet client
				handleNewClient();

				// read and handle client commands
				while (true) {

					// read next command from client
					String command = client.readLine();
					if (command == null) {

						// client has closed the connection
						System.out.println(protocol + " client closed connection");

						// stop waiting for new commands
						break;

					} else if (command.isEmpty()) {
						// TODO: how should an empty line be handled?
						//  (sent after failed authentication)
						continue;
					}

					// TODO: implement command listener

					// execute command
					try {
						handleCommand(command);
					} catch (MailException e) {
						client.writeError(e.getMessage());
					}

					// check if the session has been closed (with a QUIT command)
					boolean quit = session.isClosed();
					if (quit) {
						// stop waiting for new commands and close the connection
						// (if not already closed by the client)
						break;
					}
				}

			} catch (IOException e) {

				if (!stop.get()) { // ignore exception if server has been stopped
					System.out.flush();
					System.err.println("Unexpected " + protocol + " I/O error:");
					e.printStackTrace();
					System.err.flush();
				}

			} finally {

				// discard session
				if (session != null) {
					// make sure that session is closed
					// (test code may wait for this)
					if (!session.isClosed()) {
						session.close();
					}
					session = null;
				}

				// discard client
				client = null;

			}

		}

	}

	protected abstract C createClient(Socket socket, StringBuilder log) throws IOException;

	protected abstract S createSession();

	protected abstract void handleNewClient() throws IOException;

	protected abstract void handleCommand(String command) throws E, IOException;

	public String getLog() {
		return log.toString();
	}

	public S getActiveSession() {
		return session;
	}

	public List<S> getSessions() {
		synchronized (sessions) {
			return new ArrayList<>(sessions);
		}
	}

	private String getClientInfo(Socket socket) {
		String clientInfo = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
		if (socket instanceof SSLSocket) {
			SSLSocket sslSocket = (SSLSocket) socket;
			SSLSession sslSession = sslSocket.getSession();
			String sslProtocol = sslSession.getProtocol();
			String cipherSuite = sslSession.getCipherSuite();
			clientInfo += " (" + sslProtocol + ", " + cipherSuite + ")";
		}
		return clientInfo;
	}

}
