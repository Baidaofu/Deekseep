package com.dsmod.probe.localapi;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal DER (Distinguished Encoding Rules) writer.
 *
 * <p>Implements only what a self issued X.509 certificate needs: definite
 * length SEQUENCE/SET, INTEGER, OID, NULL, BOOLEAN, OCTET STRING, BIT STRING,
 * UTCTime, UTF8String, and explicit context specific tagging.
 *
 * <p>Containers are tracked with an explicit stack: {@link #sequenceStart()}
 * pushes a fresh buffer and {@link #sequenceEnd()} pops it, encodes it with its
 * tag and length, and appends it to its parent. That removes any need to
 * back-patch lengths.
 */
final class Der {

    private final Deque<ByteArrayOutputStream> stack = new ArrayDeque<ByteArrayOutputStream>();
    private final ByteArrayOutputStream root = new ByteArrayOutputStream();

    Der() {
        stack.push(root);
    }

    private ByteArrayOutputStream current() {
        ByteArrayOutputStream top = stack.peek();
        if (top == null) {
            throw new IllegalStateException("DER stack is empty");
        }
        return top;
    }

    // ------------------------------------------------------------ containers

    /** Opens a {@code SEQUENCE} (0x30). */
    void sequenceStart() {
        stack.push(new ByteArrayOutputStream());
    }

    /** Closes the innermost {@code SEQUENCE}, appending it to its parent. */
    void sequenceEnd() {
        closeContainer(0x30);
    }

    /** Opens a {@code SET} (0x31). */
    void setStart() {
        stack.push(new ByteArrayOutputStream());
    }

    /** Closes the innermost {@code SET}, appending it to its parent. */
    void setEnd() {
        closeContainer(0x31);
    }

    private void closeContainer(int tag) {
        ByteArrayOutputStream child = stack.pop();
        ByteArrayOutputStream parent = current();
        emit(parent, tag, child.toByteArray());
    }

    /** Writes already encoded bytes verbatim into the current container. */
    void writeRaw(byte[] value) {
        ByteArrayOutputStream target = current();
        target.write(value, 0, value.length);
    }

    // ------------------------------------------------------------- primitives

    /** DER {@code INTEGER}. */
    void integer(BigInteger value) {
        writeRaw(integerBytes(value));
    }

    /** Encodes an INTEGER without appending it. */
    byte[] integerBytes(BigInteger value) {
        byte[] magnitude = value.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x02);
        writeLength(out, magnitude.length);
        out.write(magnitude, 0, magnitude.length);
        return out.toByteArray();
    }

    /** DER {@code BOOLEAN}. */
    void booleanValue(boolean value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        writeLength(out, 1);
        out.write(value ? 0xff : 0x00);
        writeRaw(out.toByteArray());
    }

    /** DER {@code NULL}. */
    void nullValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x05);
        writeLength(out, 0);
        writeRaw(out.toByteArray());
    }

    /** DER {@code OCTET STRING}. */
    void octetString(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x04);
        writeLength(out, value.length);
        out.write(value, 0, value.length);
        writeRaw(out.toByteArray());
    }

    /** DER {@code UTF8String}. */
    void utf8String(String value) throws Exception {
        byte[] payload = value.getBytes("UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x0c);
        writeLength(out, payload.length);
        out.write(payload, 0, payload.length);
        writeRaw(out.toByteArray());
    }

    /** DER {@code OID} in dotted notation. */
    void oid(String dotted) {
        writeRaw(oidBytes(dotted));
    }

    /** DER OID without appending it. */
    byte[] oidBytes(String dotted) {
        String[] arcs = dotted.split("\\.");
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        int first = Integer.parseInt(arcs[0]);
        int second = Integer.parseInt(arcs[1]);
        content.write(first * 40 + second);
        for (int i = 2; i < arcs.length; i++) {
            writeBase128(content, Long.parseLong(arcs[i]));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x06);
        writeLength(out, content.size());
        out.write(content.toByteArray(), 0, content.size());
        return out.toByteArray();
    }

    /** UTC time rendered as {@code yymmddHHMMSSZ}. */
    void utcTime(long millis) {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] payload = format.format(new java.util.Date(millis)).getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x17);
        writeLength(out, payload.length);
        out.write(payload, 0, payload.length);
        writeRaw(out.toByteArray());
    }

    /** Primitive with a context specific tag, written into the current container. */
    void tagWrite(int tag, byte[] value) {
        ByteArrayOutputStream target = current();
        emit(target, tag, value);
    }

    /** Wraps already encoded content in an explicit constructed context tag. */
    void explicit(int tagNumber, byte[] content) {
        emit(current(), 0xa0 | tagNumber, content);
    }

    // ---------------------------------------------------------- certificates

    /** {@code AlgorithmIdentifier} with NULL parameters. */
    void algorithmIdentifier(String oid) {
        sequenceStart();
        oid(oid);
        nullValue();
        sequenceEnd();
    }

    /** {@code SubjectPublicKeyInfo}: the platform already encodes it for us. */
    void subjectPublicKeyInfo(java.security.PublicKey key) {
        writeRaw(key.getEncoded());
    }

    /** X.501 Name holding a single {@code CN} attribute. */
    void name(String distinguishedName) throws Exception {
        String cn = distinguishedName.startsWith("CN=")
                ? distinguishedName.substring(3) : distinguishedName;
        sequenceStart();
        setStart();
        sequenceStart();
        oid("2.5.4.3");
        utf8String(cn);
        sequenceEnd();
        setEnd();
        sequenceEnd();
    }

    /** {@code Validity} from two epoch-millisecond values. */
    void validity(long notBefore, long notAfter) {
        sequenceStart();
        utcTime(notBefore);
        utcTime(notAfter);
        sequenceEnd();
    }

    // --------------------------------------------------------------- output

    byte[] toByteArray() {
        if (stack.size() != 1) {
            throw new IllegalStateException("unbalanced DER containers");
        }
        return root.toByteArray();
    }

    // -------------------------------------------------------------- helpers

    private static void emit(ByteArrayOutputStream target, int tag, byte[] content) {
        target.write(tag);
        writeLength(target, content.length);
        target.write(content, 0, content.length);
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
            return;
        }
        int bytesNeeded = 1;
        int remaining = length >>> 8;
        while (remaining > 0) {
            bytesNeeded++;
            remaining >>>= 8;
        }
        out.write(0x80 | bytesNeeded);
        for (int i = bytesNeeded - 1; i >= 0; i--) {
            out.write((length >>> (8 * i)) & 0xff);
        }
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int length = 1;
        long shifted = value;
        while (shifted > 127) {
            shifted >>>= 7;
            length++;
        }
        for (int i = length - 1; i >= 0; i--) {
            int octet = (int) ((value >>> (7 * i)) & 0x7f);
            if (i > 0) {
                octet |= 0x80;
            }
            out.write(octet);
        }
    }
}
