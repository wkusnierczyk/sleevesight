package sleevesight.checks

/**
 * Android lint's security checks, raised to errors wherever lint runs (#217; listed on the site's
 * security page, `docs/security/`), so a change that weakens TLS, exports a component or launches an
 * unchecked intent fails the build. Both the app (`sleevesight.android-app`) and every JVM module
 * (`sleevesight.kotlin-jvm`) apply them: `:net` holds the HTTP client and its TLS setup, and the
 * app's lint does not look into the modules it depends on.
 */
object SecurityLint {
    val checks: Set<String> =
        setOf(
            "AddJavascriptInterface",
            "AllowAllHostnameVerifier",
            "BadHostnameVerifier",
            "ExportedContentProvider",
            "ExportedReceiver",
            "ExportedService",
            "GetInstance",
            "InsecureBaseConfiguration",
            "SecureRandom",
            "SetJavaScriptEnabled",
            "SSLCertificateSocketFactoryCreateSocket",
            "SSLCertificateSocketFactoryGetInsecure",
            "TrustAllX509TrustManager",
            "UnsafeImplicitIntentLaunch",
            "UnsafeIntentLaunch",
            "WorldReadableFiles",
            "WorldWriteableFiles",
        )
}
