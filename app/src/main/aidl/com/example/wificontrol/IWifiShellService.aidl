package com.example.wificontrol;

interface IWifiShellService {
    String runCommand(String command);
    int getUid();
    void destroy() = 16777114;
}
