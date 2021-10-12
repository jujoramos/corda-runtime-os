@file:JvmName("MemberInfoUtils")
package net.corda.membership.impl

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.util.SortedMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

interface KeyEncodingService : PublicKeyDecoder {
    fun decodePublicKey(encodedKey: ByteArray): PublicKey
    fun encodeAsByteArray(publicKey: PublicKey): ByteArray
    fun encodeAsString(publicKey: PublicKey): String
    fun toSupportedPrivateKey(key: PrivateKey): PrivateKey
    fun toSupportedPublicKey(key: PublicKey): PublicKey
}

/////////////////////////////////////////// API - base

interface PublicKeyDecoder {
    fun decodePublicKey(encodedKey: String): PublicKey
}

interface StringValueConverter {
    val publicKeyDecoder: PublicKeyDecoder
    fun <T> convert(strValue: String?, clazz: Class<out T>): T?
}

interface KeyValueStore {
    operator fun get(key: String): String?
    val keys: Set<String>
    val entries: Set<Map.Entry<String, String?>>
    fun <T> parse(key: String, clazz: Class<out T>): T
    fun <T> parseOrNull(key: String, clazz: Class<out T>): T?
    fun <T> parseList(itemKeyPrefix: String, clazz: Class<out T>): List<T>
    fun <T> parseList(
        itemKeyPrefix: String, clazz: Class<out T>,
        itemFactory: (converter: StringValueConverter, keys: List<Pair<String, String>>) -> T
    ): List<T>
    fun <T> parsePrimitiveList(
        itemKeyPrefix: String, clazz: Class<out T>,
        itemFactory: (converter: StringValueConverter, value: String) -> T
    ): List<T>
}

inline fun <reified T> KeyValueStore.parse(key: String): T {
    return parse(key, T::class.java)
}

inline fun <reified T> KeyValueStore.parseOrNull(key: String): T? {
    return parseOrNull(key, T::class.java)
}

inline fun <reified T> KeyValueStore.parseList(itemKeyPrefix: String): List<T> {
    return parseList(itemKeyPrefix, T::class.java)
}

inline fun <reified T> KeyValueStore.parseList(
    itemKeyPrefix: String,
    noinline itemFactory: (converter: StringValueConverter, keys: List<Pair<String, String>>) -> T
): List<T> {
    return parseList(itemKeyPrefix, T::class.java, itemFactory)
}

inline fun <reified T> KeyValueStore.parsePrimitiveList(
    itemKeyPrefix: String,
    noinline itemFactory: (converter: StringValueConverter, value: String) -> T
): List<T> {
    return parsePrimitiveList(itemKeyPrefix, T::class.java, itemFactory)
}

/////////////////////////////////////////// API - membership

interface MemberInfo {
    val memberCtx: KeyValueStore
    val mgmCtx: KeyValueStore
    val groupId: String
    val name: String
    val key: PublicKey
    val allKeys: List<PublicKey>
}

///////////////////////////////////////////////// IMPL - base

open class StringValueConverterImpl(
    override val publicKeyDecoder: PublicKeyDecoder
) : StringValueConverter {
    @Suppress("UNCHECKED_CAST")
    override fun <T> convert(strValue: String?, clazz: Class<out T>): T? {
        return if(strValue == null) {
            return null
        } else {
            when (clazz.kotlin) {
                Int::class -> strValue.toInt() as T
                Long::class -> strValue.toLong() as T
                Short::class -> strValue.toShort() as T
                Float::class -> strValue.toFloat() as T
                Double::class -> strValue.toDouble() as T
                String::class -> strValue as T
                Instant::class -> Instant.parse(strValue) as T
                PublicKey::class -> publicKeyDecoder.decodePublicKey(strValue) as T
                else -> throw IllegalStateException("Unknown '${clazz.name}' Type")
            }
        }
    }
}

open class KeyValueStoreImpl(
    private val map: SortedMap<String, String?>,
    private val converter: StringValueConverter
) : KeyValueStore {
    private class MaterialisedValue(val value: Any?)

    private val materialised = ConcurrentHashMap<String, MaterialisedValue>()

    override operator fun get(key: String): String? = map[key]

    @Transient
    override val keys: Set<String> = map.keys

    @Transient
    override val entries: Set<Map.Entry<String, String?>> = map.entries

    @Suppress("UNCHECKED_CAST")
    override fun <T> parse(key: String, clazz: Class<out T>): T {
        val tmp = materialised[key]
        if(tmp != null) {
            return tmp.value as? T
                ?: throw IllegalStateException("The value for the key '$key' is null.")
        }
        val value = converter.convert(get(key), clazz)
            ?: throw IllegalStateException("The '$key' was not found or the value is null.")
        materialised[key] = MaterialisedValue(value)
        return value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> parseOrNull(key: String, clazz: Class<out T>): T? {
        val tmp = materialised[key]
        if(tmp != null) {
            return tmp.value as T?
        }
        val value = converter.convert(get(key), clazz)
        materialised[key] = MaterialisedValue(value)
        return value
    }

    override fun <T> parseList(itemKeyPrefix: String, clazz: Class<out T>): List<T> {
        return parseList(itemKeyPrefix, clazz) { c, l ->
            defaultItemFactory(clazz, c, l)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> parseList(
        itemKeyPrefix: String,
        clazz: Class<out T>,
        itemFactory: (converter: StringValueConverter, keys: List<Pair<String, String>>) -> T
    ): List<T> {
        val searchPrefix = normaliseSearchKeyPrefix(itemKeyPrefix)
        val tmp = materialised[searchPrefix]
        if(tmp != null) {
            return tmp.value as? List<T>
                ?: throw IllegalStateException("The value for the key prefix '$searchPrefix' is null.")
        }
        val parsed = mutableListOf<T>()
        val currentGroup = mutableListOf<Pair<String, String>>()
        var lastIndex: String? = null
        entries.forEach {
            if (it.key.startsWith(searchPrefix)) {
                val itemIndex = getIndex(it.key)
                if (itemIndex != lastIndex) {
                    lastIndex = itemIndex
                    if (currentGroup.isNotEmpty()) {
                        parsed.add(itemFactory(converter, currentGroup))
                        currentGroup.clear()
                    }
                }
                if(it.value != null) {
                    currentGroup.add(Pair(it.key, it.value!!))
                }
            } else if (lastIndex != null) {
                return@forEach
            }
        }
        if (currentGroup.isNotEmpty()) {
            parsed.add(itemFactory(converter, currentGroup))
            currentGroup.clear()
        }
        materialised[searchPrefix] = MaterialisedValue(parsed)
        return parsed
    }

    override fun <T> parsePrimitiveList(
        itemKeyPrefix: String,
        clazz: Class<out T>,
        itemFactory: (converter: StringValueConverter, value: String) -> T
    ): List<T> {
        return parseList(itemKeyPrefix, clazz) { c, l ->
            if(l.size != 1) {
                throw IllegalStateException("Expected single item only.")
            }
            itemFactory(c, l[0].second)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> defaultItemFactory(
        clazz: Class<out T>,
        c: StringValueConverter,
        l: List<Pair<String, String>>
    ): T {
        val ctor2 = clazz.constructors.firstOrNull { ctor ->
            ctor.parameterCount == 2 &&
                    ctor.parameterTypes[0] == StringValueConverter::class.java &&
                    ctor.parameterTypes[1] == List::class.java
        }
        return if (ctor2 != null) {
            ctor2.newInstance(c, l) as T
        } else {
            val ctor1 = clazz.constructors.first { ctor ->
                ctor.parameterCount == 1 &&
                        ctor.parameterTypes[0] == List::class.java
            }
            ctor1.newInstance(l) as T
        }
    }

    private fun normaliseSearchKeyPrefix(itemKeyPrefix: String): String {
        val searchPrefix = if (itemKeyPrefix.endsWith(".")) {
            itemKeyPrefix
        } else {
            "$itemKeyPrefix."
        }
        return searchPrefix
    }

    private fun getIndex(key: String): String {
        val parts = key.split(".")
        if (parts.size < 2) {
            throw IllegalArgumentException("Wrong key format '$key'")
        }
        return parts[1]
    }
}

///////////////////////////////////////////////// IMPL - membership

class MemberInfoImpl(
    override val memberCtx: KeyValueStore,
    override val mgmCtx: KeyValueStore
) : MemberInfo {
    override val groupId: String
        get() = mgmCtx.parse("GROUP_ID")
    override val name: String
        get() = memberCtx.parse("NAME")
    override val key: PublicKey
        get() = memberCtx.parse("KEY")
    override val allKeys: List<PublicKey>
        get() = memberCtx.parsePrimitiveList("ALL_KEYS") { c, v ->
            c.publicKeyDecoder.decodePublicKey(v)
        }
}

val MemberInfo.customValue: Long get() = memberCtx.parse("CUSTOM_KEY")
val MemberInfo.timestamp: Instant get() = mgmCtx.parse("TIMESTAMP")
val MemberInfo.nullableCustomValue: Long? get() = memberCtx.parseOrNull("NULLABLE_CUSTOM_KEY")
val MemberInfo.optionalCustomValue: Long? get() = memberCtx.parseOrNull("OPTIONAL_CUSTOM_KEY")

//////
class Tests {
    @Test
    fun example() {
        val keyEncoding = mock<KeyEncodingService>()
        val stringValueConverter = StringValueConverterImpl(keyEncoding)
        val key5 = mock<PublicKey>()
        val key6 = mock<PublicKey>()
        val key7 = mock<PublicKey>()
        val now = Instant.now()
        whenever(
            keyEncoding.decodePublicKey("12345")
        ).thenReturn(key5)
        whenever(
            keyEncoding.decodePublicKey("12346")
        ).thenReturn(key6)
        whenever(
            keyEncoding.decodePublicKey("12347")
        ).thenReturn(key7)
        val memberInfo = MemberInfoImpl(
            memberCtx = KeyValueStoreImpl(
                sortedMapOf(
                    "NAME" to "Me",
                    "KEY" to "12345",
                    "CUSTOM_KEY" to "42",
                    "NULLABLE_CUSTOM_KEY" to null,
                    "ALL_KEYS.0" to "12346",
                    "ALL_KEYS.1" to "12347"
                ),
                stringValueConverter
            ),
            mgmCtx = KeyValueStoreImpl(
                sortedMapOf(
                    "GROUP_ID" to "First",
                    "TIMESTAMP" to now.toString()
                ),
                stringValueConverter
            )
        )
        assertNull(memberInfo.nullableCustomValue)
        assertNull(memberInfo.nullableCustomValue)
        assertNull(memberInfo.optionalCustomValue)
        assertNull(memberInfo.optionalCustomValue)
        assertEquals("Me", memberInfo.name)
        assertEquals("Me", memberInfo.name)
        assertEquals("First", memberInfo.groupId)
        assertEquals("First", memberInfo.groupId)
        assertEquals(42, memberInfo.customValue)
        assertEquals(42, memberInfo.customValue)
        assertSame(key5, memberInfo.key)
        assertSame(key5, memberInfo.key)
        assertEquals(now, memberInfo.timestamp)
        assertEquals(now, memberInfo.timestamp)
        var allKeys = memberInfo.allKeys
        assertEquals(2, allKeys.size)
        assertSame(key6, allKeys[0])
        assertSame(key7, allKeys[1])
        allKeys = memberInfo.allKeys
        assertEquals(2, allKeys.size)
        assertSame(key6, allKeys[0])
        assertSame(key7, allKeys[1])
    }
}