package pulse

import platform.UIKit.UIDevice

internal actual fun currentDeviceType(): String = UIDevice.currentDevice.model
