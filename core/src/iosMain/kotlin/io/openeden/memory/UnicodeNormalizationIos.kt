package io.openeden.memory

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringGetBytes
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringNormalize
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringNormalizationFormC

@OptIn(ExperimentalForeignApi::class)
internal actual fun normalizeToNfc(value: String): String {
    if (value.isEmpty()) return value
    val inputBytes = value.encodeToByteArray()
    val source = inputBytes.usePinned { pinned ->
        requireNotNull(
            CFStringCreateWithBytes(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret<UByteVar>(),
                inputBytes.size.toLong(),
                kCFStringEncodingUTF8,
                false,
            ),
        )
    }
    val mutable = requireNotNull(
        CFStringCreateMutableCopy(kCFAllocatorDefault, 0, source),
    )
    CFStringNormalize(mutable, kCFStringNormalizationFormC)

    val outputSize = CFStringGetMaximumSizeForEncoding(
        CFStringGetLength(mutable),
        kCFStringEncodingUTF8,
    )
    val sentinel = 0xffu.toUByte()
    val outputBuffer = UByteArray(outputSize.toInt()) { sentinel }
    val written = outputBuffer.usePinned { pinned ->
        CFStringGetBytes(
            mutable,
            CFRangeMake(0, CFStringGetLength(mutable)),
            kCFStringEncodingUTF8,
            0u,
            false,
            pinned.addressOf(0),
            outputSize,
            null,
        )
    }
    check(written == CFStringGetLength(mutable))
    val usedLength = (0 until outputBuffer.size).firstOrNull { index -> outputBuffer[index] == sentinel }
        ?: outputBuffer.size
    return ByteArray(usedLength) { index -> outputBuffer[index].toByte() }.decodeToString()
}
