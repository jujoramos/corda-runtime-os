package net.corda.membership.impl;

public class MemberInfoJavaExtensions {
    static public Double getJavaCustom(MemberInfo memberInfo) {
        return memberInfo.getMemberCtx().parse("JAVA_CUSTOM", Double.class);
    }
}
