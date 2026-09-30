package io.openeden.server.maintenance

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path

/** Root-relative NT opens prevent junction traversal and path replacement between checks and I/O. */
internal object WindowsNativeExportFiles {
    private interface Nt : StdCallLibrary {
        fun NtCreateFile(result: PointerByReference, access: Int, attributes: Pointer, status: Pointer,
            allocation: Pointer?, flags: Int, sharing: Int, disposition: Int, options: Int,
            extendedAttributes: Pointer?, extendedLength: Int): Int
        fun NtSetInformationFile(handle: WinNT.HANDLE, status: Pointer, information: Pointer,
            length: Int, informationClass: Int): Int
    }

    private val nt: Nt by lazy { Native.load("ntdll", Nt::class.java) }
    private val kernel get() = Kernel32.INSTANCE
    private val pointerSize get() = Native.POINTER_SIZE
    private const val READ_WRITE = -1073741824 // GENERIC_READ | GENERIC_WRITE
    private const val DELETE = 0x10000

    class Handle internal constructor(val value: WinNT.HANDLE) : AutoCloseable {
        override fun close() { check(kernel.CloseHandle(value)) { "Could not close native export handle" } }
    }

    fun openVolume(path: Path): Handle {
        val value = kernel.CreateFile(path.toString(), 0x80, 3, null, WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null)
        check(value != WinBase.INVALID_HANDLE_VALUE) { "Could not pin export volume (win32=${Native.getLastError()})" }
        return Handle(value).also { handle ->
            try { identity(handle) } catch (failure: Throwable) { handle.close(); throw failure }
        }
    }

    fun child(parent: Handle, name: String, directory: Boolean, create: Boolean = false,
        writable: Boolean = false, deletable: Boolean = false, openOrCreateDirectory: Boolean = false): Handle {
        require(!openOrCreateDirectory || (directory && !create))
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it in "\\/:\u0000" }) {
            "Export child must be a single ordinary file name"
        }
        val bytes = name.toByteArray(Charsets.UTF_16LE)
        require(bytes.size <= 32764)
        Memory((bytes.size + 2).toLong()).use { text ->
            text.clear(); text.write(0, bytes, 0, bytes.size)
            Memory((pointerSize * 2).toLong()).use { unicode ->
                unicode.clear(); unicode.setShort(0, bytes.size.toShort()); unicode.setShort(2, (bytes.size + 2).toShort())
                unicode.setPointer(pointerSize.toLong(), text)
                Memory((pointerSize * 6).toLong()).use { attributes ->
                    attributes.clear(); attributes.setInt(0, attributes.size().toInt())
                    attributes.setPointer(pointerSize.toLong(), parent.value.pointer)
                    attributes.setPointer((pointerSize * 2).toLong(), unicode)
                    attributes.setInt((pointerSize * 3).toLong(), 0x1040) // CASE_INSENSITIVE | DONT_REPARSE
                    Memory((pointerSize * 2).toLong()).use { status ->
                        val result = PointerByReference()
                        val access = (if (writable) READ_WRITE else WinNT.GENERIC_READ) or
                            0x100000 or (if (deletable) DELETE else 0)
                        requireSuccess(nt.NtCreateFile(result, access, attributes, status, null, 0,
                            if (directory && writable && !deletable) 3 else 1,
                            if (create) 2 else if (openOrCreateDirectory) 3 else 1,
                            (if (directory) 1 else 0x40) or 0x20 or 0x200000, null, 0), "open child")
                        return Handle(WinNT.HANDLE(result.value)).also { handle ->
                            try { identity(handle) } catch (failure: Throwable) { handle.close(); throw failure }
                        }
                    }
                }
            }
        }
    }

    fun identity(handle: Handle): String {
        Memory(8).use { attributes ->
            check(kernel.GetFileInformationByHandleEx(handle.value, 9, attributes, DWORD(8))) { "Cannot inspect export reparse attributes" }
            check(attributes.getInt(0) and 0x400 == 0) { "Reparse points are forbidden in export paths" }
        }
        return Memory(24).use { id ->
            check(kernel.GetFileInformationByHandleEx(handle.value, 18, id, DWORD(24))) { "Filesystem lacks stable export file identities" }
            id.getByteArray(0, 24).joinToString("") { "%02x".format(it) }
        }
    }

    fun write(parent: Handle, name: String, bytes: ByteArray) {
        child(parent, name, directory = false, create = true, writable = true).use { file ->
            var offset = 0
            while (offset < bytes.size) {
                val chunk = bytes.copyOfRange(offset, minOf(offset + 65536, bytes.size))
                val written = IntByReference()
                check(kernel.WriteFile(file.value, chunk, chunk.size, written, null) && written.value > 0) {
                    "Could not write export payload (win32=${Native.getLastError()})"
                }
                offset += written.value
            }
            flush(file)
        }
    }

    fun verifyAndDeleteMarker(parent: Handle, name: String, expected: String) {
        child(parent, name, directory = false, deletable = true).use { file ->
            val buffer = ByteArray(128)
            val read = IntByReference()
            check(kernel.ReadFile(file.value, buffer, buffer.size, read, null)) { "Could not read export identity marker" }
            check(read.value < buffer.size && buffer.copyOf(read.value).toString(Charsets.UTF_8) == expected) {
                "Export identity marker changed"
            }
            Memory(1).use { info ->
                info.setByte(0, 1)
                setInformation(file, info, 13)
            }
        }
    }

    fun rename(handle: Handle, parent: Handle, name: String) {
        val bytes = name.toByteArray(Charsets.UTF_16LE)
        Memory((pointerSize * 2 + 4 + bytes.size).toLong()).use { info ->
            info.clear() // ReplaceIfExists = FALSE, including a target created after prepare.
            info.setPointer(pointerSize.toLong(), parent.value.pointer)
            info.setInt((pointerSize * 2).toLong(), bytes.size)
            info.write((pointerSize * 2 + 4).toLong(), bytes, 0, bytes.size)
            setInformation(handle, info, 10)
        }
    }

    private fun setInformation(handle: Handle, info: Memory, informationClass: Int) {
        Memory((pointerSize * 2).toLong()).use { status ->
            requireSuccess(nt.NtSetInformationFile(handle.value, status, info, info.size().toInt(), informationClass), "publish")
        }
    }

    fun flush(handle: Handle) {
        check(kernel.FlushFileBuffers(handle.value)) { "Could not flush export metadata (win32=${Native.getLastError()})" }
    }

    private fun requireSuccess(status: Int, operation: String) {
        check(status >= 0) { "Native export $operation failed (NTSTATUS=0x${status.toUInt().toString(16)})" }
    }
}
