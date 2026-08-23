package io.openeden.memory

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringNormalize
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringNormalizationFormC

@OptIn(ExperimentalForeignApi::class)
internal actual fun normalizeToNfc(value: String): String {
    return memScoped {
        val source = requireNotNull(
            CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8),
        )
        val mutable = requireNotNull(
            CFStringCreateMutableCopy(kCFAllocatorDefault, 0, source),
        )
        CFStringNormalize(mutable, kCFStringNormalizationFormC)

        val outputSize = CFStringGetMaximumSizeForEncoding(
            CFStringGetLength(mutable),
            kCFStringEncodingUTF8,
        ) + 1
        val outputBuffer = allocArray<ByteVar>(outputSize.toInt())
        check(CFStringGetCString(mutable, outputBuffer, outputSize, kCFStringEncodingUTF8))
        outputBuffer.toKString()
    }
}
