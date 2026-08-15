package com.valen.assistant;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SslHelper {

    private static SSLSocketFactory trustedFactory;

    public static void trustSelfSignedCerts() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            trustedFactory = sslContext.getSocketFactory();

            HttpsURLConnection.setDefaultSSLSocketFactory(trustedFactory);
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        } catch (Exception e) {
            throw new RuntimeException("Failed to configure SSL trust", e);
        }
    }

    public static SSLSocketFactory getTrustedFactory() {
        if (trustedFactory == null) {
            trustSelfSignedCerts();
        }
        return trustedFactory;
    }
}
