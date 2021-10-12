package net.corda.membership.impl;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.PublicKey;
import java.time.Instant;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class JavaTests {
    @Test
    public void example() {
        var keyEncoding = Mockito.mock(KeyEncodingService.class);
        var stringValueConverter = new StringValueConverterImpl(keyEncoding);
        var key = Mockito.mock(PublicKey.class);
        var now = Instant.now();
        Mockito.when(
                keyEncoding.decodePublicKey("12345")
        ).thenReturn(key);
        var memberCtx = new TreeMap<String, String>();
        memberCtx.put("NAME", "Me");
        memberCtx.put("KEY", "12345");
        memberCtx.put("CUSTOM_KEY", "42");
        memberCtx.put("JAVA_CUSTOM", "34.56");
        var mgmCtx = new TreeMap<String, String>();
        mgmCtx.put("GROUP_ID", "First");
        mgmCtx.put("TIMESTAMP", now.toString());
        var memberInfo = new MemberInfoImpl(
                new KeyValueStoreImpl(
                        memberCtx,
                        stringValueConverter
                ),
                new KeyValueStoreImpl(
                        mgmCtx,
                        stringValueConverter
                )
        );
        assertEquals("Me", memberInfo.getName());
        assertEquals("First", memberInfo.getGroupId());
        assertEquals(42, MemberInfoUtils.getCustomValue(memberInfo));
        assertSame(key, memberInfo.getKey());
        assertEquals(now, MemberInfoUtils.getTimestamp(memberInfo));
        assertEquals(34.56, MemberInfoJavaExtensions.getJavaCustom(memberInfo));
    }
}
