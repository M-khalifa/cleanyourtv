package io.github.mkhalifa.miracast.helper;

/** Events the shell-uid helper pushes to the app. */
interface IHelperCallback {
    oneway void onStatus(String status);
    oneway void onSourceJoined(String ip, int port);
}
