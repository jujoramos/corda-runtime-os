@file:JvmName("MemberInfoUtils")

package net.corda.membership.impl

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.osgi.service.component.annotations.ReferenceCardinality
import org.osgi.service.component.annotations.ReferencePolicyOption
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.util.SortedMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

interface KeyEncodingService : CustomStringConverter {
    fun decodePublicKey(encodedKey: String): PublicKey
    fun decodePublicKey(encodedKey: ByteArray): PublicKey
    fun encodeAsByteArray(publicKey: PublicKey): ByteArray
    fun encodeAsString(publicKey: PublicKey): String
    fun toSupportedPrivateKey(key: PrivateKey): PrivateKey
    fun toSupportedPublicKey(key: PublicKey): PublicKey
}

class KeyEncodingServiceImpl: KeyEncodingService {
    override fun decodePublicKey(encodedKey: String): PublicKey {
        val key = mock<PublicKey>()
        whenever(
            key.algorithm
        ).thenReturn(encodedKey)
        whenever(
            key.encoded
        ).thenReturn(encodedKey.toByteArray())
        return key
    }

    override fun decodePublicKey(encodedKey: ByteArray): PublicKey {
        TODO("Not yet implemented")
    }

    override fun encodeAsByteArray(publicKey: PublicKey): ByteArray {
        TODO("Not yet implemented")
    }

    override fun encodeAsString(publicKey: PublicKey): String {
        TODO("Not yet implemented")
    }

    override fun toSupportedPrivateKey(key: PrivateKey): PrivateKey {
        TODO("Not yet implemented")
    }

    override fun toSupportedPublicKey(key: PublicKey): PublicKey {
        TODO("Not yet implemented")
    }

    override val type: Class<*> = PublicKey::class.java

    override fun convert(context: CustomConversionContext): Any? {
        val value = context.value()
        if(value.isNullOrBlank()) {
            return null
        }
        return decodePublicKey(value)
    }
}
/////////////////////////////////////////// API - base

class CustomConversionContext(
    store: KeyValueStore,
    key: String,
    val converter: StringValueConverter,
) : ConversionContext(store, key)

interface CustomStringConverter {
    val type: Class<*>
    fun convert(context: CustomConversionContext): Any?
}

open class ConversionContext(
    val store: KeyValueStore,
    val key: String
) {
    fun value(): String? = store[key]
}

interface StringValueConverter {
    fun <T> convert(context: ConversionContext, clazz: Class<out T>): T?
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
        itemFactory: (store: KeyValueStore, converter: StringValueConverter, keys: List<String>) -> T
    ): List<T>
    fun <T> parsePrimitiveList(
        itemKeyPrefix: String, clazz: Class<out T>,
        itemFactory: (store: KeyValueStore, converter: StringValueConverter, key: String) -> T
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
    noinline itemFactory: (store: KeyValueStore, converter: StringValueConverter, keys: List<String>) -> T
): List<T> {
    return parseList(itemKeyPrefix, T::class.java, itemFactory)
}

inline fun <reified T> KeyValueStore.parsePrimitiveList(
    itemKeyPrefix: String,
    noinline itemFactory: (store: KeyValueStore, converter: StringValueConverter, key: String) -> T
): List<T> {
    return parsePrimitiveList(itemKeyPrefix, T::class.java, itemFactory)
}

/////////////////////////////////////////// API - membership

interface MemberContext: KeyValueStore
interface MGMContext: KeyValueStore

interface MemberInfo {
    val memberCtx: MemberContext
    val mgmCtx: MGMContext
    val groupId: String
    val name: String
    val key: PublicKey
    val allKeys: List<PublicKey>
}

///////////////////////////////////////////////// IMPL - base

@Component(service = [StringValueConverter::class])
open class StringValueConverterImpl(
    @Reference(
        service = CustomStringConverter::class,
        cardinality = ReferenceCardinality.OPTIONAL,
        policyOption = ReferencePolicyOption.GREEDY
    )
    val customConverters: List<CustomStringConverter>
) : StringValueConverter {
    private val converters = customConverters.associateBy { it.type }

    @Suppress("UNCHECKED_CAST")
    override fun <T> convert(context: ConversionContext, clazz: Class<out T>): T? {
            val converter = converters[clazz]
            return if(converter != null) {
                converter.convert(
                    CustomConversionContext(
                        context.store,
                        context.key,
                        this,
                    )
                ) as T
            } else {
                val value = context.value()
                return if (value == null) {
                    null
                } else {
                    when (clazz.kotlin) {
                        Int::class -> value.toInt() as T
                        Long::class -> value.toLong() as T
                        Short::class -> value.toShort() as T
                        Float::class -> value.toFloat() as T
                        Double::class -> value.toDouble() as T
                        String::class -> value as T
                        Instant::class -> Instant.parse(value) as T
                        else -> throw IllegalStateException("Unknown '${clazz.name}' Type")
                    }
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
        if (tmp != null) {
            return tmp.value as? T
                ?: throw IllegalStateException("The value for the key '$key' is null.")
        }
        val value = converter.convert(ConversionContext(this, key), clazz)
            ?: throw IllegalStateException("The '$key' was not found or the value is null.")
        materialised[key] = MaterialisedValue(value)
        return value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> parseOrNull(key: String, clazz: Class<out T>): T? {
        val tmp = materialised[key]
        if (tmp != null) {
            return tmp.value as T?
        }
        val value = converter.convert(ConversionContext(this, key), clazz)
        materialised[key] = MaterialisedValue(value)
        return value
    }

    override fun <T> parseList(itemKeyPrefix: String, clazz: Class<out T>): List<T> {
        return parseList(itemKeyPrefix, clazz) { _, _, keys ->
            defaultItemFactory(clazz, keys)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> parseList(
        itemKeyPrefix: String,
        clazz: Class<out T>,
        itemFactory: (store: KeyValueStore, converter: StringValueConverter, keys: List<String>) -> T
    ): List<T> {
        val searchPrefix = normaliseSearchKeyPrefix(itemKeyPrefix)
        val tmp = materialised[searchPrefix]
        if (tmp != null) {
            return tmp.value as? List<T>
                ?: throw IllegalStateException("The value for the key prefix '$searchPrefix' is null.")
        }
        val parsed = mutableListOf<T>()
        val currentGroupKeys = mutableListOf<String>()
        var lastIndex: String? = null
        entries.forEach {
            if (it.key.startsWith(searchPrefix)) {
                val itemIndex = getIndex(it.key)
                if (itemIndex != lastIndex) {
                    lastIndex = itemIndex
                    if (currentGroupKeys.isNotEmpty()) {
                        parsed.add(itemFactory(this, converter, currentGroupKeys))
                        currentGroupKeys.clear()
                    }
                }
                if (it.value != null) {
                    currentGroupKeys.add(it.key)
                }
            } else if (lastIndex != null) {
                return@forEach
            }
        }
        if (currentGroupKeys.isNotEmpty()) {
            parsed.add(itemFactory(this, converter, currentGroupKeys))
            currentGroupKeys.clear()
        }
        materialised[searchPrefix] = MaterialisedValue(parsed)
        return parsed
    }

    override fun <T> parsePrimitiveList(
        itemKeyPrefix: String,
        clazz: Class<out T>,
        itemFactory: (store: KeyValueStore, converter: StringValueConverter, key: String) -> T
    ): List<T> {
        return parseList(itemKeyPrefix, clazz) { store, converter, keys ->
            if (keys.size != 1) {
                throw IllegalStateException("Expected single item only.")
            }
            itemFactory(store, converter, keys[0])
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> defaultItemFactory(
        clazz: Class<out T>,
        keys: List<String>
    ): T {
        val ctor3 = clazz.constructors.firstOrNull { ctor ->
            ctor.parameterCount == 3 &&
                    ctor.parameterTypes[0] == KeyValueStore::class.java &&
                    ctor.parameterTypes[1] == StringValueConverter::class.java &&
                    ctor.parameterTypes[2] == List::class.java
        }
        return if (ctor3 != null) {
            ctor3.newInstance(this, converter, keys) as T
        } else {
            val ctor2 = clazz.constructors.first { ctor ->
                ctor.parameterCount == 2 &&
                        ctor.parameterTypes[0] == KeyValueStore::class.java &&
                        ctor.parameterTypes[1] == List::class.java
            }
            ctor2.newInstance(this, keys) as T
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

class MemberContextImpl(
    map: SortedMap<String, String?>,
    converter: StringValueConverter
) : KeyValueStoreImpl(map, converter), MemberContext

class MGMContextImpl(
    map: SortedMap<String, String?>,
    converter: StringValueConverter
) : KeyValueStoreImpl(map, converter), MGMContext

class MemberInfoImpl(
    override val memberCtx: MemberContext,
    override val mgmCtx: MGMContext
) : MemberInfo {
    override val groupId: String
        get() = mgmCtx.parse("GROUP_ID")
    override val name: String
        get() = memberCtx.parse("NAME")
    override val key: PublicKey
        get() = memberCtx.parse("KEY")
    override val allKeys: List<PublicKey>
        get() = memberCtx.parsePrimitiveList("ALL_KEYS") { store, converter, key ->
            converter.convert(ConversionContext(store, key), PublicKey::class.java)!!
        }
}

val MemberInfo.customValue: Long get() = memberCtx.parse("CUSTOM_KEY")
val MemberInfo.timestamp: Instant get() = mgmCtx.parse("TIMESTAMP")
val MemberInfo.nullableCustomValue: Long? get() = memberCtx.parseOrNull("NULLABLE_CUSTOM_KEY")
val MemberInfo.optionalCustomValue: Long? get() = memberCtx.parseOrNull("OPTIONAL_CUSTOM_KEY")

///////////////////////////////////////////////// API - application

class CordaX500Name(
    val commonName: String?,
    val organisationUnit: String?,
    val organisation: String,
    val locality: String,
    val state: String?,
    val country: String
) {
    companion object {
        fun parse(name: String): CordaX500Name = CordaX500Name(
            name,
            "",
            "",
            "",
            "",
            ""
        )
    }
}

interface Party {
    val name: CordaX500Name
    val owningKey: PublicKey
}

class PartyStringConverter : CustomStringConverter {
    override val type: Class<*> = Party::class.java

    override fun convert(context: CustomConversionContext): Any? {
        return when(context.store::class) {
            MemberContext::class -> {
                when(context.key) {
                    "PARTY" ->  PartyImpl(
                        CordaX500Name.parse(context.store.parse("NAME")),
                        context.store.parse("KEY")
                    )
                    "NOTARY_SERVICE_PARTY" -> PartyImpl(
                        CordaX500Name.parse(context.store.parse("NAME_NOTARY")),
                        context.store.parse("KEY_NOTARY")
                    )
                    else -> throw IllegalArgumentException("Unknown key '${context.key}'")
                }
            }
            else -> throw IllegalArgumentException("Unknown class '${context.store::class.java.name}'")
        }
    }
}

val MemberInfo.party: Party get() = memberCtx.parse("PARTY")

///////////////////////////////////////////////// IMPL - application

class PartyImpl(
    override val name: CordaX500Name,
    override val owningKey: PublicKey
) : Party

//////
class Tests {
    @Test
    fun example() {
        val stringValueConverter = StringValueConverterImpl(listOf(
            KeyEncodingServiceImpl()
        ))
        val now = Instant.now()
        val memberInfo = MemberInfoImpl(
            memberCtx = MemberContextImpl(
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
            mgmCtx = MGMContextImpl(
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
        assertSame("12345", memberInfo.key.algorithm)
        assertSame("12345", memberInfo.key.algorithm)
        assertEquals(now, memberInfo.timestamp)
        assertEquals(now, memberInfo.timestamp)
        var allKeys = memberInfo.allKeys
        assertEquals(2, allKeys.size)
        assertSame("12346", allKeys[0].algorithm)
        assertSame("12347", allKeys[1].algorithm)
        allKeys = memberInfo.allKeys
        assertEquals(2, allKeys.size)
        assertSame("12346", allKeys[0].algorithm)
        assertSame("12347", allKeys[1].algorithm)
    }
}