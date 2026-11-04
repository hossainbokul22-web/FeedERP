package com.feederp.inventory

import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AuditLogDao
import com.feederp.data.AuditLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** গুরুত্বপূর্ণ কাজের অডিট লগ (অ্যাপেন্ড-অনলি)। লগইনের আগের ঘটনায় ইউজার "system"/"anonymous" ধরা হয়। */
@Singleton
class AuditRepository @Inject constructor(
    private val dao: AuditLogDao,
    private val stamper: Stamper,
    private val session: SessionManager,
) {
    suspend fun log(action: String, entity: String, entityId: String?, detail: String? = null) {
        dao.insert(
            AuditLogEntity(
                id = newId(),
                userId = session.current.value?.userId ?: "anonymous",
                action = action,
                entity = entity,
                entityId = entityId,
                detail = detail,
                createdAt = stamper.now(),
                deviceId = stamper.deviceId,
            )
        )
    }

    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>> = dao.observeRecent(limit)
}
