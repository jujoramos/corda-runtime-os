package net.corda.libs.permissions.manager.impl

import net.corda.data.permissions.management.PermissionManagementRequest
import net.corda.data.permissions.management.PermissionManagementResponse
import net.corda.libs.permissions.cache.PermissionCache
import net.corda.libs.permissions.manager.PermissionRoleManager
import net.corda.messaging.api.publisher.RPCSender

class PermissionRoleManagerImpl(
    private val rpcSender: RPCSender<PermissionManagementRequest, PermissionManagementResponse>,
    private val permissionCache: PermissionCache
) : PermissionRoleManager