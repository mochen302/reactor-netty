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

package reactor.ipc.netty.http.server;

import java.util.Objects;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2MultiplexCodecBuilder;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperationsHandler;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.annotation.Nullable;

import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_INITIAL_BUFFER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_MAX_CHUNK_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_MAX_HEADER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_MAX_INITIAL_LINE_LENGTH;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_VALIDATE_HEADERS;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.INITIAL_BUFFER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.MAX_CHUNK_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.MAX_HEADER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.MAX_INITIAL_LINE_LENGTH;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.VALIDATE_HEADERS;

/**
 * @author Stephane Maldini
 */
final class HttpServerBind extends HttpServer implements Function<ServerBootstrap, ServerBootstrap> {

	static final HttpServerBind INSTANCE = new HttpServerBind();

	final TcpServer tcpServer;

	HttpServerBind() {
		this(DEFAULT_TCP_SERVER);
	}

	HttpServerBind(TcpServer tcpServer) {
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

		Integer minCompressionSize = getAttributeValue(b, PRODUCE_GZIP, null);

		Integer line = getAttributeValue(b, MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_INITIAL_LINE_LENGTH);

		Integer header = getAttributeValue(b, MAX_HEADER_SIZE, DEFAULT_MAX_HEADER_SIZE);

		Integer chunk = getAttributeValue(b, MAX_CHUNK_SIZE, DEFAULT_MAX_CHUNK_SIZE);

		Boolean validate = getAttributeValue(b, VALIDATE_HEADERS, DEFAULT_VALIDATE_HEADERS);

		Integer buffer = getAttributeValue(b, INITIAL_BUFFER_SIZE, DEFAULT_INITIAL_BUFFER_SIZE);

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				(listener, channel) -> {
					ChannelPipeline p = channel.pipeline();

					if (p.get(NettyPipeline.SslHandler) != null) {
						p.addLast(new Http2Initializer(line, header, chunk, validate, buffer, minCompressionSize, listener));
					}
					else {
						HttpServerCodec httpServerCodec = new HttpServerCodec(line, header, chunk, validate, buffer);
						HttpServerHandler httpServerHandler = new HttpServerHandler(listener);
						p.addLast(NettyPipeline.HttpCodec, httpServerCodec)
						 .addLast(new HttpServerUpgradeHandler(httpServerCodec,
						         new UpgradeCodecFactoryImpl(line, header, chunk, validate, buffer, minCompressionSize, listener)));

						if (minCompressionSize != null && minCompressionSize >= 0) {
							p.addLast(NettyPipeline.CompressionHandler,
							          new CompressionHandler(minCompressionSize));
						}

						p.addLast(NettyPipeline.HttpServerHandler, httpServerHandler);
					}
				});
		return b;
	}

	static final AttributeKey<Integer> PRODUCE_GZIP =
			AttributeKey.newInstance("produceGzip");

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

	final class UpgradeCodecFactoryImpl implements UpgradeCodecFactory {
		final Integer line;
		final Integer header;
		final Integer chunk;
		final Boolean validate;
		final Integer buffer;
		final Integer minCompressionSize;
		final ConnectionEvents listener;

		UpgradeCodecFactoryImpl(Integer line, Integer header, Integer chunk, Boolean validate,
				Integer buffer, Integer minCompressionSize, ConnectionEvents listener) {
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.listener = listener;
		}

		@Override
		public UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				return new Http2ServerUpgradeCodec(
						Http2MultiplexCodecBuilder.forServer(new ChannelInitializer() {
							@Override
							protected void initChannel(Channel ch) throws Exception {
								// TODO do we need to add HttpCodec and CompressionHandler?
								preparePipeline(ch.pipeline(), line, header, chunk, validate, buffer, minCompressionSize, listener);
								ch.pipeline().addLast(NettyPipeline.ReactiveBridge, new ChannelOperationsHandler(listener));

								if (log.isDebugEnabled()) {
									log.debug("{} Initialized pipeline {}", ch, ch.pipeline());
								}
							}
						}).build());
			} else {
				return null;
			}
		}
	}

	final class Http2Initializer extends ApplicationProtocolNegotiationHandler {
		final Integer line;
		final Integer header;
		final Integer chunk;
		final Boolean validate;
		final Integer buffer;
		final Integer minCompressionSize;
		final ConnectionEvents listener;

		Http2Initializer(Integer line, Integer header, Integer chunk, Boolean validate,
						 Integer buffer, Integer minCompressionSize, ConnectionEvents listener) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.listener = listener;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				p.addLast(Http2MultiplexCodecBuilder.forServer(new ChannelInitializer() {
					@Override
					protected void initChannel(Channel ch) throws Exception {
						// TODO do we need to add HttpCodec and CompressionHandler?
						preparePipeline(ch.pipeline(), line, header, chunk, validate, buffer, minCompressionSize, listener);
						ch.pipeline().addLast(NettyPipeline.ReactiveBridge, new ChannelOperationsHandler(listener));

						if (log.isDebugEnabled()) {
							log.debug("{} Initialized pipeline {}", ch, ch.pipeline());
						}
					}
				}).build());
				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				preparePipeline(p, line, header, chunk, validate, buffer, minCompressionSize, listener);
				return;
			}

			throw new IllegalStateException("unknown protocol: " + protocol);
		}
	}

	private void preparePipeline(ChannelPipeline p, Integer line, Integer header, Integer chunk, Boolean validate,
			Integer buffer, Integer minCompressionSize, ConnectionEvents listener) {
		HttpServerCodec httpServerCodec = new HttpServerCodec(line, header, chunk, validate, buffer);
		HttpServerHandler httpServerHandler = new HttpServerHandler(listener);
		p.addLast(NettyPipeline.HttpCodec, httpServerCodec);

		if (minCompressionSize != null && minCompressionSize >= 0) {
			p.addLast(NettyPipeline.CompressionHandler,
					new CompressionHandler(minCompressionSize));
		}

		p.addLast(NettyPipeline.HttpServerHandler, httpServerHandler);
	}
}
