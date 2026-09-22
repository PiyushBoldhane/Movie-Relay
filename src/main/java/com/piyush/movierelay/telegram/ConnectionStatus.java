package com.piyush.movierelay.telegram;

/**
 * Whether this app currently has a working connection to Telegram. state is a short machine
 * label (e.g. "ready", "waiting_for_network", "connecting") for the frontend to show a more
 * specific message than a plain yes/no.
 */
public record ConnectionStatus(boolean connected, String state) {
}
