package com.dsmod.probe.localapi;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Per-device HTTPS certificate authority for the Local API.
 *
 * <p>On first use a self signed CA is generated and kept in a PKCS12 keystore
 * inside the DeepSeek private directory. That CA then signs the server
 * certificate, whose Subject Alternative Name list contains every address the
 * listener may answer on: loopback plus the current LAN address. When roaming
 * to a new network the leaf is silently re-issued, so the same CA keeps working
 * without asking the user to trust anything again.
 *
 * <p>Because the CA is unique per installation, trusting it is deliberate.
 * Three routes are offered: the Android credential installer (user trust), a
 * flashable Magisk/KernelSU/APatch module (system trust), or exporting the PEM
 * so a client can pin it instead.
 *
 * <p>Certificates are assembled with the tiny {@link Der} writer rather than
 * pulling in a crypto provider, keeping the module free of bundled
 * dependencies.
 */
public final class TlsDirector {

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String SIGNATURE_OID = "1.2.840.113549.1.1.11";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;

    private static final String CA_STORE = "dq0_ca.p12";
    private static final String SERVER_STORE = "dq0_server.p12";
    private static final String CA_CERT = "dq0_ca.cer";
    private static final String PASSWORD_FILE = "dq0_tls_password";
    private static final String VERIFIED = "dq0_tls_verified";
    private static final String CA_ALIAS = "deekseep-local-ca";
    private static final String SERVER_ALIAS = "dq0-tls";

    private static final long VALIDITY_MS = 3650L * 24L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();

    private TlsDirector() {
    }

    /** Prepared key material, ready to initialise an {@code SSLContext}. */
    public static final class Material {
        public final KeyStore authorityStore;
        public final KeyStore serverStore;
        public final char[] password;
        public final X509Certificate authorityCertificate;

        Material(KeyStore authorityStore, KeyStore serverStore, char[] password,
                X509Certificate authorityCertificate) {
            this.authorityStore = authorityStore;
            this.serverStore = serverStore;
            this.password = password;
            this.authorityCertificate = authorityCertificate;
        }
    }

    /** Ensures the CA exists and issues a leaf covering current addresses. */
    public static void prepare(Context context) throws Exception {
        Context app = appContext(context);
        if (app == null) {
            throw new IllegalArgumentException("missing context");
        }
        synchronized (LOCK) {
            File files = app.getFilesDir();
            char[] password = password(app);
            File authorityFile = new File(files, CA_STORE);
            File authorityCertFile = new File(files, CA_CERT);
            PrivateKey authorityKey;
            X509Certificate authorityCertificate;
            if (authorityFile.isFile() && authorityCertFile.isFile()) {
                KeyStore existing = load(authorityFile, password);
                authorityCertificate = (X509Certificate) existing.getCertificate(CA_ALIAS);
                authorityKey = (PrivateKey) existing.getKey(CA_ALIAS, password);
                if (authorityKey == null || authorityCertificate == null) {
                    throw new java.io.IOException("Local API CA keystore is incomplete");
                }
            } else {
                KeyPair pair = generateKeyPair();
                authorityCertificate = selfSignedAuthority(pair);
                KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE);
                store.load(null, null);
                store.setKeyEntry(CA_ALIAS, pair.getPrivate(), password,
                        new Certificate[] {authorityCertificate});
                writeStore(authorityFile, store, password);
                LocalApiConfig.writeAtomic(authorityCertFile,
                        authorityCertificate.getEncoded());
                authorityKey = pair.getPrivate();
            }

            File serverFile = new File(files, SERVER_STORE);
            boolean reissue = true;
            if (serverFile.isFile()) {
                try {
                    X509Certificate leaf =
                            (X509Certificate) load(serverFile, password)
                                    .getCertificate(SERVER_ALIAS);
                    leaf.verify(authorityCertificate.getPublicKey());
                    reissue = !covers(leaf, subjectNames());
                } catch (Throwable ignored) {
                    reissue = true;
                }
            }
            if (reissue) {
                KeyPair pair = generateKeyPair();
                X509Certificate leaf = issueLeaf(authorityCertificate, authorityKey,
                        pair.getPublic());
                KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE);
                store.load(null, null);
                store.setKeyEntry(SERVER_ALIAS, pair.getPrivate(), password,
                        new Certificate[] {leaf, authorityCertificate});
                writeStore(serverFile, store, password);
                File verified = new File(files, VERIFIED);
                if (verified.exists() && !verified.delete()) {
                    // Non fatal: verification simply has to be repeated.
                }
            }
        }
    }

    /** Loads keystores ready to feed {@code KeyManagerFactory} / trust setup. */
    public static Material material(Context context) throws Exception {
        Context app = appContext(context);
        if (app == null) {
            throw new IllegalArgumentException("missing context");
        }
        prepare(context);
        File files = app.getFilesDir();
        char[] password = password(app);
        KeyStore authorityStore = load(new File(files, CA_STORE), password);
        KeyStore serverStore = load(new File(files, SERVER_STORE), password);
        X509Certificate authority =
                (X509Certificate) authorityStore.getCertificate(CA_ALIAS);
        return new Material(authorityStore, serverStore, password, authority);
    }

    /** Exports the CA certificate to Downloads for manual installation. */
    public static String exportCaCertificate(Context context) throws Exception {
        Context app = appContext(context);
        if (app == null) {
            throw new IllegalArgumentException("missing context");
        }
        prepare(context);
        X509Certificate certificate = (X509Certificate)
                material(context).authorityStore.getCertificate(CA_ALIAS);
        return writeDownload(context, "Deekseep-Local-API-CA.cer",
                "application/x-x509-ca-cert", certificate.getEncoded());
    }

    /** Builds a flashable module granting system-wide trust to the CA. */
    public static String exportRootModule(Context context) throws Exception {
        Context app = appContext(context);
        if (app == null) {
            throw new IllegalArgumentException("missing context");
        }
        prepare(context);
        X509Certificate certificate = (X509Certificate)
                material(context).authorityStore.getCertificate(CA_ALIAS);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        writeEntry(zip, "module.prop", ("id=dq0_ca\n"
                + "name=Deekseep Local API CA\n"
                + "version=1.0\n"
                + "versionCode=1\n"
                + "author=Deekseep\n"
                + "description=Trust the per-device Deekseep Local API HTTPS CA\n")
                .getBytes("UTF-8"));
        writeEntry(zip, "customize.sh", rootInstaller().getBytes("UTF-8"));
        writeEntry(zip, "system/etc/security/cacerts/" + legacyFileName(context, certificate),
                pem(certificate));
        zip.finish();
        zip.close();
        return writeDownload(context, "Deekseep-CA-Root.zip", "application/zip",
                bytes.toByteArray());
    }

    /** SHA-256 fingerprint shown so users can verify what they trusted. */
    public static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format(Locale.US, "%02X", digest[i] & 0xff));
        }
        return builder.toString();
    }

    /** Every address the listener may legitimately answer on. */
    public static List<String> subjectNames() {
        List<String> names = new ArrayList<String>();
        names.add("127.0.0.1");
        names.add("localhost");
        String lan = lanAddress();
        if (lan != null) {
            names.add(lan);
        }
        return names;
    }

    /** Current Wi-Fi/ LAN IPv4 address, or null when unavailable. */
    public static String lanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface net = interfaces.nextElement();
                if (!net.isUp() || net.isLoopback() || net.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = net.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    public static void markVerified(Context context) {
        Context app = appContext(context);
        if (app == null) {
            return;
        }
        try {
            LocalApiConfig.writeAtomic(new File(app.getFilesDir(), VERIFIED), new byte[] {49});
        } catch (Throwable ignored) {
            // Marker file only; never fatal.
        }
    }

    public static boolean isVerified(Context context) {
        Context app = appContext(context);
        return app != null && new File(app.getFilesDir(), VERIFIED).isFile();
    }

    // ------------------------------------------------------------- internals

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE, new SecureRandom());
        return generator.generateKeyPair();
    }

    private static char[] password(Context context) throws Exception {
        File file = new File(context.getFilesDir(), PASSWORD_FILE);
        if (file.isFile()) {
            return new String(LocalApiConfig.readAll(file), "US-ASCII").toCharArray();
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String text = Base64.encodeToString(bytes, Base64.NO_WRAP);
        LocalApiConfig.writeAtomic(file, text.getBytes("US-ASCII"));
        return text.toCharArray();
    }

    private static KeyStore load(File file, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE);
        FileInputStream input = new FileInputStream(file);
        try {
            store.load(input, password);
            return store;
        } finally {
            input.close();
        }
    }

    private static void writeStore(File file, KeyStore store, char[] password) throws Exception {
        LocalApiConfig.writeAtomic(file, serialize(store, password));
    }

    private static byte[] serialize(KeyStore store, char[] password) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, password);
        return out.toByteArray();
    }

    private static boolean covers(X509Certificate leaf, List<String> expected) throws Exception {
        Collection<List<?>> names = leaf.getSubjectAlternativeNames();
        if (names == null) {
            return false;
        }
        for (String want : expected) {
            boolean found = false;
            for (List<?> entry : names) {
                if (entry.size() > 1 && want.equals(String.valueOf(entry.get(1)))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static String legacyFileName(Context context, X509Certificate certificate)
            throws Exception {
        // Android derives cacerts filenames from the subject; accept either form.
        return legacyName(certificate) + ".0";
    }

    private static String legacyName(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(certificate.getEncoded());
        return String.format(Locale.US, "%08x", ((digest[3] & 0xff) << 24)
                | ((digest[2] & 0xff) << 16) | ((digest[1] & 0xff) << 8) | (digest[0] & 0xff));
    }

    private static byte[] pem(X509Certificate certificate) throws Exception {
        String base64 = Base64.encodeToString(certificate.getEncoded(), Base64.DEFAULT);
        StringBuilder builder = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            builder.append(base64, i, Math.min(base64.length(), i + 64)).append('\n');
        }
        builder.append("-----END CERTIFICATE-----\n");
        return builder.toString().getBytes("US-ASCII");
    }

    private static String rootInstaller() {
        return "#!/system/bin/sh\n"
                + "ui_print \"- Deekseep Local API CA\"\n"
                + "ui_print \"- Install <?xml version='1.0'?> certificates into the system store\"\n"
                + "set_perm_recursive $MODPATH 0 0 0755 0644\n"
                + "set_perm $MODPATH/system/etc/security/cacerts/*.0 0 0 0644\n"
                + "ui_print \"- Reboot after installation\"\n";
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] payload)
            throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(payload);
        zip.closeEntry();
    }

    private static String writeDownload(Context context, String name, String mime, byte[] payload)
            throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            File directory = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new java.io.IOException("could not create Downloads directory");
            }
            File target = new File(directory, name);
            FileOutputStream output = new FileOutputStream(target, false);
            try {
                output.write(payload);
                output.getFD().sync();
                return target.getAbsolutePath();
            } finally {
                output.close();
            }
        }
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("_display_name", name);
        values.put("mime_type", mime);
        values.put("relative_path", android.os.Environment.DIRECTORY_DOWNLOADS);
        Uri uri = context.getContentResolver()
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new java.io.IOException("Downloads provider rejected file");
        }
        java.io.OutputStream output = context.getContentResolver().openOutputStream(uri, "w");
        if (output == null) {
            throw new java.io.IOException("could not open Downloads file");
        }
        try {
            output.write(payload);
            output.flush();
            return name;
        } finally {
            output.close();
        }
    }

    // ---------------------------------------------------------- X.509 writer

    private static X509Certificate selfSignedAuthority(KeyPair pair) throws Exception {
        byte[] tbs = toBeSigned(BigInteger.ONE, "CN=Deekseep Local API CA", pair.getPublic(),
                null, true, pair.getPublic());
        return assemble(tbs, pair.getPrivate());
    }

    private static X509Certificate issueLeaf(X509Certificate issuer, PrivateKey issuerKey,
            PublicKey leafKey) throws Exception {
        byte[] tbs = toBeSigned(serial(), issuer.getSubjectDN().getName(), leafKey,
                subjectNames(), false, issuer.getPublicKey());
        return assemble(tbs, issuerKey);
    }

    private static BigInteger serial() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return new BigInteger(1, bytes);
    }

    private static X509Certificate assemble(byte[] tbs, PrivateKey signerKey) throws Exception {
        Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
        signer.initSign(signerKey);
        signer.update(tbs);
        byte[] signature = signer.sign();

        Der certificate = new Der();
        certificate.sequenceStart();
        certificate.writeRaw(tbs);
        certificate.sequenceStart();
        certificate.oid(SIGNATURE_OID);
        certificate.nullValue();
        certificate.sequenceEnd();
        certificate.tagWrite(0x03, prefixedBitString(signature));
        certificate.sequenceEnd();
        return parse(certificate);
    }

    private static byte[] toBeSigned(BigInteger serial, String issuer, PublicKey publicKey,
            List<String> sanNames, boolean authority, PublicKey keyIdentifierSource)
            throws Exception {
        Der der = new Der();
        der.sequenceStart();
        der.explicit(0, der.integerBytes(BigInteger.valueOf(2L)));
        der.integer(serial);
        der.sequenceStart();
        der.oid(SIGNATURE_OID);
        der.nullValue();
        der.sequenceEnd();
        der.name(issuer);
        der.validity(System.currentTimeMillis(), System.currentTimeMillis() + VALIDITY_MS);
        der.name("CN=Deekseep Local API");
        der.subjectPublicKeyInfo(publicKey);
        der.explicit(3, extensions(sanNames, authority, keyIdentifierSource));
        der.sequenceEnd();
        return der.toByteArray();
    }

    private static byte[] extensions(List<String> sanNames, boolean authority,
            PublicKey keyIdentifierSource) throws Exception {
        Der extensions = new Der();
        extensions.sequenceStart();
        // basicConstraints
        extensions.writeRaw(extension("2.5.29.19", true,
                authority ? new byte[] {0x30, 0x03, 0x01, 0x01, (byte) 0xff}
                        : new byte[] {0x30, 0x00}));
        // keyUsage: CA signs certs, leaves do key exchange for TLS.
        extensions.writeRaw(extension("2.5.29.15", true, keyUsage(authority)));
        // subjectKeyIdentifier
        extensions.writeRaw(extension("2.5.29.14", false, keyIdentifier(keyIdentifierSource)));
        if (!authority) {
            // extendedKeyUsage: serverAuth only for the leaf.
            Der usage = new Der();
            usage.sequenceStart();
            usage.writeRaw(usage.oidBytes("1.3.6.1.5.5.7.3.1"));
            usage.sequenceEnd();
            extensions.writeRaw(extension("2.5.29.37", false, usage.toByteArray()));
        }
        if (sanNames != null && !sanNames.isEmpty()) {
            extensions.writeRaw(extension("2.5.29.17", false, generalNames(sanNames)));
        }
        extensions.sequenceEnd();
        return extensions.toByteArray();
    }

    private static byte[] extension(String oid, boolean critical, byte[] payload)
            throws Exception {
        Der extension = new Der();
        extension.sequenceStart();
        extension.oid(oid);
        if (critical) {
            extension.booleanValue(true);
        }
        extension.octetString(payload);
        extension.sequenceEnd();
        return extension.toByteArray();
    }

    private static byte[] keyUsage(boolean authority) throws Exception {
        // Prefixed BIT STRING: unused-bit count followed by the set bits.
        byte bits = authority ? (byte) 0x86 : (byte) 0xa0;
        byte unused = authority ? (byte) 1 : (byte) 6;
        return new byte[] {unused, bits};
    }

    private static byte[] keyIdentifier(PublicKey key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getEncoded());
        if (digest.length != 20) {
            throw new IllegalStateException("unexpected key digest length");
        }
        byte[] payload = new byte[digest.length + 2];
        payload[0] = 0x04;
        payload[1] = 0x14;
        System.arraycopy(digest, 0, payload, 2, digest.length);
        return payload;
    }

    private static byte[] generalNames(List<String> names) throws Exception {
        Der sequence = new Der();
        sequence.sequenceStart();
        for (String name : names) {
            byte[] packed = packAddress(name);
            if (packed == null) {
                sequence.tagWrite(0x82, name.getBytes("US-ASCII"));
            } else {
                sequence.tagWrite(0x87, packed);
            }
        }
        sequence.sequenceEnd();
        return sequence.toByteArray();
    }

    /** Returns the four raw octets of an IPv4 literal, or null for host names. */
    private static byte[] packAddress(String name) {
        String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] packed = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) {
                    return null;
                }
                packed[i] = (byte) value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return packed;
    }

    private static byte[] prefixedBitString(byte[] value) {
        byte[] payload = new byte[value.length + 1];
        payload[0] = 0x00;
        System.arraycopy(value, 0, payload, 1, value.length);
        return payload;
    }

    private static X509Certificate parse(Der certificate) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
                new java.io.ByteArrayInputStream(certificate.toByteArray()));
    }
}
