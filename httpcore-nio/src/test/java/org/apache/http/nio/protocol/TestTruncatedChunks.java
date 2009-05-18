/*
 * $HeadURL$
 * $Revision$
 * $Date$
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.MalformedChunkCodingException;
import org.apache.http.TruncatedChunkException;
import org.apache.http.entity.ContentLengthStrategy;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultServerIOEventDispatch;
import org.apache.http.impl.nio.codecs.AbstractContentEncoder;
import org.apache.http.impl.nio.reactor.ExceptionEvent;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.mockup.SimpleEventListener;
import org.apache.http.mockup.SimpleNHttpRequestHandlerResolver;
import org.apache.http.mockup.TestHttpClient;
import org.apache.http.mockup.TestHttpServer;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.NHttpServerIOTarget;
import org.apache.http.nio.NHttpServiceHandler;
import org.apache.http.nio.entity.ConsumingNHttpEntity;
import org.apache.http.nio.entity.ContentInputStream;
import org.apache.http.nio.protocol.AsyncNHttpClientHandler;
import org.apache.http.nio.protocol.AsyncNHttpServiceHandler;
import org.apache.http.nio.protocol.NHttpRequestExecutionHandler;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.reactor.SessionOutputBuffer;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.nio.util.SimpleInputBuffer;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.util.CharArrayBuffer;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Tests for handling truncated chunks.
 */
public class TestTruncatedChunks extends TestCase {

    // ------------------------------------------------------------ Constructor
    public TestTruncatedChunks(String testName) {
        super(testName);
    }

    // ------------------------------------------------------------------- Main
    public static void main(String args[]) {
        String[] testCaseName = { TestTruncatedChunks.class.getName() };
        junit.textui.TestRunner.main(testCaseName);
    }

    // ------------------------------------------------------- TestCase Methods

    public static Test suite() {
        return new TestSuite(TestTruncatedChunks.class);
    }
    
    private static final byte[] GARBAGE = new byte[] {'1', '2', '3', '4', '5' };
    
    static class BrokenChunkEncoder extends AbstractContentEncoder {

        private final CharArrayBuffer lineBuffer;
        private boolean done;
        
        public BrokenChunkEncoder(
                final WritableByteChannel channel, 
                final SessionOutputBuffer buffer,
                final HttpTransportMetricsImpl metrics) {
            super(channel, buffer, metrics);
            this.lineBuffer = new CharArrayBuffer(16);
        }

        public void complete() throws IOException {
            this.completed = true;
        }

        public int write(ByteBuffer src) throws IOException {
            int chunk;
            if (!this.done) {
                this.lineBuffer.clear();
                this.lineBuffer.append(Integer.toHexString(GARBAGE.length * 10));
                this.buffer.writeLine(this.lineBuffer);
                this.buffer.write(ByteBuffer.wrap(GARBAGE));
                this.done = true;
                chunk = GARBAGE.length;
            } else {
                chunk = 0;
            }
            long bytesWritten = this.buffer.flush(this.channel);
            if (bytesWritten > 0) {
                this.metrics.incrementBytesTransferred(bytesWritten);
            }
            if (!this.buffer.hasData()) {
                this.channel.close();
            }
            return chunk;
        }
        
    };

    static class CustomServerIOEventDispatch extends DefaultServerIOEventDispatch {
        
        public CustomServerIOEventDispatch(
                final NHttpServiceHandler handler,
                final HttpParams params) {
            super(handler, params);
        }

        @Override
        protected NHttpServerIOTarget createConnection(final IOSession session) {
            
            return new DefaultNHttpServerConnection(
                    session, 
                    createHttpRequestFactory(), 
                    this.allocator, 
                    this.params) {

                        @Override
                        protected ContentEncoder createContentEncoder(
                                final long len,
                                final WritableByteChannel channel,
                                final SessionOutputBuffer buffer,
                                final HttpTransportMetricsImpl metrics) {
                            if (len == ContentLengthStrategy.CHUNKED) {
                                return new BrokenChunkEncoder(channel, buffer, metrics);
                            } else {
                                return super.createContentEncoder(len, channel, buffer, metrics);
                            }
                        }
                        
            };
        }
        
    }

    static class CustomTestHttpServer extends TestHttpServer {
        
        public CustomTestHttpServer(final HttpParams params) throws IOException {
            super(params);
        }

        @Override
        protected IOEventDispatch createIOEventDispatch(
                NHttpServiceHandler serviceHandler, HttpParams params) {
            return new CustomServerIOEventDispatch(serviceHandler, params);
        }
        
    }
    
    protected CustomTestHttpServer server;
    protected TestHttpClient client;
    
    @Override
    protected void setUp() throws Exception {
        HttpParams serverParams = new BasicHttpParams();
        serverParams
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 60000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.ORIGIN_SERVER, "TEST-SERVER/1.1");

        this.server = new CustomTestHttpServer(serverParams);

        HttpParams clientParams = new BasicHttpParams();
        clientParams
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 60000)
            .setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 30000)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.USER_AGENT, "TEST-CLIENT/1.1");

        this.client = new TestHttpClient(clientParams);
    }

    @Override
    protected void tearDown() {
        try {
            this.client.shutdown();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        List<ExceptionEvent> clogs = this.client.getAuditLog();
        if (clogs != null) {
            for (ExceptionEvent clog: clogs) {
                Throwable cause = clog.getCause();
                cause.printStackTrace();
            }
        }

        try {
            this.server.shutdown();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        List<ExceptionEvent> slogs = this.server.getAuditLog();
        if (slogs != null) {
            for (ExceptionEvent slog: slogs) {
                Throwable cause = slog.getCause();
                cause.printStackTrace();
            }
        }
    }
    
    public void testTruncatedChunkException() throws Exception {
        
        NHttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                return new BasicHttpRequest("GET", s);
            }
            
        };

        TestJob testjob = new TestJob(2000);
        Queue<TestJob> queue = new ConcurrentLinkedQueue<TestJob>();
        queue.add(testjob); 
        
        BasicHttpProcessor serverHttpProc = new BasicHttpProcessor();
        serverHttpProc.addInterceptor(new ResponseDate());
        serverHttpProc.addInterceptor(new ResponseServer());
        serverHttpProc.addInterceptor(new ResponseContent());
        serverHttpProc.addInterceptor(new ResponseConnControl());

        AsyncNHttpServiceHandler serviceHandler = new AsyncNHttpServiceHandler(
                serverHttpProc,
                new DefaultHttpResponseFactory(),
                new DefaultConnectionReuseStrategy(),
                this.server.getParams());

        serviceHandler.setHandlerResolver(
                new SimpleNHttpRequestHandlerResolver(new TestRequestHandler(true)));
        serviceHandler.setEventListener(
                new SimpleEventListener());

        BasicHttpProcessor clientHttpProc = new BasicHttpProcessor();
        clientHttpProc.addInterceptor(new RequestContent());
        clientHttpProc.addInterceptor(new RequestTargetHost());
        clientHttpProc.addInterceptor(new RequestConnControl());
        clientHttpProc.addInterceptor(new RequestUserAgent());
        clientHttpProc.addInterceptor(new RequestExpectContinue());

        AsyncNHttpClientHandler clientHandler = new AsyncNHttpClientHandler(
                clientHttpProc,
                requestExecutionHandler,
                new DefaultConnectionReuseStrategy(),
                this.client.getParams());

        clientHandler.setEventListener(
                new SimpleEventListener() {

                    @Override
                    public void fatalIOException(
                            final IOException ex,
                            final NHttpConnection conn) {
                        HttpContext context = conn.getContext();
                        TestJob testjob = (TestJob) context.getAttribute("job");
                        testjob.fail(ex.getMessage(), ex);
                    }
                    
                });
        
        this.server.start(serviceHandler);
        this.client.start(clientHandler);

        ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();
        InetSocketAddress serverAddress = (InetSocketAddress) endpoint.getAddress();

        this.client.openConnection(
                new InetSocketAddress("localhost", serverAddress.getPort()),
                queue);

        testjob.waitFor();
        assertFalse(testjob.isSuccessful());
        assertNotNull(testjob.getException());
        assertTrue(testjob.getException() instanceof MalformedChunkCodingException);
    }

    static class LenientNHttpEntity extends HttpEntityWrapper implements ConsumingNHttpEntity {
        
        private final static int BUFFER_SIZE = 2048;

        private final SimpleInputBuffer buffer;
        private boolean finished;
        private boolean consumed;

        public LenientNHttpEntity(
                final HttpEntity httpEntity,
                final ByteBufferAllocator allocator) {
            super(httpEntity);
            this.buffer = new SimpleInputBuffer(BUFFER_SIZE, allocator);
        }

        public void consumeContent(
                final ContentDecoder decoder,
                final IOControl ioctrl) throws IOException {
            try {
                this.buffer.consumeContent(decoder);
                if (decoder.isCompleted()) {
                    this.finished = true;
                }
            } catch (TruncatedChunkException ex) {
                this.buffer.shutdown();
                this.finished = true;
            }
        }

        public void finish() {
            this.finished = true;
        }

        @Override
        public void consumeContent() throws IOException {
        }

        @Override
        public InputStream getContent() throws IOException {
            if (!this.finished) {
                throw new IllegalStateException("Entity content has not been fully received");
            }
            if (this.consumed) {
                throw new IllegalStateException("Entity content has been consumed");
            }
            this.consumed = true;
            return new ContentInputStream(this.buffer);
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public void writeTo(final OutputStream outstream) throws IOException {
            if (outstream == null) {
                throw new IllegalArgumentException("Output stream may not be null");
            }
            InputStream instream = getContent();
            byte[] buffer = new byte[BUFFER_SIZE];
            int l;
            // consume until EOF
            while ((l = instream.read(buffer)) != -1) {
                outstream.write(buffer, 0, l);
            }
        }
        
    }
    
    public void testIgnoreTruncatedChunkException() throws Exception {
        
        NHttpRequestExecutionHandler requestExecutionHandler = new TestRequestExecutionHandler() {

            @Override
            protected HttpRequest generateRequest(final TestJob testjob) {
                String s = testjob.getPattern() + "x" + testjob.getCount(); 
                return new BasicHttpRequest("GET", s);
            }

            @Override
            public ConsumingNHttpEntity responseEntity(
                    final HttpResponse response, 
                    final HttpContext context) throws IOException {
                return new LenientNHttpEntity(response.getEntity(),
                        new HeapByteBufferAllocator());
            }
            
        };

        TestJob testjob = new TestJob(2000);
        Queue<TestJob> queue = new ConcurrentLinkedQueue<TestJob>();
        queue.add(testjob); 
        
        BasicHttpProcessor serverHttpProc = new BasicHttpProcessor();
        serverHttpProc.addInterceptor(new ResponseDate());
        serverHttpProc.addInterceptor(new ResponseServer());
        serverHttpProc.addInterceptor(new ResponseContent());
        serverHttpProc.addInterceptor(new ResponseConnControl());

        AsyncNHttpServiceHandler serviceHandler = new AsyncNHttpServiceHandler(
                serverHttpProc,
                new DefaultHttpResponseFactory(),
                new DefaultConnectionReuseStrategy(),
                this.server.getParams());

        serviceHandler.setHandlerResolver(
                new SimpleNHttpRequestHandlerResolver(new TestRequestHandler(true)));
        serviceHandler.setEventListener(
                new SimpleEventListener());

        BasicHttpProcessor clientHttpProc = new BasicHttpProcessor();
        clientHttpProc.addInterceptor(new RequestContent());
        clientHttpProc.addInterceptor(new RequestTargetHost());
        clientHttpProc.addInterceptor(new RequestConnControl());
        clientHttpProc.addInterceptor(new RequestUserAgent());
        clientHttpProc.addInterceptor(new RequestExpectContinue());

        AsyncNHttpClientHandler clientHandler = new AsyncNHttpClientHandler(
                clientHttpProc,
                requestExecutionHandler,
                new DefaultConnectionReuseStrategy(),
                this.client.getParams());

        clientHandler.setEventListener(
                new SimpleEventListener());
        
        this.server.start(serviceHandler);
        this.client.start(clientHandler);

        ListenerEndpoint endpoint = this.server.getListenerEndpoint();
        endpoint.waitFor();
        InetSocketAddress serverAddress = (InetSocketAddress) endpoint.getAddress();

        this.client.openConnection(
                new InetSocketAddress("localhost", serverAddress.getPort()),
                queue);

        testjob.waitFor();
        if (testjob.isSuccessful()) {
            assertEquals(HttpStatus.SC_OK, testjob.getStatusCode());
            assertEquals(new String(GARBAGE, "US-ASCII"), testjob.getResult());
        } else {
            fail(testjob.getFailureMessage());
        }
    }

}
