/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.channel;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
public class Http2Message {
	final int streamId;
	final Object msg;
	final boolean isEndOfStream;

	public Http2Message(int streamId, Object msg, boolean isEndOfStream) {
		this.streamId = streamId;
		this.msg = msg;
		this.isEndOfStream = isEndOfStream;
	}

	public int streamId() {
		return streamId;
	}

	public Object message() {
		return msg;
	}

	public boolean isEndOfStream() {
		return isEndOfStream;
	}
}
