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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;

import static io.netty.handler.logging.LogLevel.INFO;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
final class Http2ClientHandler extends ChannelInitializer<SocketChannel> {
	private static final Http2FrameLogger LOGGER = new Http2FrameLogger(INFO, Http2ClientHandler.class);

	private final SslContext sslContext;
	private final String expectation;
	private HttpToHttp2ConnectionHandler connectionHandler;
	private Http2ResponseHandler responseHandler;
	private Http2SettingsHandler settingsHandler;

	Http2ClientHandler(SslContext sslContext, String expectation) {
		this.sslContext = sslContext;
		this.expectation = expectation;
	}

	@Override
	protected void initChannel(SocketChannel channel) throws Exception {
		final Http2Connection connection = new DefaultHttp2Connection(false);
		connectionHandler =
				new HttpToHttp2ConnectionHandlerBuilder()
				        .frameListener(new DelegatingDecompressorFrameListener(
				                connection,
				                new InboundHttp2ToHttpAdapterBuilder(connection).propagateSettings(true)
				                                                                .maxContentLength(Integer.MAX_VALUE)
				                                                                .build()))
				        .frameLogger(LOGGER)
				        .connection(connection)
				        .build();
		responseHandler = new Http2ResponseHandler(expectation);
		settingsHandler = new Http2SettingsHandler(channel.newPromise());
		if (sslContext != null) {
			configureSsl(channel);
		} else {
			configureClearText(channel);
		}
	}

	final Http2ResponseHandler responseHandler() {
		return responseHandler;
	}

	final Http2SettingsHandler settingsHandler() {
		return settingsHandler;
	}

	final boolean isSSL() {
		return sslContext != null;
	}

	final void configureEndOfPipeline(ChannelPipeline pipeline) {
		pipeline.addLast(settingsHandler, responseHandler);
	}

	final void configureSsl(SocketChannel channel) {
		ChannelPipeline pipeline = channel.pipeline();
		pipeline.addLast(sslContext.newHandler(channel.alloc()));
		pipeline.addLast(new ApplicationProtocolNegotiationHandler("") {
			@Override
			protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
				if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
					ChannelPipeline p = ctx.pipeline();
					p.addLast(connectionHandler);
					configureEndOfPipeline(p);
					return;
				}
				ctx.close();
				throw new IllegalStateException("unknown protocol: " + protocol);
			}
		});
	}

	final void configureClearText(SocketChannel channel) {
		HttpClientCodec sourceCodec = new HttpClientCodec();
		Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(connectionHandler);
		HttpClientUpgradeHandler upgradeHandler = new HttpClientUpgradeHandler(sourceCodec, upgradeCodec, 65536);

		channel.pipeline()
		       .addLast(sourceCodec,
		                upgradeHandler,
		                new UpgradeRequestHandler());
	}

	final class UpgradeRequestHandler extends ChannelInboundHandlerAdapter {
		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			DefaultFullHttpRequest upgradeRequest =
					new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
			ctx.writeAndFlush(upgradeRequest);

			ctx.fireChannelActive();

			ctx.pipeline().remove(this);

			configureEndOfPipeline(ctx.pipeline());
		}
	}
}
