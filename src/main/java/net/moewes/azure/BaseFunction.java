package net.moewes.azure;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.quarkus.netty.runtime.virtual.VirtualClientConnection;
import io.quarkus.netty.runtime.virtual.VirtualResponseHandler;
import io.quarkus.runtime.Application;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import org.jboss.logging.Logger;

public class BaseFunction {
    private static final Logger log = Logger.getLogger("io.quarkus.azure");

    protected static String deploymentStatus;
    protected static boolean started = false;
    protected static boolean bootstrapError = false;

    private static final int BUFFER_SIZE = 8096;

    protected static void initQuarkus() {
        StringWriter error = new StringWriter();
        PrintWriter errorWriter = new PrintWriter(error, true);
        if (Application.currentApplication() == null) { // were we already bootstrapped?  Needed for mock azure unit testing.
            try {
                Class<?> appClass = Class.forName("io.quarkus.runner.ApplicationImpl");
                String[] args = {};
                Application app = (Application) appClass.newInstance();
                app.start(args);
                errorWriter.println("Quarkus bootstrapped successfully.");
                started = true;
            } catch (Throwable ex) {
                bootstrapError = true;
                errorWriter.println("Quarkus bootstrap failed.");
                ex.printStackTrace(errorWriter);
            }
        } else {
            errorWriter.println("Quarkus bootstrapped successfully.");
            started = true;
        }
        deploymentStatus = error.toString();
    }

    protected HttpResponseMessage dispatch(HttpRequestMessage<Optional<String>> request, boolean patch) {
        try {
            return nettyDispatch(request, patch);
        } catch (Exception e) {
            e.printStackTrace();
            return request
                    .createResponseBuilder(HttpStatus.valueOf(500)).build();
        }
    }

    protected HttpResponseMessage nettyDispatch(HttpRequestMessage<Optional<String>> request, boolean patch)
            throws Exception {
        String path = request.getUri().getRawPath();
        String query = request.getUri().getRawQuery();
        if (query != null)
            path = path + '?' + query;
        String host = request.getUri().getHost();
        if (request.getUri().getPort() != -1) {
            host = host + ':' + request.getUri().getPort();
        }
        DefaultHttpRequest nettyRequest;
        if (patch) {
             nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                    HttpMethod.valueOf("PATCH"), path);
        } else {
             nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                    HttpMethod.valueOf(request.getHttpMethod().name()), path);
        }
        nettyRequest.headers().set("Host", host);
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            nettyRequest.headers().add(header.getKey(), header.getValue());
        }

        HttpContent requestContent = LastHttpContent.EMPTY_LAST_CONTENT;
        if (request.getBody().isPresent()) {
            ByteBuf body = Unpooled.wrappedBuffer(request.getBody().get().getBytes());
            requestContent = new DefaultLastHttpContent(body);
        }

        BaseFunction.ResponseHandler handler = new BaseFunction.ResponseHandler(request);
        VirtualClientConnection<?> connection = VirtualClientConnection.connect(handler, VertxHttpRecorder.VIRTUAL_HTTP);

        connection.sendMessage(nettyRequest);
        connection.sendMessage(requestContent);
        try {
            return handler.future.get();
        } finally {
            connection.close();
        }
    }

    private static ByteArrayOutputStream createByteStream() {
        ByteArrayOutputStream baos;
        baos = new ByteArrayOutputStream(BUFFER_SIZE);
        return baos;
    }

    private static class ResponseHandler implements VirtualResponseHandler {
        HttpResponseMessage.Builder responseBuilder;
        ByteArrayOutputStream baos;
        WritableByteChannel byteChannel;
        CompletableFuture<HttpResponseMessage> future = new CompletableFuture<>();
        final HttpRequestMessage<Optional<String>> request;

        public ResponseHandler(HttpRequestMessage<Optional<String>> request) {
            this.request = request;
        }

        @Override
        public void handleMessage(Object msg) {
            try {
                //log.info("Got message: " + msg.getClass().getName());

                if (msg instanceof HttpResponse) {
                    HttpResponse res = (HttpResponse) msg;
                    responseBuilder = request.createResponseBuilder(HttpStatus.valueOf(res.status().code()));
                    String set_cookie_header = null;
                    for (Map.Entry<String, String> entry : res.headers()) {
                        if ("set-cookie".equals(entry.getKey())) {
                            set_cookie_header = (entry.getValue().startsWith("q_auth=;")) ? set_cookie_header : entry.getValue();
                        } else {
                            responseBuilder.header(entry.getKey(), entry.getValue());
                        }
                    }
                    if (set_cookie_header != null) {
                        responseBuilder.header("Set-Cookie",set_cookie_header);
                    }
                }
                if (msg instanceof HttpContent) {
                    HttpContent content = (HttpContent) msg;
                    if (baos == null) {
                        baos = createByteStream();
                    }
                    int readable = content.content().readableBytes();
                    for (int i = 0; i < readable; i++) {
                        baos.write(content.content().readByte());
                    }
                }
                if (msg instanceof FileRegion) {
                    FileRegion file = (FileRegion) msg;
                    if (file.count() > 0 && file.transferred() < file.count()) {
                        if (baos == null)
                            baos = createByteStream();
                        if (byteChannel == null)
                            byteChannel = Channels.newChannel(baos);
                        file.transferTo(byteChannel, file.transferred());
                    }
                }
                if (msg instanceof LastHttpContent) {
                    responseBuilder.body(baos.toByteArray());
                    future.complete(responseBuilder.build());
                }
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void close() {
            if (!future.isDone())
                future.completeExceptionally(new RuntimeException("Connection closed"));
        }
    }
}
