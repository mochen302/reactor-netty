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

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.DisposableServer;

import java.time.Duration;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
public class Http2ServerTests extends Http2ServerBaseTests {

	@Test
	public void testHttp2() throws Exception {
		DisposableServer server =
				Http2Server.create()
				           .port(0)
				           .secure()
				           .wiretap()
				           .handler((in, out) -> out.sendString(Flux.just("Hello", " from", " HTTP/2", " Server")))
				           .bindNow(Duration.ofSeconds(30));

		SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
		SslContext sslContext =
				SslContextBuilder.forClient()
				                 .sslProvider(provider)
				                 .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                 .applicationProtocolConfig(new ApplicationProtocolConfig(
				                     Protocol.ALPN,
				                     SelectorFailureBehavior.NO_ADVERTISE,
				                     SelectedListenerFailureBehavior.ACCEPT,
				                     ApplicationProtocolNames.HTTP_2,
				                     ApplicationProtocolNames.HTTP_1_1))
				                 .build();

		get(server.address(), new Http2ClientHandler(sslContext, "Hello from HTTP/2 Server"), "/", 3);

		server.disposeNow();
	}

	@Test
	public void testHttp2ClearText() throws Exception {
		DisposableServer server =
				Http2Server.create()
				           .tcpConfiguration(tcpServer -> tcpServer.host("127.0.0.1"))
				           .port(0)
				           .wiretap()
				           .handler((in, out) -> out.sendString(Flux.just("Hello", " from", " HTTP/2", " Server")))
				           .bindNow(Duration.ofSeconds(30));


		get(server.address(), new Http2ClientHandler(null, "Hello from HTTP/2 Server"), "/", 3);

		server.disposeNow();
	}
}
