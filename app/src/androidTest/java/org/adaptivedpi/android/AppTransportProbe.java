package org.adaptivedpi.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Работает с UID пакета тестов, отдельно от процесса инструментализации VPN. */
public final class AppTransportProbe extends BroadcastReceiver {
    private static final int TIMEOUT = 4_000;

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        ResultReceiver receiver = intent.getParcelableExtra("reply");
        if (receiver == null) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            Bundle result = new Bundle();
            try {
                inspectNetwork(context, result);
                if (intent.hasExtra("target")) {
                    InetAddress ip = InetAddresses.parseNumericAddress(intent.getStringExtra("target"));
                    require(ip instanceof Inet4Address && ip.isSiteLocalAddress(), "Недопустимый адрес стенда");
                    exchange(ip, intent.getStringExtra("certificate"), result);
                }
                receiver.send(0, result);
            } catch (Exception error) {
                result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
                receiver.send(1, result);
            } finally {
                pending.finish();
            }
        }, "Проверка транспорта приложения").start();
    }

    private static void inspectNetwork(Context context, Bundle result) {
        result.putInt("uid", Process.myUid());
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        if (network == null) return;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        if (capabilities == null) return;
        result.putBoolean("network", true);
        result.putBoolean("vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        LinkProperties link = manager.getLinkProperties(network);
        if (link == null) return;
        result.putBoolean("ipv4Default", link.getRoutes().stream().anyMatch(route ->
                route.isDefaultRoute() && route.getDestination().getAddress() instanceof Inet4Address));
        result.putBoolean("ipv6Default", link.getRoutes().stream().anyMatch(route ->
                route.isDefaultRoute() && !(route.getDestination().getAddress() instanceof Inet4Address)));
        result.putBoolean("ipv4Dns", !link.getDnsServers().isEmpty() && link.getDnsServers().stream().allMatch(a -> a instanceof Inet4Address));
    }

    private static void exchange(InetAddress ip, String certificate, Bundle result) throws Exception {
        byte[] tcp = {7, 6, 5, 4, 3};
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.setSoTimeout(TIMEOUT);
            socket.connect(new InetSocketAddress(ip, 39052), TIMEOUT);
            socket.getOutputStream().write(tcp);
            byte[] reply = new byte[tcp.length];
            new DataInputStream(socket.getInputStream()).readFully(reply);
            require(Arrays.equals(tcp, reply), "Неверный TCP-ответ");
        }
        result.putBoolean("tcp", true);
        byte[] udp = {3, 4, 5, 6, 7};
        require(Arrays.equals(udp, datagram(ip, 39052, udp)), "Неверный UDP-ответ");
        result.putBoolean("udp", true);

        byte[] large = new byte[1400];
        for (int i = 0; i < large.length; i++) large[i] = (byte) i;
        require(Arrays.equals(large, datagram(ip, 39052, "adaptive-udp-large".getBytes(StandardCharsets.US_ASCII))),
                "Неверный фрагментированный UDP-ответ");
        result.putBoolean("udpLarge", true);

        byte[] query = {0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 4, 't', 'e', 's', 't', 0, 0, 1, 0, 1};
        byte[] dns = Arrays.copyOf(query, query.length + 16);
        dns[2] = (byte) 0x81;
        dns[3] = (byte) 0x80;
        dns[7] = 1;
        byte[] answer = {(byte) 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, (byte) 192, 0, 2, 45};
        System.arraycopy(answer, 0, dns, query.length, answer.length);
        require(Arrays.equals(dns, datagram(ip, 39053, query)), "Неверный DNS A-ответ");
        result.putBoolean("dns", true);

        https(ip, certificate);
        result.putBoolean("https", true);
    }

    private static byte[] datagram(InetAddress ip, int port, byte[] payload) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT);
            socket.connect(ip, port);
            socket.send(new DatagramPacket(payload, payload.length));
            DatagramPacket reply = new DatagramPacket(new byte[1500], 1500);
            socket.receive(reply);
            return Arrays.copyOf(reply.getData(), reply.getLength());
        }
    }

    private static void https(InetAddress ip, String encodedCertificate) throws Exception {
        Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(Base64.decode(encodedCertificate, Base64.DEFAULT)));
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        trust.setCertificateEntry("fixture", cert);
        TrustManagerFactory manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        manager.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, manager.getTrustManagers(), null);
        URL url = new URL("https", ip.getHostAddress(), 39054, "/probe");
        HttpsURLConnection client = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            client.setSSLSocketFactory(tls.getSocketFactory());
            client.setConnectTimeout(TIMEOUT);
            client.setReadTimeout(TIMEOUT);
            client.setInstanceFollowRedirects(false);
            client.setUseCaches(false);
            require(client.getResponseCode() == 200, "Неверный статус HTTPS стенда");
            byte[] expected = "adaptive-tun-https\n".getBytes(StandardCharsets.UTF_8);
            try (DataInputStream body = new DataInputStream(client.getInputStream())) {
                byte[] response = new byte[expected.length];
                body.readFully(response);
                require(Arrays.equals(expected, response) && body.read() == -1, "Неверное содержимое HTTPS стенда");
            }
        } finally {
            client.disconnect();
        }
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
