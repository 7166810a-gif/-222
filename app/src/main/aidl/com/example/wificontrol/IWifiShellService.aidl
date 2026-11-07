package com.example.wificontrol;

interface IWifiShellService {
    String setWifiEnabled(boolean enabled) = 1;
    String connectNetwork(String ssid, String securityType, String password) = 2;
    int getUid() = 3;
    void destroy() = 16777114;
}
