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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

public class Mailbox {

	private final String username;
	private final String secret;
	private final String email;
	private final List<Message> messages = new CopyOnWriteArrayList<>();

	public Mailbox(String username, String secret, String email) {
		if (username == null) throw new IllegalArgumentException("username must not be null");
		if (secret == null) throw new IllegalArgumentException("secret must not be null");
		if (email == null) throw new IllegalArgumentException("email must not be null");

		this.username = username;
		this.secret = secret;
		this.email = email;
	}

	public String getUsername() {
		return username;
	}

	public String getSecret() {
		return secret;
	}

	public String getEmail() {
		return email;
	}

	public List<Message> getMessages() {
		return new ArrayList<>(messages);
	}

	public void addMessage(String message) {
		messages.add(new Message(message));
	}

	public void removeDeletedMessages() {
		messages.removeIf(Message::isDeleted);
	}

	public static class Message {

		private final String content;
		private boolean deleted;

		public Message(String content) {
			if (content == null) throw new IllegalArgumentException("content must not be null");
			this.content = content;
		}

		public String getContent() {
			return content;
		}

		public boolean isDeleted() {
			return deleted;
		}

		public void setDeleted(boolean deleted) {
			this.deleted = deleted;
		}

		public String getUID() {
			return DigestUtils.md5Hex(content);
		}

		public int getSize() {
			return content.length();
		}

		/**
		 * Get the first n lines of the message, or the complete message if
		 * n is greater than the total number of lines.
		 *
		 * @param n Number of lines.
		 * @return The first n lines of the message.
		 */
		public String getTop(int n) {
			String[] lines = StringUtils.split(content, "\r\n");
			if (n >= lines.length) {
				return content;
			}
			StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < n; i++) {
				if (buffer.length() > 0) {
					buffer.append("\r\n");
				}
				buffer.append(lines[i]);
			}
			return buffer.toString();
		}

	}

}