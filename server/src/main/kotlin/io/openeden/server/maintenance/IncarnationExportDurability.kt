package io.openeden.server.maintenance

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface IncarnationExportDurability {
    fun forceWrite(path: Path, bytes: ByteArray)
    fun forceDirectory(path: Path)
    fun atomicMove(source: Path, target: Path)
}

object NioIncarnationExportDurability : IncarnationExportDurability {
    override fun forceWrite(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override fun forceDirectory(path: Path) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            forceWindowsDirectory(path)
        } else {
            FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        try {
            java.nio.file.Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Export target does not support an atomic staging move", failure)
        }
    }

    private fun forceWindowsDirectory(path: Path) {
        val handle = Kernel32.INSTANCE.CreateFile(
            path.toString(),
            WinNT.GENERIC_WRITE,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_BACKUP_SEMANTICS,
            null,
        )
        check(handle != WinBase.INVALID_HANDLE_VALUE) {
            "Could not open directory for durability flush: $path (win32=${Native.getLastError()})"
        }
        try {
            check(Kernel32.INSTANCE.FlushFileBuffers(handle)) {
                "Could not flush directory metadata: $path (win32=${Native.getLastError()})"
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }
}
