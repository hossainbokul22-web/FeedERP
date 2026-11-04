# কোড কনভেনশন (সব মডিউল এজেন্টের জন্য বাধ্যতামূলক)

প্রজেক্ট: `/home/claude` (Android, Kotlin, Compose, Hilt, Room+SQLCipher). প্যাকেজ রুট `com.feederp`
(`/home/claude/app/src/main/java/com/feederp/`). ডিজাইন: `/home/claude/docs/ERP_Design.md`। এখানে Android SDK/নেটওয়ার্ক নেই,
তাই কম্পাইল করা যাবে না। **অতএব প্রতিটি ফাইল খুব সাবধানে লিখুন**: ইমপোর্ট ঠিক রাখুন, Room/Hilt/Compose API সঠিকভাবে ব্যবহার করুন,
এবং লেখার পর নিজের ফাইলগুলো আবার পড়ে ইমপোর্ট, টাইপ, নাম ও ফাংশন সিগনেচার মিলিয়ে দেখুন।

## ফাইল মালিকানা
- শুধু নিজের মডিউলের ফাইল লিখুন/বদলান। `AppDatabase`, Hilt `DaoModule`, `MainActivity`, `AppNav` **আমি** তৈরি করব (আপনার `@Entity` ও `@Dao`
  স্ক্যান করে)। এগুলো লিখবেন না।
- ফাইল জায়গা: এন্টিটি+DAO `data/<module>/`, রিপোজিটরি ও লজিক `<module>/` (যেমন `com.feederp.purchase`), UI `ui/<module>/`।
- `data/Entities.kt` এ আছে: Role, ItemType, PartyType, MovementType, UserEntity, UnitEntity, ItemEntity, PartyEntity, WarehouseEntity,
  StockMovementEntity, AuditLogEntity। এগুলো মালিক = Agent A (শুধু সে বদলাবে)। অন্যরা প্রয়োজনে Agent A কে বাদ দিয়ে এগুলোর ফিল্ড বদলাবে না,
  বরং নিজের মডিউলে আলাদা এন্টিটি বানাবে।
- `core/` এর ফাইল (Core.kt, Permissions.kt, Session.kt, Security.kt, ModuleEntry.kt, DocNumbers.kt, AccountCodes.kt) পড়ে ব্যবহার করুন, বদলাবেন না।
  `ui/common/Components.kt` ও `ui/theme/Theme.kt` ও একই।

## এন্টিটি নিয়ম (সিঙ্কের জন্য অপরিহার্য — Sync এজেন্ট এগুলোর ওপর নির্ভর করে)
1. প্রাইমারি কী: `@PrimaryKey val id: String` (UUID, `newId()`)।
2. **পরিবর্তনযোগ্য এন্টিটি** (পণ্য, পার্টি, ফর্মুলা, ওয়ার্ক অর্ডার, ইনভয়েস ইত্যাদি): ফিল্ড `updatedAt: Long` (`stamper.now()`), `deviceId: String`,
   `deletedAt: Long? = null` (সফট ডিলিট; কখনও হার্ড ডিলিট নয়)। প্রতিটি সেভ/আপডেটে `updatedAt` ও `deviceId` নতুন বসান।
3. **অ্যাপেন্ড-অনলি এন্টিটি** (স্টক মুভমেন্ট, লেজার এন্ট্রি, অডিট লগ, রান লগ, কালেকশন): ফিল্ড `createdAt: Long`, `deviceId: String`, `createdBy: String`;
   কখনও আপডেট/ডিলিট নয়। ভুল ঠিক করতে রিভার্সাল এন্ট্রি যোগ করুন।
4. লাইন/চাইল্ড টেবিল (invoice lines ইত্যাদি) পরিবর্তনযোগ্য নিয়মে চলে (`updatedAt`, `deviceId`, `deletedAt`) এবং প্যারেন্টের id ধরে।
5. `@Entity(tableName = "snake_case")` সবসময় বলুন; টেবিল নাম প্রজেক্টজুড়ে অনন্য। কলামের নাম = Kotlin প্রপার্টি নাম।
   সমর্থিত টাইপ: String, Long, Int, Double, Boolean, nullable, এবং **enum** (Room 2.6 enum কে নামসহ TEXT হিসেবে রাখে, কনভার্টার লাগে না)।
   List/Map/ডেটা ক্লাস কলামে রাখবেন না; দরকার হলে আলাদা টেবিল বা JSON `String`।
6. টাকা: `Long` পয়সা (নাম `...Paisa`)। ওজন/পরিমাণ: `Double` গ্রামে (নাম `...Grams`)। তারিখ/সময়: `Long` epoch millis।
7. ইনডেক্স দিন যেসব কলামে ফিল্টার/জয়েন হয় (`@Index`)।
8. **ডিভাইস-লোকাল টেবিল** (সিঙ্ক হবে না) নাম রাখুন `local_` দিয়ে শুরু।

## DAO নিয়ম
- `@Dao interface XxxDao`; নাম প্রজেক্টে অনন্য। প্রতিটি `@Query` এর কলাম/টেবিল নাম এন্টিটির সাথে হুবহু মেলান।
- পাঠ্য মেথড `Flow<List<T>>` (UI-র জন্য) বা `suspend fun` (একবারের জন্য)। `@Insert(onConflict = OnConflictStrategy.REPLACE)` বা `@Upsert`।
- সফট ডিলিট ফিল্টার: `WHERE deletedAt IS NULL`।
- একাধিক টেবিলে একসাথে লেখা: রিপোজিটরিতে `db.withTransaction {}` লাগবে → `AppDatabase` ইনজেক্ট করুন
  (`com.feederp.data.AppDatabase`, আমি বানাব; `androidx.room.withTransaction` ব্যবহার করুন)।

## রিপোজিটরি ও ViewModel
- `@Singleton class XxxRepository @Inject constructor(daos..., private val stamper: Stamper, private val session: SessionManager, ...)`।
- লেখার আগে `session.require(Permission.X)`; `createdBy = session.userId`; `updatedAt = stamper.now()`; `deviceId = stamper.deviceId`।
- গুরুত্বপূর্ণ কাজে (অনুমোদন, মূল্য বদল, ডিলিট) `AuditRepository.log(...)` ডাকুন।
- `@HiltViewModel class XxxViewModel @Inject constructor(...) : ViewModel()`; স্টেট `StateFlow`; UI তে `collectAsStateWithLifecycle()`।
- ইউনিট-টেস্টযোগ্য বিশুদ্ধ লজিক (নিউট্রিয়েন্ট হিসাব, কস্টিং, পে-রোল, ডেপ্রিসিয়েশন, ডোজ যাচাই ইত্যাদি) আলাদা `object`/ক্লাসে রাখুন যার কোনো
  Android/Room নির্ভরতা নেই, এবং `app/src/test/java/com/feederp/...` এ JUnit4 টেস্ট লিখুন।

## ক্রস-মডিউল কন্ট্রাক্ট (হুবহু এই সিগনেচারে ব্যবহার/তৈরি করুন)
**Agent A তৈরি করবে** (`com.feederp.inventory`):
```kotlin
data class LotStock(val lotId: String, val batchNo: String, val expiryAt: Long?, val qtyGrams: Double)
@Singleton class StockRepository @Inject constructor(...) {
    suspend fun createLot(itemId: String, batchNo: String, mfgAt: Long?, expiryAt: Long?, moisturePct: Double? = null,
                          supplierId: String? = null, qcStatus: String = "PENDING"): String   // returns lotId
    suspend fun record(itemId: String, lotId: String?, warehouseId: String, type: MovementType,
                       qtyGrams: Double /* in=+, out=- */, unitCostPaisaPerKg: Long? = null,
                       refType: String? = null, refId: String? = null, note: String? = null): String // movementId
    fun observeStockGrams(itemId: String, warehouseId: String? = null): Flow<Double>
    suspend fun stockGrams(itemId: String, warehouseId: String? = null, lotId: String? = null): Double
    suspend fun lotsFefo(itemId: String, warehouseId: String): List<LotStock>   // কেবল QC পাস/অনির্ধারিত নয় এমন, মেয়াদ অনুযায়ী আগে-শেষ-আগে
    suspend fun weightedAvgCostPaisaPerKg(itemId: String): Long
    suspend fun setLotQcStatus(lotId: String, status: String /* PENDING|PASSED|REJECTED|HOLD */)
}
@Singleton class AuditRepository @Inject constructor(...) { suspend fun log(action: String, entity: String, entityId: String?, detail: String? = null) }
```
**Agent C তৈরি করবে** (`com.feederp.accounts`):
```kotlin
@Singleton class LedgerRepository @Inject constructor(...) {
    /** একটি ডাবল-এন্ট্রি ভাউচার; lines এর ডেবিট যোগফল = ক্রেডিট যোগফল না হলে IllegalArgumentException। */
    suspend fun post(voucherType: String, refType: String?, refId: String?, memo: String?, lines: List<LedgerLine>): String
    suspend fun partyBalancePaisa(partyId: String): Long   // + = আমরা পাব (receivable), - = আমরা দেব
}
data class LedgerLine(val accountCode: String, val debitPaisa: Long = 0, val creditPaisa: Long = 0, val partyId: String? = null)
```
সব মডিউল `AccountCodes` (core) ব্যবহার করবে।

**Agent B তৈরি করবে** (`com.feederp.formulation`) — অন্যরা ব্যবহার করতে পারে:
```kotlin
@Singleton class FormulaRepository @Inject constructor(...) { suspend fun approvedFormulas(): List<FormulaEntity>; suspend fun getApproved(id: String): FormulaEntity?; ... }
```
R&D ফর্মুলা প্রস্তাব বানায় `FormulaRepository.createDraftVersion(baseFormulaId: String, ...)` দিয়ে। (B ও R&D একই এজেন্ট।)

## মডিউল এন্ট্রি
প্রতিটি এজেন্ট প্রতিটি আলাদা ফিচার-এরিয়ার জন্য একটি `object XxxModule : ModuleEntry` (ফাইল `ui/<module>/XxxModule.kt`) দেবে:
`menu` তে `MenuItem(route, titleBn, titleEn, Permission, group)` তালিকা; `register(builder, nav)` এ `builder.composable("route") { ... }`
(`androidx.navigation.compose.composable`)। রুট স্ট্রিং অনন্য রাখুন, মডিউল প্রিফিক্স দিয়ে (`"purchase/list"`, `"hr/employees"`)।
আপনার সব `ModuleEntry` অবজেক্টের তালিকা শেষ উত্তরে দিন।

## UI
- Compose Material3; `ui/common/Components.kt` এর `ScreenScaffold`, `LabeledField`, `SearchableList`, `ConfirmDialog`, `KeyValueRow`, `formatDate` ব্যবহার করুন।
- সব লেবেল বাংলায় (সংখ্যা ইংরেজি ডিজিটে ঠিক আছে)। টাকা `Money.format`, ওজন `Weight.formatKg`।
- ফর্ম ভ্যালিডেশন দেখান (খালি, নেগেটিভ, ডোজ সীমা, স্টকের বেশি ইত্যাদি) এবং ত্রুটি ব্যবহারকারীকে বাংলায় জানান।
- ওষুধ/নিয়ন্ত্রিত আইটেম, QC হোল্ড, অনুমোদন ধাপ — ডিজাইন ডকুমেন্টের নিয়ম মানুন।

## শেষ উত্তরে জানান (সংক্ষেপে)
(১) কোন ফাইল তৈরি হলো, (২) `ModuleEntry` অবজেক্টের নাম, (৩) আপনি যে কন্ট্রাক্ট ব্যবহার করেছেন/তৈরি করেছেন, (৪) যা অসম্পূর্ণ বা অনিশ্চিত, (৫) কোন টেস্ট লিখেছেন।
সত্যি না হলে "সম্পূর্ণ" বা "কাজ করছে" বলবেন না — কম্পাইল করা যায়নি, তাই "লেখা হয়েছে, যাচাই হয়নি" বলুন।
