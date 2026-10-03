package pulse

/** Platform-owned device family used by the initial Pulse session registration. */
internal expect fun currentDeviceType(): String
