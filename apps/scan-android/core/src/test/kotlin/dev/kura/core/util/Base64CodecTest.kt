package dev.kura.core.util

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base64CodecTest {
    @Test
    fun `identique a java util Base64 sur des donnees aleatoires`() {
        val rnd = Random(42)
        repeat(300) {
            val data = rnd.nextBytes(rnd.nextInt(0, 70))
            assertEquals(Base64.getEncoder().encodeToString(data), Base64Codec.encode(data))
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(data), Base64Codec.encodeUrl(data))
            assertContentEquals(data, Base64Codec.decode(Base64.getEncoder().encodeToString(data)))
            assertContentEquals(data, Base64Codec.decode(Base64.getEncoder().withoutPadding().encodeToString(data)))
            assertContentEquals(data, Base64Codec.decodeUrl(Base64.getUrlEncoder().encodeToString(data)))
        }
    }

    @Test
    fun `entrees invalides`() {
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("ab!d") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("abcde") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeUrl("ab+/") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("ab===") }
    }
}
