package com.tapdeuce.game;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Connects two phones directly with Google Nearby Connections, which uses Bluetooth and Wi-Fi
 * without needing internet. The game talks to this class as window.AndroidRoom and receives the
 * same messages the Wi-Fi server sends: room, joined, err, left, and anything the other phone sent.
 */
public class NearbyRoom {
    private static final String SERVICE_ID = "com.tapdeuce.game.room";
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;
    private static final long SEARCH_TIMEOUT_MS = 20000;
    private static final int PERMISSION_REQUEST = 41;

    private final Activity activity;
    private final WebView web;
    private final ConnectionsClient client;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Runnable afterPermissions;
    private Runnable searchTimeout;
    private String wantedCode;
    private String peer;
    private boolean hosting;

    NearbyRoom(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.client = Nearby.getConnectionsClient(activity);
    }

    /* ---------- called from the game ---------- */

    @JavascriptInterface
    public void createRoom() {
        main.post(() -> withPermissions(this::startHosting));
    }

    @JavascriptInterface
    public void joinRoom(String code) {
        main.post(() -> {
            wantedCode = code;
            withPermissions(this::startSearching);
        });
    }

    @JavascriptInterface
    public void send(String text) {
        main.post(() -> {
            if (peer != null) client.sendPayload(peer, Payload.fromBytes(text.getBytes(StandardCharsets.UTF_8)));
        });
    }

    @JavascriptInterface
    public void leave() {
        main.post(this::stopEverything);
    }

    @JavascriptInterface
    public void exitApp() {
        main.post(activity::finishAndRemoveTask);
    }

    /* ---------- creating and joining ---------- */

    private void startHosting() {
        stopEverything();
        hosting = true;
        String code = String.valueOf(1000 + new Random().nextInt(9000));
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        client.startAdvertising(code, SERVICE_ID, connections, options)
                .addOnSuccessListener(unused -> toGame(message("room", code)))
                .addOnFailureListener(e -> toGame(message("err", "Could not create a room. Check that Bluetooth and Wi-Fi are on, then try again.")));
    }

    private void startSearching() {
        stopEverything();
        hosting = false;
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        client.startDiscovery(SERVICE_ID, discovery, options)
                .addOnFailureListener(e -> toGame(message("err", "Could not search for rooms. Check that Bluetooth and Wi-Fi are on, then try again.")));
        searchTimeout = () -> {
            client.stopDiscovery();
            toGame(message("err", "No room found with that code. Check the code and keep the phones close."));
        };
        main.postDelayed(searchTimeout, SEARCH_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (searchTimeout != null) main.removeCallbacks(searchTimeout);
        searchTimeout = null;
    }

    private void stopEverything() {
        cancelTimeout();
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        peer = null;
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            if (!info.getEndpointName().equals(wantedCode)) return;
            client.stopDiscovery();
            client.requestConnection("guest", endpointId, connections)
                    .addOnFailureListener(e -> {
                        cancelTimeout();
                        toGame(message("err", "Could not connect to that room. Try again."));
                    });
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
        }
    };

    private final ConnectionLifecycleCallback connections = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            // The room code already identified the right phone, so both sides accept.
            client.acceptConnection(endpointId, payloads);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                peer = endpointId;
                cancelTimeout();
                if (hosting) client.stopAdvertising();
                else toGame("{\"joined\":1}");
            } else if (!hosting) {
                cancelTimeout();
                toGame(message("err", "Could not connect to that room. Try again."));
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            peer = null;
            toGame("{\"left\":1}");
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES && payload.asBytes() != null) {
                toGame(new String(payload.asBytes(), StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
        }
    };

    /* ---------- permissions ---------- */

    private void withPermissions(Runnable action) {
        List<String> missing = new ArrayList<>();
        for (String p : requiredPermissions()) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            action.run();
        } else {
            afterPermissions = action;
            activity.requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    void onPermissions(int[] results) {
        Runnable action = afterPermissions;
        afterPermissions = null;
        if (action == null) return;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                toGame(message("err", "Local rooms need the Nearby devices permission. Allow it and try again."));
                return;
            }
        }
        if (results.length > 0) action.run();
    }

    /* ---------- messages to the game ---------- */

    private static String message(String key, String value) {
        return "{" + JSONObject.quote(key) + ":" + JSONObject.quote(value) + "}";
    }

    private void toGame(String json) {
        main.post(() -> web.evaluateJavascript(
                "window.tapDeuceNative && window.tapDeuceNative(" + JSONObject.quote(json) + ")", null));
    }
}
