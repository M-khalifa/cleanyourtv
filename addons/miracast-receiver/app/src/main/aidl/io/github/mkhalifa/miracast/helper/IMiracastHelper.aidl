package io.github.mkhalifa.miracast.helper;

import io.github.mkhalifa.miracast.helper.IHelperCallback;

/** Shell-uid Wi-Fi Direct helper run by Shizuku; destroy() uses the transaction code Shizuku sends on removal. */
interface IMiracastHelper {
    void destroy() = 16777114;
    String status() = 1;
    void setEnabled(boolean enabled) = 2;
    void setName(String name) = 3;
    void setMice(String hostName, int capability) = 4;
    void register(IHelperCallback callback) = 5;
    void removeGroup() = 6;
    void launchReceiver(String source, int port, String via) = 7;
    int version() = 8;
    void setGroupMode(boolean on) = 9;
    void setSessionActive(boolean active) = 10;
}
