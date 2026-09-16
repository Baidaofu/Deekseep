package com.dsmod.probe.localapi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny blocking HTTP server used by the Local API.
 *
 * <p>The listener is intentionally plain: one accept thread plus a bounded pool
 * of workers, no epoll, no NIO selector games. Throughput needs are a handful
 * of concurrent local clients, and simplicity here means fewer ways for the
 * host application to wedge.
 *
 * <p>The {@link Handler} is invoked per connection on a worker thread. Every
 * exception is contained: the worst possible outcome for a client is a closed
 * socket, never a crashed hook.
 */
public final class HttpServer {

    public interface Handler {
        /**
         * Serves one request. Implementations must not throw; they should
         * convert failures into responses.
         */
        void handle(HttpExchange exchange) throws IOException;
    }

    private final Handler handler;
    private final ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeJobs = new AtomicInteger();

    private Thread acceptThread;
    private ServerSocket serverSocket;

    public HttpServer(Handler handler, int workerCount) {
        this.handler = handler;
        this.pool = Executors.newFixedThreadPool(Math.max(2, workerCount));
    }

    /** Binds {@code socket} and begins accepting. Returns the bound port. */
    public int start(ServerSocket socket) throws IOException {
        this.serverSocket = socket;
        running.set(true);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "Deekseep-local-api");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return socket.getLocalPort();
    }

    /** True while the accept loop is alive. */
    public boolean isRunning() {
        return running.get() && acceptThread != null && acceptThread.isAlive();
    }

    public int boundPort() {
        return serverSocket == null ? 0 : serverSocket.getLocalPort();
    }

    public String boundHost() {
        if (serverSocket == null) {
            return null;
        }
        InetAddress address = serverSocket.getInetAddress();
        return address == null ? null : address.getHostAddress();
    }

    /** Stops accepting and closes the listening socket. */
    public void stop() {
        running.set(false);
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed; nothing to unwind.
            }
        }
        Thread thread = acceptThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        pool.shutdownNow();
    }

    public int activeRequests() {
        return activeJobs.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (SocketException ignored) {
                if (!running.get()) {
                    return;
                }
                continue;
            } catch (IOException ignored) {
                return;
            }
            configure(client);
            final Socket socket = client;
            activeJobs.incrementAndGet();
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(socket);
                    }
                });
            } catch (Throwable ignored) {
                activeJobs.decrementAndGet();
                close(socket);
            }
        }
    }

    private void configure(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            socket.setKeepAlive(false);
        } catch (SocketException ignored) {
            // Optional tuning; ignore.
        }
    }

    private void serve(Socket socket) {
        try {
            HttpExchange exchange = HttpExchange.parse(socket.getInputStream(),
                    socket.getOutputStream());
            if (exchange != null) {
                try {
                    handler.handle(exchange);
                } catch (Throwable failure) {
                    try {
                        if (!exchange.headersWritten()) {
                            exchange.respond(500, "application/json", errorBody(500,
                                    "internal_error",
                                    String.valueOf(failure.getMessage())));
                        }
                    } catch (Throwable ignored) {
                        // Nothing left to do but close.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Malformed request, closed socket, TLS handshake failure.
        } finally {
            activeJobs.decrementAndGet();
            close(socket);
        }
    }

    private static byte[] errorBody(int status, String code, String message) {
        try {
            org.json.JSONObject error = new org.json.JSONObject();
            error.put("message", message == null ? "" : message);
            error.put("type", status >= 500 ? "server_error" : "invalid_request_error");
            error.put("code", code);
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("error", error);
            return body.toString().getBytes("UTF-8");
        } catch (Throwable ignored) {
            return "{\"error\":{\"message\":\"internal error\"}}".getBytes();
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
