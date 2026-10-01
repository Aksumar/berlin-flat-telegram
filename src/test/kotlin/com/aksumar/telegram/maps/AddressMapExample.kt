package com.aksumar.telegram.maps

import com.aksumar.telegram.kafka.ListingContract
import com.aksumar.telegram.support.testMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Explicit live regression preview; excluded from ordinary tests. */
@Tag("map-examples")
class AddressMapExample {
    @Test
    fun charlottenburg() {
        val key = System.getenv("GEOAPIFY_API_KEY")?.takeIf { it.isNotBlank() }
            ?: Files.readAllLines(Path.of(".env")).first { it.startsWith("GEOAPIFY_API_KEY=") }
                .substringAfter('=').trim().trim('"', '\'')
        val item = ListingContract(testMapper).decode(
            javaClass.getResource("/degewo-charlottenburg-v2.json")!!.readText(),
        )
        val location = requireNotNull(GeoapifyGeocoder(
            GeoapifyClient(key), testMapper, "https://api.geoapify.com/v1/geocode/search",
        ).locate(item.address, System.nanoTime() + Duration.ofSeconds(30).toNanos()))
        assertEquals(13.3233867, location.longitude, 0.001)
        assertEquals(52.5214985, location.latitude, 0.001)
        assertFalse(location.approximate)
        val map = requireNotNull(GeoapifyMaps(key, testMapper).create(item))
        assertFalse(map.approximate)
        val output = Path.of("build/map-diagnostics/helmholtz-charlottenburg.png")
        Files.createDirectories(output.parent)
        Files.write(output, map.png)
    }
}
