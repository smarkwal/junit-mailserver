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

package net.markwalder.junit.mailserver.smtp;

import java.io.IOException;
import net.markwalder.junit.mailserver.MailClient;
import net.markwalder.junit.mailserver.Mailbox;
import net.markwalder.junit.mailserver.MailboxStore;

public class DATA extends SmtpCommand {

	public DATA() {
	}

	public static DATA parse(String parameters) throws SmtpException {
		isNull(parameters);
		return new DATA();
	}

	@Override
	public String toString() {
		return "DATA";
	}

	@Override
	protected void execute(SmtpServer server, SmtpSession session, SmtpClient client) throws IOException, SmtpException {

		if (server.isAuthenticationRequired()) {
			throw SmtpException.AuthenticationRequired();
		}

		client.writeLine("354 Send message, end with <CRLF>.<CRLF>");
		String message = readMessage(client);
		// TODO: add Received header
		deliverMessage(message, server, session);

		// clear sender, list of recipients, and message data
		session.endTransaction(message);

		client.writeLine("250 2.6.0 Message accepted");
	}

	/**
	 * Read message until a line with only a dot is received.
	 */
	private String readMessage(MailClient client) throws IOException {

		StringBuilder message = new StringBuilder();
		while (true) {

			String line = client.readLine();
			if (line.equals(".")) {
				// end of message detected
				// TODO: remove trailing CRLF from message
				break;
			}

			// remove leading dot (if present)
			if (line.startsWith(".")) {
				line = line.substring(1);
			}

			// add line to message
			if (message.length() > 0) {
				message.append("\r\n");
			}
			message.append(line);
		}

		return message.toString();
	}

	/**
	 * Deliver message to mailboxes of known recipients.
	 */
	private void deliverMessage(String message, SmtpServer server, SmtpSession session) {
		MailboxStore store = server.getStore();
		for (String email : session.getRecipients()) {
			Mailbox mailbox = store.findMailbox(email);
			if (mailbox != null) {
				mailbox.addMessage(message);
			}
		}
	}

}
