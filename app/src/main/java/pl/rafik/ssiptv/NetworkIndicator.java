package pl.rafik.ssiptv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/** Transport urządzenia i sygnał Wi-Fi, bez zgadywania przepustowości IPTV. */
public final class NetworkIndicator {
    public final boolean connected;
    public final int icon;
    public final String label;

    private NetworkIndicator(boolean connected, int icon, String label) {
        this.connected = connected;
        this.icon = icon;
        this.label = label;
    }

    @SuppressWarnings("deprecation")
    public static NetworkIndicator read(Context context) {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = manager == null || manager.getActiveNetwork() == null ?
                null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (capabilities == null)
            return new NetworkIndicator(false, R.drawable.ic_network_off, "Brak sieci");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            return new NetworkIndicator(true, R.drawable.ic_network_ethernet, "Ethernet");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            int rssi = -127;
            if (Build.VERSION.SDK_INT >= 31 && capabilities.getTransportInfo() instanceof WifiInfo) {
                rssi = ((WifiInfo) capabilities.getTransportInfo()).getRssi();
            }
            if (rssi <= -127 || rssi >= 0) {
                WifiManager wifi = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi != null && wifi.getConnectionInfo() != null)
                    rssi = wifi.getConnectionInfo().getRssi();
            }
            if (rssi <= -127 || rssi >= 0)
                return new NetworkIndicator(true, R.drawable.ic_wifi_unknown, "Wi-Fi · sygnał nieznany");
            int level = WifiManager.calculateSignalLevel(rssi, 5);
            int icon = level >= 4 ? R.drawable.ic_wifi_4 :
                    level == 3 ? R.drawable.ic_wifi_3 :
                    level == 2 ? R.drawable.ic_wifi_2 :
                    level == 1 ? R.drawable.ic_wifi_1 : R.drawable.ic_wifi_0;
            return new NetworkIndicator(true, icon, "Wi-Fi " + Math.round(level * 25f) + "%");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            return new NetworkIndicator(true, R.drawable.ic_network_mobile, "Sieć mobilna");
        return new NetworkIndicator(true, R.drawable.ic_network_ethernet, "Połączenie sieciowe");
    }
}
