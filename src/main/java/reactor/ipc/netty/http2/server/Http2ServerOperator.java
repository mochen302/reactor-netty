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
package reactor.ipc.netty.http2.server;

import java.util.Objects;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.tcp.TcpServer;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
abstract class Http2ServerOperator extends Http2Server {

	final Http2Server source;

	Http2ServerOperator(Http2Server source) {
		this.source = Objects.requireNonNull(source, "source");
	}

	@Override
	protected TcpServer tcpConfiguration() {
		return source.tcpConfiguration();
	}

	@Override
	protected Mono<? extends DisposableServer> bind(TcpServer b) {
		return source.bind(b);
	}
}
