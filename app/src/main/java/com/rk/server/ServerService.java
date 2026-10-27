package com.rk.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerService extends Service {
    public static final String ACTION_START = "com.rk.server.START";
    public static final int PORT = 8080;
    public static volatile boolean isRunning = false;
    public static volatile String lastError = "";

    private static final int MAX_MESSAGES = 200;
    private static final int MAX_HEADER_BYTES = 16384;
    private static final int MAX_BODY_BYTES = 32768;
    private static final Object DATA_LOCK = new Object();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private volatile boolean stopRequested = false;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private JSONArray messages = new JSONArray();
    private File messagesFile;

    @Override public void onCreate() {
        super.onCreate();
        messagesFile = new File(getFilesDir(), "rk-messages.json");
        loadMessages();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(41, makeNotification());
        if (acceptThread == null || !acceptThread.isAlive()) startServer();
        return START_STICKY;
    }

    private Notification makeNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel("rk_server_status", "Status RK Server", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Menunjukkan server lokal sedang berjalan");
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, "rk_server_status");
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.rk_icon)
                .setContentTitle("RK Server berjalan")
                .setContentText("Uji chat lokal · port " + PORT)
                .setOngoing(true)
                .setContentIntent(pending)
                .setWhen(System.currentTimeMillis());
        return builder.build();
    }

    private void startServer() {
        stopRequested = false;
        lastError = "";
        workers = new ThreadPoolExecutor(1, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RKServer:Cpu");
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "RKServer:Wifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }

        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ServerSocket socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress("0.0.0.0", PORT), 24);
                    serverSocket = socket;
                    isRunning = true;
                    while (!stopRequested) {
                        try {
                            final Socket client = socket.accept();
                            client.setSoTimeout(6000);
                            try {
                                workers.execute(new Runnable() {
                                    @Override public void run() { handleClient(client); }
                                });
                            } catch (Exception rejected) {
                                closeQuietly(client);
                            }
                        } catch (SocketException closed) {
                            if (!stopRequested) lastError = "Koneksi server terputus";
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (!stopRequested) lastError = cleanError(e);
                } finally {
                    isRunning = false;
                    closeServerSocket();
                    releaseLocks();
                    ThreadPoolExecutor pool = workers;
                    if (pool != null) pool.shutdownNow();
                    if (!stopRequested && !TextUtils.isEmpty(lastError)) stopSelf();
                }
            }
        }, "RKServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private String cleanError(Exception e) {
        String message = e.getMessage();
        if (e instanceof java.net.BindException) return "Port " + PORT + " sedang dipakai aplikasi lain";
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void handleClient(Socket client) {
        try {
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            byte[] headerBytes = readHeaders(input);
            if (headerBytes == null) return;
            String rawHeaders = new String(headerBytes, LATIN1);
            String[] lines = rawHeaders.split("\\r\\n");
            if (lines.length == 0) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String[] request = lines[0].split(" ");
            if (request.length < 2) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String method = request[0].toUpperCase(Locale.US);
            String path = request[1];
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) path = path.substring(0, queryIndex);
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && "content-length".equals(lines[i].substring(0, colon).trim().toLowerCase(Locale.US))) {
                    try { contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim()); }
                    catch (NumberFormatException ignored) { contentLength = -1; }
                }
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(output, 204, "No Content", "text/plain; charset=utf-8", new byte[0]);
                return;
            }
            if (contentLength < 0) { sendJson(output, 400, "Bad Request", errorJson("Content-Length tidak valid")); return; }
            if (contentLength > MAX_BODY_BYTES) { sendJson(output, 413, "Payload Too Large", errorJson("Data terlalu besar")); return; }

            if ("GET".equals(method) && "/".equals(path)) {
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", readAsset("index.html"));
            } else if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("service", "RK Server");
                result.put("version", "1.0");
                result.put("messages", getMessageCount());
                sendJson(output, 200, "OK", result.toString());
            } else if ("GET".equals(method) && "/api/messages".equals(path)) {
                JSONObject result = new JSONObject();
                result.put("ok", true);
                JSONArray copy;
                synchronized (DATA_LOCK) { copy = new JSONArray(messages.toString()); }
                result.put("messages", copy);
                result.put("count", copy.length());
                sendJson(output, 200, "OK", result.toString());
            } else if ("POST".equals(method) && "/api/messages".equals(path)) {
                byte[] bodyBytes = readBody(input, contentLength);
                if (bodyBytes == null) { sendJson(output, 400, "Bad Request", errorJson("Isi pesan tidak lengkap")); return; }
                handlePost(output, new String(bodyBytes, UTF8));
            } else {
                sendJson(output, 404, "Not Found", errorJson("Alamat tidak ditemukan"));
            }
        } catch (Exception e) {
            try { sendJson(client.getOutputStream(), 500, "Internal Server Error", errorJson("Kesalahan server")); }
            catch (Exception ignored) { }
        } finally {
            closeQuietly(client);
        }
    }

    private void handlePost(OutputStream output, String body) throws IOException {
        try {
            JSONObject data = new JSONObject(body);
            String sender = data.optString("sender", "Pengguna").trim();
            String text = data.optString("text", "").trim();
            if (sender.length() == 0) sender = "Pengguna";
            if (sender.length() > 32) sender = sender.substring(0, 32);
            if (text.length() == 0) { sendJson(output, 400, "Bad Request", errorJson("Pesan masih kosong")); return; }
            if (text.length() > 2000) { sendJson(output, 413, "Payload Too Large", errorJson("Pesan maksimal 2000 karakter")); return; }
            JSONObject message = new JSONObject();
            message.put("id", System.currentTimeMillis());
            message.put("sender", sender);
            message.put("text", text);
            message.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            synchronized (DATA_LOCK) {
                JSONArray next = new JSONArray();
                int from = Math.max(0, messages.length() - MAX_MESSAGES + 1);
                for (int i = from; i < messages.length(); i++) next.put(messages.getJSONObject(i));
                next.put(message);
                messages = next;
                saveMessagesLocked();
            }
            JSONObject response = new JSONObject();
            response.put("ok", true);
            response.put("message", message);
            sendJson(output, 200, "OK", response.toString());
        } catch (JSONException e) {
            sendJson(output, 400, "Bad Request", errorJson("Format pesan tidak valid"));
        }
    }

    private JSONObject errorJson(String message) {
        JSONObject object = new JSONObject();
        try { object.put("ok", false); object.put("error", message); }
        catch (JSONException ignored) { }
        return object;
    }

    private int getMessageCount() { synchronized (DATA_LOCK) { return messages.length(); } }

    private byte[] readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int b = input.read();
            if (b < 0) return null;
            buffer.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') return buffer.toByteArray();
            else if (b == '\r') state = 1;
            else state = 0;
        }
        throw new IOException("Header terlalu besar");
    }

    private byte[] readBody(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int total = 0;
        while (total < length) {
            int read = input.read(result, total, length - total);
            if (read < 0) return null;
            total += read;
        }
        return result;
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream input = getAssets().open(name);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        input.close();
        return output.toByteArray();
    }

    private void sendJson(OutputStream output, int code, String reason, String json) throws IOException {
        sendResponse(output, code, reason, "application/json; charset=utf-8", json.getBytes(UTF8));
    }

    // Convenience overload for JSON objects, including errorJson(...).
    private void sendJson(OutputStream output, int code, String reason, JSONObject json) throws IOException {
        sendJson(output, code, reason, json == null ? "{}" : json.toString());
    }

    private void sendResponse(OutputStream output, int code, String reason, String type, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n\r\n";
        output.write(header.getBytes(LATIN1));
        output.write(body);
        output.flush();
    }

    private void loadMessages() {
        synchronized (DATA_LOCK) {
            if (!messagesFile.exists()) { messages = new JSONArray(); return; }
            try {
                FileInputStream input = new FileInputStream(messagesFile);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048]; int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                input.close();
                JSONArray loaded = new JSONArray(new String(output.toByteArray(), UTF8));
                JSONArray limited = new JSONArray();
                int from = Math.max(0, loaded.length() - MAX_MESSAGES);
                for (int i = from; i < loaded.length(); i++) limited.put(loaded.getJSONObject(i));
                messages = limited;
            } catch (Exception e) { messages = new JSONArray(); }
        }
    }

    private void saveMessagesLocked() {
        File temp = new File(getFilesDir(), "rk-messages.tmp");
        try {
            FileOutputStream output = new FileOutputStream(temp);
            output.write(messages.toString().getBytes(UTF8));
            output.flush();
            output.getFD().sync();
            output.close();
            if (messagesFile.exists() && !messagesFile.delete()) throw new IOException("Tidak bisa mengganti file pesan");
            if (!temp.renameTo(messagesFile)) throw new IOException("Tidak bisa menyimpan pesan");
        } catch (Exception e) {
            lastError = "Gagal menyimpan pesan";
            try { if (temp.exists()) temp.delete(); } catch (Exception ignored) { }
        }
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        wifiLock = null;
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        stopRequested = true;
        isRunning = false;
        closeServerSocket();
        releaseLocks();
        ThreadPoolExecutor pool = workers;
        if (pool != null) pool.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
