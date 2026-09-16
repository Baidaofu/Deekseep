package com.dsmod.probe.localapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Regression coverage for the parts of the Local API that carry real logic:
 * model routing, key validation, bearer parsing, and transport cycling.
 *
 * <p>Runs on the desktop JVM, so it deliberately avoids anything that needs a
 * live {@code Context} or the Android JSON implementation.
 */
public final class LocalApiProtocolRegressionTest {

    public static void main(String[] args) {
        modelRouting();
        keyValidation();
        bearerParsing();
        transportCycling();
        System.out.println("Local API protocol regression tests passed");
    }

    // -------------------------------------------------------------- models

    private static void modelRouting() {
        List<Object> none = Collections.emptyList();

        check(ApiContract.MODEL_DEFAULT.equals(ModelCatalog.resolveRole(null, none)),
                "a missing model must resolve to the default route");
        check(ApiContract.MODEL_DEFAULT.equals(ModelCatalog.resolveRole("   ", none)),
                "a blank model must resolve to the default route");
        check(ApiContract.MODEL_EXPERT.equals(
                ModelCatalog.resolveRole("deepseek-reasoner", none)),
                "deepseek-reasoner must resolve to the expert route");
        check(ApiContract.MODEL_EXPERT.equals(ModelCatalog.resolveRole("deepseek-r1", none)),
                "deepseek-r1 must resolve to the expert route");
        check(ApiContract.MODEL_VISION.equals(
                ModelCatalog.resolveRole("deepseek-vision", none)),
                "deepseek-vision must resolve to the vision route");
        check(ApiContract.MODEL_DEFAULT.equals(
                ModelCatalog.resolveRole("totally-invented-model", none)),
                "an unknown model must fall back rather than fail");

        List<ModelCatalog.Entry> advertised = ModelCatalog.advertised(none);
        Set<String> ids = ModelCatalog.ids(advertised);
        check(ids.contains("deepseek-chat"), "the stock chat model must be advertised");
        check(ids.contains("deepseek-reasoner"), "the stock expert model must be advertised");
        check(ids.contains("deepseek-vision"), "the stock vision model must be advertised");
        check(!ids.contains("deepseek-r1"),
                "input-only aliases must not be advertised as models");

        check(!ModelCatalog.isAuxiliary("deepseek-chat"),
                "the chat model must not be treated as an auxiliary model");
        check(ModelCatalog.isAuxiliary("deepseek-aux"),
                "deepseek-aux must be treated as an auxiliary model");
        check(ModelCatalog.isAuxiliary("deepseek-aux-heartbeat"),
                "prefixed auxiliary models must be recognised");
    }

    // ----------------------------------------------------------------- keys

    private static void keyValidation() {
        check(!LocalApiConfig.validateKey(null).ok, "a null key must be rejected");
        check(!LocalApiConfig.validateKey("short").ok, "a short key must be rejected");
        check(!LocalApiConfig.validateKey("has space in it").ok,
                "a key containing whitespace must be rejected");
        StringBuilder tooLong = new StringBuilder();
        while (tooLong.length() <= LocalApiConfig.MAX_KEY_LENGTH) {
            tooLong.append('a');
        }
        check(!LocalApiConfig.validateKey(tooLong.toString()).ok,
                "an over-long key must be rejected");

        check(LocalApiConfig.validateKey("sk-localapi-0123456789").ok,
                "a normal key must be accepted");

        String generated = LocalApiConfig.generateKey();
        check(LocalApiConfig.validateKey(generated).ok,
                "a generated key must satisfy the validation rules");
        check(!generated.equals(LocalApiConfig.generateKey()),
                "two generated keys must differ");
    }

    // --------------------------------------------------------------- bearer

    private static void bearerParsing() {
        check("abc".equals(LocalApiServer.bearer("Bearer abc")),
                "a bearer token must be extracted");
        check("abc".equals(LocalApiServer.bearer("bearer abc")),
                "the bearer scheme must be matched case-insensitively");
        check("abc".equals(LocalApiServer.bearer("abc")),
                "a bare token must be accepted for clients that send no scheme");
        check(LocalApiServer.bearer(null) == null, "a missing header must yield no token");
        check(LocalApiServer.bearer("   ") == null, "a blank header must yield no token");

        check(LocalApiServer.constantTimeEquals("same", "same"), "equal keys must match");
        check(!LocalApiServer.constantTimeEquals("same", "sAme"), "differing keys must not match");
        check(!LocalApiServer.constantTimeEquals("same", "longe"),
                "keys of different length must not match");
        check(!LocalApiServer.constantTimeEquals(null, "same"), "a null expected key must fail");
        check(!LocalApiServer.constantTimeEquals("same", null), "a null presented key must fail");
    }

    // ----------------------------------------------------------- transports

    private static void transportCycling() {
        check(PublicTunnel.Transport.fromValue("auto") == PublicTunnel.Transport.AUTO,
                "auto must round-trip");
        check(PublicTunnel.Transport.fromValue("nonsense") == PublicTunnel.Transport.AUTO,
                "an unknown transport must fall back to auto");
        check(PublicTunnel.Transport.AUTO.next() == PublicTunnel.Transport.HTTP2,
                "auto must cycle to http2");
        check(PublicTunnel.Transport.HTTP2.next() == PublicTunnel.Transport.QUIC,
                "http2 must cycle to quic");
        check(PublicTunnel.Transport.QUIC.next() == PublicTunnel.Transport.AUTO,
                "quic must cycle back to auto");
    }

    // -------------------------------------------------------------- helpers

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private LocalApiProtocolRegressionTest() {
    }

    /** Guards against accidental removal of the unused import warning path. */
    static List<Object> emptyCustomModels() {
        return new ArrayList<Object>();
    }
}
