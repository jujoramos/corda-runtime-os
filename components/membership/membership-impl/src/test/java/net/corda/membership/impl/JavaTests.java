package net.corda.membership.impl;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class JavaTests {
    @Test
    public void example() {
        var customConverters = new ArrayList<CustomStringConverter>();
        customConverters.add(new KeyEncodingServiceImpl());
        var stringValueConverter = new StringValueConverterImpl(customConverters);
        var now = Instant.now();
        var memberCtx = new TreeMap<String, String>();
        memberCtx.put("NAME", "Me");
        memberCtx.put("KEY", "12345");
        memberCtx.put("CUSTOM_KEY", "42");
        memberCtx.put("JAVA_CUSTOM", "34.56");
        var mgmCtx = new TreeMap<String, String>();
        mgmCtx.put("GROUP_ID", "First");
        mgmCtx.put("TIMESTAMP", now.toString());
        var memberInfo = new MemberInfoImpl(
                new MemberContextImpl(
                        memberCtx,
                        stringValueConverter
                ),
                new MGMContextImpl(
                        mgmCtx,
                        stringValueConverter
                )
        );
        assertEquals("Me", memberInfo.getName());
        assertEquals("First", memberInfo.getGroupId());
        assertEquals(42, MemberInfoUtils.getCustomValue(memberInfo));
        assertSame("12345", memberInfo.getKey().getAlgorithm());
        assertEquals(now, MemberInfoUtils.getTimestamp(memberInfo));
        assertEquals(34.56, MemberInfoJavaExtensions.getJavaCustom(memberInfo));
    }
}
