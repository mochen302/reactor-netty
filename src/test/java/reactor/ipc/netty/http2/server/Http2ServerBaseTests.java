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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
class Http2ServerBaseTests {

	static final Logger LOGGER = Loggers.getLogger(Http2ServerBaseTests.class);

	static void get(InetSocketAddress remoteAddress, Http2ClientHandler handler,
			String path, int streamId) {
		EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
		try {
			Channel channel = prepareChannel(eventLoopGroup, remoteAddress, handler);

			Http2SettingsHandler http2SettingsHandler = handler.settingsHandler();
			http2SettingsHandler.awaitHttp2Settings(5, TimeUnit.SECONDS);

			Http2ResponseHandler responseHandler = handler.responseHandler();
			HttpScheme scheme = handler.isSSL() ? HttpScheme.HTTPS : HttpScheme.HTTP;
			AsciiString hostName = new AsciiString(remoteAddress.getHostString() + ':' + remoteAddress.getPort());
			LOGGER.info("{} Sending request...", channel);
			FullHttpRequest request = prepareRequest(GET, path, hostName, scheme, null);
			responseHandler.put(streamId, channel.write(request), channel.newPromise());
			channel.flush();
			responseHandler.awaitHttp2Responses(5, TimeUnit.SECONDS);
			LOGGER.info("{} Finished HTTP/2 request", channel);

			channel.close().syncUninterruptibly();
		} finally {
			eventLoopGroup.shutdownGracefully();
		}
	}

	static void post(InetSocketAddress remoteAddress, Http2ClientHandler handler,
			String path, int streamId, String data) {
		EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
		try {
			Channel channel = prepareChannel(eventLoopGroup, remoteAddress, handler);

			Http2SettingsHandler http2SettingsHandler = handler.settingsHandler();
			http2SettingsHandler.awaitHttp2Settings(5, TimeUnit.SECONDS);

			Http2ResponseHandler responseHandler = handler.responseHandler();
			HttpScheme scheme = handler.isSSL() ? HttpScheme.HTTPS : HttpScheme.HTTP;
			AsciiString hostName = new AsciiString(remoteAddress.getHostString() + ':' + remoteAddress.getPort());
			LOGGER.info("{} Sending request...", channel);
			FullHttpRequest request = prepareRequest(POST, path, hostName, scheme, data);
			responseHandler.put(streamId, channel.write(request), channel.newPromise());
			channel.flush();
			responseHandler.awaitHttp2Responses(5, TimeUnit.SECONDS);
			LOGGER.info("{} Finished HTTP/2 request", channel);

			channel.close().syncUninterruptibly();
		} finally {
			eventLoopGroup.shutdownGracefully();
		}
	}

	private static Channel prepareChannel(EventLoopGroup eventLoopGroup, InetSocketAddress remoteAddress, Http2ClientHandler handler) {
		Bootstrap b = new Bootstrap();
		b.group(eventLoopGroup);
		b.channel(NioSocketChannel.class);
		b.option(ChannelOption.SO_KEEPALIVE, true);
		b.remoteAddress(remoteAddress);
		b.handler(handler);

		Channel channel = b.connect().syncUninterruptibly().channel();
		LOGGER.info("{} Connected", channel);

		return channel;
	}

	private static FullHttpRequest prepareRequest(HttpMethod method, String path, AsciiString hostName, HttpScheme scheme, String data) {
		FullHttpRequest request;
		if (GET.equals(method)) {
			request = new DefaultFullHttpRequest(HTTP_1_1, method, path);
		}
		else {
			request = new DefaultFullHttpRequest(HTTP_1_1, method, path,
					Unpooled.wrappedBuffer(data.getBytes(CharsetUtil.UTF_8)));
		}
		request.headers().add(HttpHeaderNames.HOST, hostName);
		request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
		request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
		request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
		return request;
	}
}
