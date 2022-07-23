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

package net.markwalder.junit.mailserver.pop3;

import java.io.IOException;
import net.markwalder.junit.mailserver.Client;
import net.markwalder.junit.mailserver.Mailbox;
import org.apache.commons.lang3.StringUtils;

public class RETR extends Command {

	@Override
	protected void execute(String command, Pop3Server server, Client client) throws IOException, ProtocolException {
		server.assertState(Pop3Server.State.TRANSACTION);

		String username = server.getUsername();

		// try to find message by number
		String msg = StringUtils.substringAfter(command, "RETR ");
		Mailbox.Message message = server.getMessage(username, msg);
		if (message == null || message.isDeleted()) {
			throw ProtocolException.MessageNotFound();
		}

		int size = message.getSize();
		String content = message.getContent();
		client.writeLine("+OK " + size + " octets");
		client.writeLine(content);
		client.writeLine(".");
	}

}