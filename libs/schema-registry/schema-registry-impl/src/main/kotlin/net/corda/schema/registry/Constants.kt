package net.corda.schema.registry

import net.corda.data.Magic

val MAGIC = Magic("corda".toByteArray() + byteArrayOf(1, 0, 0))
