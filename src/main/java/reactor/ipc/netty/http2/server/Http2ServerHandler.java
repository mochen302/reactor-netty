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
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import reactor.ipc.netty.channel.ChannelOperations;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
public final class Http2ServerHandler extends Http2ConnectionHandler {

	Http2ServerHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
		super(decoder, encoder, initialSettings);
	}
	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof UpgradeEvent) {
			UpgradeEvent upgradeEvent = (UpgradeEvent) evt;
			Http2ServerOperations http2ServerOperations = (Http2ServerOperations) ChannelOperations.get(ctx.channel());
			decoder().frameListener(http2ServerOperations);
			Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
			encoder().writeHeaders(ctx, 1, headers, 0, false, ctx.newPromise());
			encoder().writeData(ctx, 1, EMPTY_BUFFER, 0, true, ctx.newPromise());
		}
		super.userEventTriggered(ctx, evt);
		ctx.read();
	}

	private Http2Headers http1ToHttp2Headers(FullHttpRequest request) {
		CharSequence host = request.headers()
		                           .get(HttpHeaderNames.HOST);
		Http2Headers http2Headers =
				new DefaultHttp2Headers().method(HttpMethod.GET.asciiName())
				                         .path(request.uri())
				                         .scheme(HttpScheme.HTTP.name());
		if (host != null) {
			http2Headers.authority(host);
		}
		return http2Headers;
	}
}
