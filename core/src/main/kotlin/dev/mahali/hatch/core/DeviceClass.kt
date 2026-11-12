package dev.mahali.hatch.core

public enum class DeviceClass {
    Low, Normal;

    public companion object {
        /**
         * Low on a low-RAM device, a heap under 256 MB, or four cores or fewer.
         * Under, not at: most current flagships report exactly 256.
         */
        public fun of(lowRam: Boolean, memoryClassMb: Int, cores: Int): DeviceClass =
            if (lowRam || memoryClassMb < 256 || cores <= 4) Low else Normal
    }
}
