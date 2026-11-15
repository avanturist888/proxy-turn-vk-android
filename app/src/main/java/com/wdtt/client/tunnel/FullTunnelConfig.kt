package com.wdtt.client

/**
 * Always use Android's VPN backend (never a local SOCKS-only connection).
 * Explicit direct exclusions are a separate, opt-in setting.
 */
const val FORCE_FULL_TUNNEL = true
