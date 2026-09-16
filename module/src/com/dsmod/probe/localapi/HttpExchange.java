package com.dsmod.probe.localapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTP request/response pair.
 *
 * <p>A deliberately small implementation of what the OpenAI and Anthropic
 * clients actually need: a request line, case insensitive headers, a body read
 * either by content length or chunked framing, and a response writer able to
 * emit a fixed length body or a chunked stream (used for SSE).
 *
 * <p>Keep alive is supported but never assumed: every socket is treated as
 * single-flight, so a client that pipelines gets answers in order and nothing
 * else.
 */
public final class HttpExchange {

    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final int MAX_HEADER_LINES = 128;

    public final String method;
    public final String rawTarget;
    public final String path;
    public final Map<String, String> headers;
    public final InputStream input;
    public final OutputStream output;

    private boolean headersWritten;

    private HttpExchange(String method, String rawTarget, String path,
            Map<String, String> headers, InputStream input, OutputStream output) {
        this.method = method;
        this.rawTarget = rawTarget;
        this.path = path;
        this.headers = headers;
        this.input = input;
        this.output = output;
    }

    /** Parses the request line and headers from the socket input. */
    static HttpExchange parse(InputStream input, OutputStream output) throws IOException {
        String line = readLine(input);
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            throw new IOException("malformed request line");
        }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        Map<String, String> headers = new HashMap<String, String>();
        while (true) {
            String headerLine = readLine(input);
            if (headerLine == null || headerLine.isEmpty()) {
                break;
            }
            int colon = headerLine.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = headerLine.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = headerLine.substring(colon + 1).trim();
            headers.put(name, value);
        }
        String path = stripQuery(target);
        return new HttpExchange(method, target, path,
                Collections.unmodifiableMap(headers), input, output);
    }

    private static String stripQuery(String target) {
        int mark = target.indexOf('?');
        String path = mark < 0 ? target : target.substring(0, mark);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** Rendered origin path, normalised so trailing slashes do not matter. */
    public String path() {
        return path;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.US));
    }

    /** Reads the request body, honouring Content-Length and chunked framing. */
    public byte[] body() throws IOException {
        String transfer = header("transfer-encoding");
        if (transfer != null && transfer.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(input);
        }
        String lengthHeader = header("content-length");
        if (lengthHeader == null) {
            return new byte[0];
        }
        int length;
        try {
            length = Integer.parseInt(lengthHeader.trim());
        } catch (NumberFormatException ignored) {
            return new byte[0];
        }
        if (length <= 0) {
            return new byte[0];
        }
        if (length > MAX_BODY_BYTES) {
            throw new IOException("request body too large");
        }
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return buffer;
    }

    private static byte[] readChunked(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            String digits = semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon);
            int size;
            try {
                size = Integer.parseInt(digits.trim(), 16);
            } catch (NumberFormatException ignored) {
                throw new IOException("malformed chunk size");
            }
            if (size == 0) {
                readLine(input); // trailing CRLF after the terminating chunk
                break;
            }
            if (out.size() + size > MAX_BODY_BYTES) {
                throw new IOException("request body too large");
            }
            byte[] chunk = new byte[size];
            int offset = 0;
            while (offset < size) {
                int read = input.read(chunk, offset, size - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            out.write(chunk, 0, offset);
            readLine(input); // CRLF terminating the chunk data
        }
        return out.toByteArray();
    }

    /** Writes a complete response with a known body. */
    public void respond(int status, String contentType, byte[] payload) throws IOException {
        byte[] body = payload == null ? new byte[0] : payload;
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes("UTF-8"));
        output.write(body);
        output.flush();
        headersWritten = true;
    }

    /** Starts a chunked (streaming) response for SSE output. */
    public void startStreaming(int status, String contentType) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Cache-Control: no-cache\r\n");
        head.append("X-Accel-Buffering: no\r\n");
        head.append("Transfer-Encoding: chunked\r\n");
        head.append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes("UTF-8"));
        output.flush();
        headersWritten = true;
    }

    /** Writes one chunk of a streaming response. */
    public void writeChunk(byte[] payload) throws IOException {
        if (payload.length == 0) {
            return;
        }
        output.write(Integer.toHexString(payload.length).getBytes("UTF-8"));
        output.write("\r\n".getBytes("UTF-8"));
        output.write(payload);
        output.write("\r\n".getBytes("UTF-8"));
        output.flush();
    }

    /** Emits one SSE frame. */
    public void writeSse(String data) throws IOException {
        writeChunk(("data: " + data + "\n\n").getBytes("UTF-8"));
    }

    /** Terminates a chunked response cleanly. */
    public void endStreaming() throws IOException {
        output.write("0\r\n\r\n".getBytes("UTF-8"));
        output.flush();
    }

    public boolean headersWritten() {
        return headersWritten;
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Error";
        }
    }

    /** Reads one CRLF or LF terminated line. */
    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int count = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (line.size() == 0) {
                    return null;
                }
                break;
            }
            if (++count > 16 * 1024) {
                throw new IOException("header line too long");
            }
            if (value == '\n') {
                break;
            }
            if (value == '\r') {
                previous = value;
                continue;
            }
            if (previous == '\r') {
                // Should not happen, but stay lenient.
            }
            line.write(value);
            previous = value;
        }
        return new String(line.toByteArray(), "UTF-8");
    }

    static int maxHeaderLines() {
        return MAX_HEADER_LINES;
    }
}
