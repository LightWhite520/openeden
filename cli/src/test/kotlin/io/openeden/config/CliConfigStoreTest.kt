package io.openeden.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliConfigStoreTest {
    @Test
    fun `creates config with server url and system user defaults`() {
        val directory = Files.createTempDirectory("openeden-cli-config")
        val path = directory.resolve("config.json")
        val store = CliConfigStore(path = path, systemUserName = "lightwhite")

        val config = store.loadOrCreate()

        assertEquals("http://127.0.0.1:8080", config.serverUrl)
        assertEquals("lightwhite", config.userId)
        assertTrue(Files.exists(path))
        val json = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("http://127.0.0.1:8080", json["serverUrl"]?.toString()?.trim('"'))
        assertEquals("lightwhite", json["userId"]?.toString()?.trim('"'))
    }

    @Test
    fun `loads explicit client config without environment lookup`() {
        val path = Files.createTempFile("openeden-cli-config", ".json")
        Files.writeString(path, """{"serverUrl":"http://remote:8080","userId":"owner"}""")
        val store = CliConfigStore(path = path, systemUserName = "ignored")

        val config = store.loadOrCreate()

        assertEquals("http://remote:8080", config.serverUrl)
        assertEquals("owner", config.userId)
    }
}
