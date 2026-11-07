package com.example.wificontrol;

interface IWifiShellService {
    String setWifiEnabled(boolean enabled);
    String connectNetwork(String ssid, String securityType, String password);
    int getUid();
    void destroy() = 16777114;
}
