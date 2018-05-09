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
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.channel.ChannelOperations;

import static io.netty.handler.logging.LogLevel.INFO;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
final class Http2ServerHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<Http2ServerHandler, Http2ServerHandlerBuilder> {

	static final Http2FrameLogger LOGGER = new Http2FrameLogger(INFO, Http2ServerHandler.class);

	final ConnectionEvents listener;

	Http2ServerHandlerBuilder(ConnectionEvents listener) {
		frameLogger(LOGGER);
		this.listener = listener;
	}

	@Override
	public Http2ServerHandler build() {
		return super.build();
	}

	@Override
	protected Http2ServerHandler build(Http2ConnectionDecoder decoder,
			Http2ConnectionEncoder encoder, Http2Settings initialSettings){
		Http2ServerHandler handler = new Http2ServerHandler(decoder, encoder, initialSettings);
		frameListener(new InitialHttp2FrameListener(handler, listener));
		return handler;
	}


	static final class InitialHttp2FrameListener extends Http2FrameAdapter {
		final Http2ServerHandler handler;
		final ConnectionEvents listener;

		InitialHttp2FrameListener(Http2ServerHandler handler, ConnectionEvents listener) {
			this.handler = handler;
			this.listener = listener;
		}

		@Override
		public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) throws Http2Exception {
			Http2ServerOperations http2ServerOperations = (Http2ServerOperations) ChannelOperations.get(ctx.channel());
			if (http2ServerOperations != null) {
				Http2FrameListener listener = http2ServerOperations.http2FrameListener();
				handler.decoder()
				       .frameListener(listener);
				listener.onSettingsRead(ctx, settings);
			}
			else {
				throw new IllegalStateException();
			}
		}
	}
}
