package de.luhmer.owncloudnewsreader.ssl;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import de.luhmer.owncloud.accountimporter.helper.AccountImporter;
import de.luhmer.owncloud.accountimporter.helper.NextcloudRequest;
import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.database.model.Feed;
import de.luhmer.owncloudnewsreader.database.model.Folder;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
import de.luhmer.owncloudnewsreader.model.NextcloudNewsVersion;
import de.luhmer.owncloudnewsreader.model.UserInfo;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.BufferedSource;

/**
 * Created by david on 26.05.17.
 */

public class OkHttpSSLClient {

    public static OkHttpClient GetSslClient(final HttpUrl baseUrl, String username, String password, SharedPreferences sp, MemorizingTrustManager mtm) {
        // set location of the keystore
        MemorizingTrustManager.setKeyStoreFile("private", "sslkeys.bks");

        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        //interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        final OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.MINUTES)
                .addInterceptor(new AuthorizationInterceptor(baseUrl, Credentials.basic(username, password)))
                .addInterceptor(interceptor);

        // register MemorizingTrustManager for HTTPS
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            //sc.init(null, MemorizingTrustManager.getInstanceList(context), new java.security.SecureRandom());
            sc.init(null, new X509TrustManager[] { mtm }, new java.security.SecureRandom());
            // enables TLSv1.1/1.2 for Jelly Bean Devices
            TLSSocketFactory tlsSocketFactory = new TLSSocketFactory(sc);
            clientBuilder.sslSocketFactory(tlsSocketFactory, systemDefaultTrustManager());
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        clientBuilder.connectTimeout(10, TimeUnit.SECONDS);
        clientBuilder.readTimeout(120, TimeUnit.SECONDS);

        // disable hostname verification, when preference is set
        // (this still shows a certification dialog, which requires user interaction!)
        if(sp.getBoolean(SettingsActivity.CB_DISABLE_HOSTNAME_VERIFICATION_STRING, false))
            clientBuilder.hostnameVerifier(new HostnameVerifier() {
                @SuppressLint("BadHostnameVerifier")
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

        return clientBuilder.build();
    }

    public static <T extends Throwable> T HandleExceptions(T ex) {
        if(ex.getMessage().startsWith("Not a JSON Object: \"<!DOCTYPE\"")) {
            return (T) new JsonParseException("Invalid response from server. Please make sure, that the News App is installed and activated in your ownCloud/Nextcloud Webinterface. More information can be found here: https://github.com/nextcloud/news/blob/master/README.md#installationupdate");
        }
        //if(versionCode == -1 && exception_message.equals("Value <!DOCTYPE of type java.lang.String cannot be converted to JSONObject")) {
        //        ShowAlertDialog(getString(R.string.login_dialog_title_error), getString(R.string.login_dialog_text_not_compatible), getActivity());
        //}

        return ex;
    }

    private static X509TrustManager systemDefaultTrustManager() {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new IllegalStateException("Unexpected default trust managers:"
                        + Arrays.toString(trustManagers));
            }
            return (X509TrustManager) trustManagers[0];
        } catch (GeneralSecurityException e) {
            throw new AssertionError(); // The system has no TLS. Just give up.
        }
    }

    private static class AuthorizationInterceptor implements Interceptor {

        private final String mCredentials;
        private final HttpUrl mHostUrl;

        AuthorizationInterceptor(HttpUrl hostUrl, String credentials) {
            this.mHostUrl = hostUrl;
            this.mCredentials = credentials;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            // only add Authorization header for urls on the configured owncloud/nextcloud host
            if (mHostUrl.url().getHost().equals(request.url().host()))
                request = request.newBuilder()
                        .addHeader("Authorization", mCredentials)
                        .build();
            return chain.proceed(request);
        }
    }
}