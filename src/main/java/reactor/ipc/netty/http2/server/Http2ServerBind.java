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
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.AttributeKey;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.annotation.Nullable;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
final class Http2ServerBind extends Http2Server implements Function<ServerBootstrap, ServerBootstrap> {

	static final Http2ServerBind INSTANCE = new Http2ServerBind();

	final TcpServer tcpServer;

	Http2ServerBind() {
		this(DEFAULT_TCP_SERVER);
	}

	Http2ServerBind(TcpServer tcpServer) {
		this.tcpServer = Objects.requireNonNull(tcpServer, "tcpServer");
	}

	@Override
	protected TcpServer tcpConfiguration() {
		return tcpServer;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<? extends DisposableServer> bind(TcpServer delegate) {
		return delegate.bootstrap(this)
		               .bind();
	}

	@Override
	public ServerBootstrap apply(ServerBootstrap b) {
		if (b.config()
		     .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(tcpConfiguration().sslContext() instanceof JdkSslContext);

			EventLoopGroup selector = loops.onServerSelect(useNative);
			EventLoopGroup elg = loops.onServer(useNative);

			b.group(selector, elg)
			 .channel(loops.onServerChannel(elg));
		}

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				(listener, channel) -> {
					ChannelPipeline p = channel.pipeline();
					if (p.get(NettyPipeline.SslHandler) != null) {
						p.addLast(new Http2Initializer(listener));
					}
					else {
						p.addLast(new CleartextHttp2ServerUpgradeHandler(listener));
					}
				});
		return b;
	}

	@SuppressWarnings("unchecked")
	@Nullable
	static  <T> T getAttributeValue(ServerBootstrap bootstrap, AttributeKey<T>
			attributeKey, @Nullable T defaultValue) {
		T result = bootstrap.config().attrs().get(attributeKey) != null
				? (T) bootstrap.config().attrs().get(attributeKey)
				: defaultValue;
		bootstrap.attr(attributeKey, null);
		return result;
	}

	static final class Http2Initializer extends ApplicationProtocolNegotiationHandler {
		final ConnectionEvents listener;

		Http2Initializer(ConnectionEvents listener) {
			super(ApplicationProtocolNames.HTTP_2);
			this.listener = listener;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				ctx.pipeline()
				   .addBefore(NettyPipeline.ReactiveBridge,
				              NettyPipeline.Http2ServerHandler,
				              new Http2ServerHandlerBuilder(listener).build());
				return;
			}

			throw new IllegalStateException("unknown protocol: " + protocol);
		}
	}
}
