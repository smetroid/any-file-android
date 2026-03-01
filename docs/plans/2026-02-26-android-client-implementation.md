# any-file Android Client Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a native Android client for any-file that supports full bi-directional file synchronization using the any-sync P2P infrastructure.

**Architecture:** Pure Kotlin implementation of any-sync protocols (CoordinatorClient, FilenodeClient) over HTTP/gRPC, with Jetpack Compose UI and Room database storage. Uses WorkManager for background sync.

**Tech Stack:** Kotlin, Jetpack Compose, Room, WorkManager, OkHttp, gRPC, Blake3, Ed25519, Hilt DI, Android 8+ (API 26+)

---

## Phase 1: Foundation (Project Setup)

### Task 1: Create Android Project Structure

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (project level)
- Create: `app/proguard-rules.pro`

**Step 1: Create project manifest**

```xml
<!-- app/src/main/AndroidManifest.xml -->
<?xml xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.anyproto.anyfile">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:name=".AnyFileApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AnyFile"
        android:dataExtractionRules="@xml/data_extraction_rules">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AnyFile">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- WorkManager initialization -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            android:initOrder="last">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="com.anyproto.anyfile.di.WorkManagerInitializer" />
        </provider>
    </application>
</manifest>
```

**Step 2: Create app build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("dagger.hilt.android.plugin")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.anyproto.anyfile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anyproto.anyfile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.0"
    }

    namespace = "com.anyproto.anyfile"
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // Compose
    implementation(platform("androidx.compose.ui:ui:1.5.0"))
    implementation(platform("androidx.compose.material3:material3:1.1.0"))
    implementation("androidx.activity:activity-compose:1.7.2")

    // Room
    implementation("androidx.room:room-runtime:2.6.0")
    implementation("androidx.room:room-ktx:2.6.0")
    ksp("androidx.room:room-compiler:2.6.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // gRPC
    implementation("io.grpc:grpc-okhttp:1.56.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.24.0")

    // Crypto
    implementation("network.bytefiddler:crypt:0.1.0")
    implementation("com.github.blake3:blake3:0.9.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-android-compiler:2.48")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.5")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:runner:1.5.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.5.0")
    androidTestImplementation("androidx.compose.material3:material3:1.1.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.48")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.48")
}
```

**Step 3: Run build to verify setup**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL (may have warnings initially)

**Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml settings.gradle.kts build.gradle.kts
git commit -m "feat(android): initialize Android project structure"
```

### Task 2: Create Room Database Entities

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/database/entity/Space.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/database/entity/SyncedFile.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/database/entity/Peer.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/database/AnyfileDatabase.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/database/dao/SpaceDao.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/database/dao/SyncedFileDao.kt`

**Step 1: Create Space entity**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/entity/Space.kt
package com.anyproto.anyfile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anyproto.anyfile.data.database.dao.SpaceDao
import java.util.Date

@Entity
data class Space(
    @PrimaryKey
    val spaceId: String,
    val name: String,
    val spaceKey: ByteArray,
    val createdAt: Date,
    val lastSyncAt: Date?,
    val syncStatus: SyncStatus = SyncStatus.IDLE
)
```

**Step 2: Create SyncedFile entity**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/entity/SyncedFile.kt
package com.anyproto.anyfile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity
data class SyncedFile(
    @PrimaryKey
    val cid: String,
    val spaceId: String,
    val filePath: String,
    val size: Long,
    val version: Int,
    val syncStatus: SyncStatus,
    val modifiedAt: Date,
    val checksum: String // blake3 hash
)
```

**Step 3: Create Peer entity**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/entity/Peer.kt
package com.anyproto.anyfile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity
data class Peer(
    @PrimaryKey
    val peerId: String,
    val addresses: List<String>,
    val types: List<String>,
    val lastSeen: Date
)
```

**Step 4: Create type definitions**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/model/SyncStatus.kt
package com.anyproto.anyfile.data.database.model

enum class SyncStatus {
    IDLE, SYNCING, ERROR, CONFLICT
}
```

**Step 5: Create database and DAOs**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/AnyfileDatabase.kt
package com.anyproto.anyfile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao

@Database(
    entities = [Space::class, SyncedFile::class, Peer::class],
    version = 1,
    exportSchema = false
)
abstract class AnyfileDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun syncedFileDao(): SyncedFileDao
}
```

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/dao/SpaceDao.kt
package com.anyproto.anyfile.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM space ORDER BY createdAt DESC")
    fun getAllSpaces(): Flow<List<Space>>

    @Query("SELECT * FROM space WHERE spaceId = :spaceId")
    suspend fun getSpaceById(spaceId: String): Space?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpace(space: Space)

    @Update
    suspend fun updateSpace(space: Space): Int

    @Delete
    suspend fun deleteSpace(space: Space): Int

    @Query("UPDATE space SET lastSyncAt = :lastSyncAt WHERE spaceId = :spaceId")
    suspend fun updateLastSyncTime(spaceId: String, lastSyncAt: Date): Int
}
```

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/database/dao/SyncedFileDao.kt
package com.anyproto.anyfile.data.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedFileDao {
    @Query("SELECT * FROM syncedfile WHERE spaceId = :spaceId ORDER BY filePath")
    fun getFilesBySpace(spaceId: String): Flow<List<SyncedFile>>

    @Query("SELECT * FROM syncedfile WHERE cid = :cid")
    suspend fun getFileByCid(cid: String): SyncedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: SyncedFile)

    @Update
    suspend fun updateFile(file: SyncedFile): Int

    @Query("UPDATE syncedfile SET syncStatus = :status WHERE cid = :cid")
    suspend fun updateSyncStatus(cid: String, status: SyncStatus): Int
}
```

**Step 6: Run build to verify compilation**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/data/
git commit -m "feat(android): add Room database entities and DAOs"
```

### Task 3: Create Proto Buffer Definitions

**Files:**
- Create: `app/src/main/proto/coordinator.proto`
- Create: `app/src/main/proto/filenode.proto`
- Create: `app/build.gradle.kts` (update for protobuf)

**Step 1: Create coordinator proto**

```protobuf
// app/src/main/proto/coordinator.proto
syntax = "proto3";

package anysync;

option java_multiple_files = true;
option java_package = "com.anyproto.anyfile.protos";

service CoordinatorService {
  rpc RegisterPeer(RegisterPeerRequest) returns (RegisterPeerResponse);
  rpc CreateSpace(CreateSpaceRequest) returns (CreateSpaceResponse);
  rpc SignSpace(SignSpaceRequest) returns (SignSpaceResponse);
  rpc NetworkConfiguration(NetworkConfigRequest) returns (NetworkConfigResponse);
}

message RegisterPeerRequest {
  string peer_id = 1;
  string network_id = 2;
  repeated string addresses = 3;
  repeated string types = 4;
}

message RegisterPeerResponse {
  bool success = 1;
  string message = 2;
}

message CreateSpaceRequest {
  string space_id = 1;
  string space_type = 2;
  bytes space_header = 3;
  string payload = 4;
  bool force_request = 5;
}

message CreateSpaceResponse {
  bytes space_receipt_payload = 1;
  bytes signature = 2;
}

message SignSpaceRequest {
  string space_id = 1;
  bytes space_header = 2;
  bool force_request = 3;
}

message SignSpaceResponse {
  bytes space_receipt_payload = 1;
  bytes signature = 2;
}

message NetworkConfigRequest {}

message NetworkConfigResponse {
  string network_id = 1;
  repeated Node nodes = 2;
}

message Node {
  string peer_id = 1;
  repeated string addresses = 2;
  repeated string types = 3;
}
```

**Step 2: Create filenode proto**

```protobuf
// app/src/main/proto/filenode.proto
syntax = "proto3";

package anysync;

option java_multiple_files = true;
option java_package = "com.anyproto.anyfile.protos";

service FilenodeService {
  rpc BlockPush(BlockPushRequest) returns (BlockPushResponse);
  rpc BlockGet(BlockGetRequest) returns (BlockGetResponse);
  rpc FilesList(FilesListRequest) returns (FilesListResponse);
  rpc FileGet(FileGetRequest) returns (FileGetResponse);
  rpc SpaceInfo(SpaceInfoRequest) returns (SpaceInfoResponse);
}

message BlockPushRequest {
  string space_id = 1;
  string file_id = 2;
  bytes cid = 3;
  bytes data = 4;
}

message BlockPushResponse {
  bool success = 1;
}

message BlockGetRequest {
  string space_id = 1;
  bytes cid = 2;
}

message BlockGetResponse {
  bytes data = 1;
}

message FilesListRequest {
  string space_id = 1;
}

message FilesListResponse {
  repeated string file_ids = 1;
}

message FileGetRequest {
  string space_id = 1;
  string file_id = 2;
}

message FileGetResponse {
  string cid = 1;
  int64 size = 2;
  int32 version = 3;
  repeated string cids = 4;
}

message SpaceInfoRequest {
  string space_id = 1;
}

message SpaceInfoResponse {
  int64 total_usage_bytes = 1;
  int64 limit_bytes = 2;
  int32 files_count = 3;
}
```

**Step 3: Update build.gradle.kts for protobuf**

Add to the android block in app/build.gradle.kts:

```kotlin
plugins {
    id("com.google.protobuf")
}

dependencies {
    implementation("com.google.protobuf:protobuf-kotlin:3.24.0")
}
```

**Step 4: Run build to generate proto classes**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL, proto classes generated in `app/build/generated/source/proto/`

**Step 5: Commit**

```bash
git add app/src/main/proto/ app/build.gradle.kts
git commit -m "feat(android): add protobuf definitions for any-sync"
```

### Task 4: Create Dependency Injection Setup

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/di/AnyfileApplication.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/di/NetworkModule.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/di/DatabaseModule.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/di/SyncModule.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/di/WorkManagerInitializer.kt`

**Step 1: Create application class**

```kotlin
// app/src/main/java/com/anyproto/anyfile/di/AnyfileApplication.kt
package com.anyproto.anyfile.di

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AnyfileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
```

**Step 2: Create network module**

```kotlin
// app/src/main/java/com/anyproto/anyfile/di/NetworkModule.kt
package com.anyproto.anyfile.di

import com.anyproto.anyfile.data.network.CoordinatorClient
import com.anyproto.anyfile.data.network.FilenodeClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(Singleton::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/grpc")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideCoordinatorClient(httpClient: OkHttpClient): CoordinatorClient {
        return CoordinatorClient(httpClient)
    }

    @Provides
    @Singleton
    fun provideFilenodeClient(httpClient: OkHttpClient): FilenodeClient {
        return FilenodeClient(httpClient)
    }
}
```

**Step 3: Create database module**

```kotlin
// app/src/main/java/com/anyproto/anyfile/di/DatabaseModule.kt
package com.anyproto.anyfile.di

import android.content.Context
import androidx.room.Room
import com.anyproto.anyfile.data.database.AnyfileDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(Singleton::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AnyfileDatabase {
        return Room.databaseBuilder(
            context,
            AnyfileDatabase::class.java,
            "anyfile.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }
}
```

**Step 4: Create sync module**

```kotlin
// app/src/main/java/com/anyproto/anyfile/di/SyncModule.kt
package com.anyproto.anyfile.di

import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import dagger.Module
import dagger.Provides
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Singleton

@Module
@InstallIn(Singleton::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncOrchestrator(): SyncOrchestrator {
        return SyncOrchestrator()
    }
}
```

**Step 5: Create WorkManager initializer**

```kotlin
// app/src/main/java/com/anyproto/anyfile/di/WorkManagerInitializer.kt
package com.anyproto.anyfile.di

import android.content.Context
import androidx.hilt.work.WorkManager
import androidx.startup.Initializer
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import com.anyproto.anyfile.worker.SyncWorker
import dagger.hilt.android.EntryPoint
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class WorkManagerInitializer @Inject constructor(
    private val context: Context
) : Initializer() {

    override fun create(context: Context) {
        // Schedule periodic sync
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(false)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueue(syncRequest)
    }

    override fun dependencies(): List<Class<Initializer>> {
        return emptyList() // No dependencies
    }
}
```

**Step 6: Run build to verify DI setup**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/di/
git commit -m "feat(android): add Hilt dependency injection setup"
```

---

## Phase 2: Core P2P (any-sync Kotlin Layer)

### Task 5: Implement CoordinatorClient

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/CoordinatorClient.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/model/SpaceModels.kt`

**Step 1: Create data models**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/network/model/SpaceModels.kt
package com.anyproto.anyfile.data.network.model

data class RegisterPeerRequest(
    val peerId: String,
    val networkId: String,
    val addresses: List<String>,
    val types: List<String>
)

data class RegisterPeerResponse(
    val success: Boolean,
    val message: String
)

data class CreateSpaceRequest(
    val spaceId: String,
    val spaceType: String,
    val spaceHeader: ByteArray,
    val payload: ByteArray,
    val forceRequest: Boolean = false
)

data class SpaceInfo(
    val spaceId: String,
    val spaceKey: ByteArray,
    val spaceHeader: ByteArray,
    val spaceReceipt: SpaceReceipt?
)

data class SpaceReceipt(
    val spaceId: String,
    val peerId: String,
    val networkId: String,
    val signature: ByteArray,
    val validUntil: Long
)

data class NetworkConfiguration(
    val networkId: String,
    val nodes: List<NodeInfo>
)

data class NodeInfo(
    val peerId: String,
    val addresses: List<String>,
    val types: List<String>
)
```

**Step 2: Create CoordinatorClient implementation**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/network/CoordinatorClient.kt
package com.anyproto.anyfile.data.network

import android.content.SharedPreferences
import com.anyproto.anyfile.protos.*
import com.anyproto.anyfile.data.network.model.*
import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.IOException

class CoordinatorClient(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    private var coordinatorUrl: String = "http://127.0.0.1:1004" // Default

    fun setCoordinatorUrl(url: String) {
        coordinatorUrl = url
    }

    suspend fun registerPeer(request: RegisterPeerRequest): Result<RegisterPeerResponse> {
        return makeGrpcCall("$coordinatorUrl/anytype.coordinator.CoordinatorService/RegisterPeer") {
            setRequestBody(createProtoRequest(request))
        }
    }

    suspend fun createSpace(request: CreateSpaceRequest): Result<SpaceInfo> {
        val response = makeGrpcCall<CreateSpaceResponse>("$coordinatorUrl/anytype.coordinator.CoordinatorService/CreateSpace") {
            setRequestBody(createProtoRequest(request))
        }

        return response.map { resp ->
            SpaceInfo(
                spaceId = request.spaceId,
                spaceKey = extractSpaceKey(resp),
                spaceHeader = request.spaceHeader,
                spaceReceipt = parseSpaceReceipt(resp)
            )
        }
    }

    suspend fun signSpace(request: SignSpaceRequest): Result<SpaceReceipt> {
        val response = makeGrpcCall<SignSpaceResponse>("$coordinatorUrl/anytype.coordinator.CoordinatorService/SignSpace") {
            setRequestBody(createProtoRequest(request))
        }

        return response.map { resp -> parseSpaceReceipt(resp) }
    }

    suspend fun getNetworkConfiguration(): Result<NetworkConfiguration> {
        val response = makeGrpcCall<NetworkConfigResponse>("$coordinatorUrl/anytype.coordinator.CoordinatorService/NetworkConfiguration") {
            setRequestBody(createProtoRequest(NetworkConfigRequest()))
        }

        return response.map { resp ->
            NetworkConfiguration(
                networkId = resp.networkId,
                nodes = resp.nodesList.map { node ->
                    NodeInfo(
                        peerId = node.peerId,
                        addresses = node.addressesList.map { it.toString() },
                        types = node.typesList.map { it.toString() }
                    )
                }
            )
        }
    }

    private suspend fun <T> makeGrpcCall(url: String, requestBuilder: Request.Builder.() -> Unit): Result<T> {
        return suspendCancellable { continuation ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(requestBuilder)
                    .addHeader("Content-Type", "application/grpc")
                    .addHeader("grpc-accept-encoding", "gzip,identity")
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            try {
                                val responseBody = response.body?.string() ?: ""
                                // Parse protobuf response
                                continuation.resume(parseProtobufResponse<T>(responseBody))
                            } catch (e: Exception) {
                                continuation.resume(Result.failure(e))
                            }
                        } else {
                            continuation.resume(Result.failure(IOException("HTTP ${response.code}")))
                        }
                    }
                })
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    private fun createProtoRequest(request: Any): RequestBody {
        // Convert Kotlin object to protobuf and serialize
        // This is simplified - you'd use the actual proto classes
        val json = gson.toJson(request)
        return json.toRequestBody("application/json".toMediaType())
    }
}
```

**Step 3: Write unit test for CoordinatorClient**

Create: `app/src/test/java/com/anyproto/anyfile/data/network/CoordinatorClientTest.kt`

```kotlin
// app/src/test/java/com/anyproto/anyfile/data/network/CoordinatorClientTest.kt
package com.anyproto.anyfile.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CoordinatorClientTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var client: CoordinatorClient

    @Before
    fun setup() {
        mockServer = MockWebServer()
        client = CoordinatorClient(
            httpClient = OkHttpClient(),
            gson = Gson()
        )
        client.setCoordinatorUrl(mockServer.url.toString())
    }

    @Test
    fun `registerPeer should return success`() = runTest {
        val responseBody = """
        {"success": true, "message": "Registered"}
        """
        mockServer.enqueue(MockResponse().setBody(responseBody))

        val result = client.registerPeer(RegisterPeerRequest(
            peerId = "12D3KooWTest",
            networkId = "test-network",
            addresses = listOf("127.0.0.1:1234"),
            types = listOf("client")
        ))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.success)
    }

    @Test
    fun `getNetworkConfig should return nodes`() = runTest {
        val responseBody = """
        {
            "network_id": "test-network",
            "nodes": [{
                "peer_id": "12D3KooWTest",
                "addresses": ["127.0.0.1:1004"],
                "types": ["coordinator"]
            }]
        }
        """
        mockServer.enqueue(MockResponse().setBody(responseBody))

        val result = client.getNetworkConfiguration()

        assertTrue(result.isSuccess)
        assertEquals("test-network", result.getOrNull()!!.networkId)
    }
}
```

**Step 4: Run test to verify it fails initially**

Run: `./gradlew test`

Expected: FAIL (implementation not complete yet)

**Step 5: Complete CoordinatorClient implementation and verify tests pass**

Run: `./gradlew test`

Expected: TESTS PASSED

**Step 6: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/
git commit -m "feat(android): implement CoordinatorClient with tests"
```

### Task 6: Implement FilenodeClient

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/FilenodeClient.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/model/FilenodeModels.kt`

**Step 1: Create Filenode models**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/network/model/FilenodeModels.kt
package com.anyproto.anyfile.data.network.model

data class BlockPushRequest(
    val spaceId: String,
    val fileId: String,
    val cid: ByteArray,
    val data: ByteArray
)

data class BlockGetRequest(
    val spaceId: String,
    val cid: ByteArray
)

data class FilesListRequest(
    val spaceId: String
)

data class FileGetRequest(
    val spaceId: String,
    val fileId: String
)

data class FileMetadata(
    val cid: String,
    val size: Long,
    val version: Int,
    val cids: List<String>
)

data class SpaceUsage(
    val totalUsageBytes: Long,
    val limitBytes: Long,
    val filesCount: Int
)
```

**Step 2: Create FilenodeClient implementation**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/network/FilenodeClient.kt
package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.protos.*
import com.anyproto.anyfile.data.network.model.*
import okhttp3.*
import okio.ByteString
import java.io.IOException

class FilenodeClient(
    private val httpClient: OkHttpClient
) {
    private var filenodeUrl: String = "http://127.0.0.1:1005" // Default

    fun setFilenodeUrl(url: String) {
        filenodeUrl = url
    }

    suspend fun blockPush(spaceId: String, fileId: String, cid: ByteArray, data: ByteArray): Result<Unit> {
        val request = BlockPushRequest(spaceId, fileId, cid, data)
        return makeGrpcCall<Unit>("$filenodeUrl/anytype.filenode.FileNodeService/BlockPush") {
            setRequestBody(createProtoRequest(request))
        }
    }

    suspend fun blockGet(spaceId: String, cid: ByteArray): Result<ByteArray> {
        val request = BlockGetRequest(spaceId, cid)
        return makeGrpcCall<BlockGetResponse>("$filenodeUrl/anytype.filenode.FileNodeService/BlockGet") {
            setRequestBody(createProtoRequest(request))
        }
    }

    suspend fun filesList(spaceId: String): Result<List<String>> {
        val request = FilesListRequest(spaceId)
        return makeGrpcCall<FilesListResponse>("$filenodeUrl/anytype.filenode.FileNodeService/FilesList") {
            setRequestBody(createProtoRequest(request))
        }
    }

    suspend fun fileGet(spaceId: String, fileId: String): Result<FileMetadata> {
        val request = FileGetRequest(spaceId, fileId)
        return makeGrpcCall<FileGetResponse>("$filenodeUrl/anytype.filenode.FileNodeService/FileGet") {
            setRequestBody(createProtoRequest(request))
        }
    }

    suspend fun spaceInfo(spaceId: String): Result<SpaceUsage> {
        val request = SpaceInfoRequest(spaceId)
        return makeGrpcCall<SpaceInfoResponse>("$filenodeUrl/anytype.filenode.FileNodeService/SpaceInfo") {
            setRequestBody(createProtoRequest(request))
        }
    }
}
```

**Step 3: Write unit tests for FilenodeClient**

Create: `app/src/test/java/com/anyproto/anyfile/data/network/FilenodeClientTest.kt`

**Step 4: Run tests to verify they pass**

Run: `./gradlew test`

**Step 5: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/
git commit -m "feat(android): implement FilenodeClient with tests"
```

---

## Phase 3: Sync Engine

### Task 7: Implement Blake3 Hashing

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/domain/crypto/Blake3Hasher.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/domain/crypto/CryptoUtils.kt`

**Step 1: Implement Blake3 hasher**

```kotlin
// app/src/main/java/com/anyproto/anyfile/domain/crypto/Blake3Hasher.kt
package com.anyproto.anyfile.domain.crypto

import java.io.InputStream

class Blake3Hasher {
    private var hasher: Blake3? = null

    init {
        initialize()
    }

    private fun initialize() {
        // Initialize blake3 via JNI or pure Kotlin implementation
        // For now, use a placeholder - implement actual blake3
    }

    fun update(data: ByteArray) {
        hasher?.update(data)
    }

    fun finalize(): ByteArray {
        return hasher?.finalize() ?: byteArrayOf()
    }

    companion object {
        fun hash(data: ByteArray): ByteArray {
            val hasher = Blake3Hasher()
            hasher.update(data)
            return hasher.finalize()
        }

        fun hashStream(stream: InputStream): ByteArray {
            val hasher = Blake3Hasher()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it }).also { bytesRead = it } >= 0) {
                hasher.update(buffer, 0, bytesRead)
            }
            return hasher.finalize()
        }
    }
}

fun calculateCID(data: ByteArray): String {
    val hash = Blake3Hasher.hash(data)
    // CID is the blake3 hash encoded in base58 or similar
    return hash.toHex() // Simplified - use proper CID encoding
}
```

**Step 2: Implement crypto utilities**

```kotlin
// app/src/main/java/com/anyproto/anyfile/domain/crypto/CryptoUtils.kt
package com.anyproto.anyfile.domain.crypto

import java.security.*
import java.security.spec.KeySpec
import java.security.spec.PKCS8EncodedKeySpec

object CryptoUtils {
    fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        return keyPair.public.encoded to ByteArray() to keyPair.private.encoded to ByteArray()
    }

    fun derivePeerId(publicKey: ByteArray): String {
        // Implement libp2p peer ID derivation
        return deriveLibp2pPeerId(publicKey)
    }
}
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/domain/crypto/
git commit -m "feat(android): add Blake3 hashing and crypto utilities"
```

### Task 8: Implement SyncOrchestrator

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/domain/sync/SyncOrchestrator.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/domain/sync/SyncResult.kt`

**Step 1: Create sync result models**

```kotlin
// app/src/main/java/com/anyproto/anyfile/domain/sync/SyncResult.kt
package com.anyproto.anyfile.domain.sync

sealed class SyncResult {
    object Success : SyncResult()
    object Skipped : SyncResult()
    data class Conflict(val local: FileMetadata, val remote: FileMetadata) : SyncResult()
    data class Error(val error: Throwable) : SyncResult()
}
```

**Step 2: Implement SyncOrchestrator**

```kotlin
// app/src/main/java/com/anyproto/anyfile/domain/sync/SyncOrchestrator.kt
package com.anyproto.anyfile.domain.sync

class SyncOrchestrator(
    private val filenodeClient: FilenodeClient,
    private val fileDao: SyncedFileDao,
    private val conflictResolver: ConflictResolver
) {
    suspend fun uploadFile(spaceId: String, file: java.io.File): SyncResult {
        return try {
            // Calculate blake3 hash
            val fileHash = Blake3Hasher.hashStream(file.inputStream())
            val cid = calculateCID(fileHash)

            // Check if already uploaded
            val existing = fileDao.getFileByCid(cid)
            if (existing != null && existing.checksum == contentHash(file)) {
                return SyncResult.Skipped // Already uploaded
            }

            // Upload to filenode
            filenodeClient.blockPush(spaceId, file.name, cid.hexStringToByteArray(), file.readBytes())

            // Update database
            val syncedFile = SyncedFile(
                cid = cid,
                spaceId = spaceId,
                filePath = file.absolutePath,
                size = file.length(),
                version = 1,
                syncStatus = SyncStatus.SYNCING,
                modifiedAt = Date(),
                checksum = contentHash(file)
            )
            fileDao.insertFile(syncedFile)

            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }

    suspend fun downloadFile(spaceId: String, cid: String): SyncResult {
        return try {
            // Download from filenode
            val data = filenodeClient.blockGet(spaceId, cid.hexStringToByteArray())
                .getOrThrow()

            // TODO: Write to local storage
            // TODO: Update database

            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e)
        }
    }
}
```

**Step 3: Run tests**

Run: `./gradlew test`

**Step 4: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/domain/sync/
git commit -m "feat(android): implement SyncOrchestrator for upload/download"
```

### Task 9: Implement FileWatcher

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/domain/watcher/FileWatcher.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/domain/watcher/FileChangeEvent.kt`

**Step 1: Create file change event model**

```kotlin
// app/src/main/java/com/anyproto/anyfile/domain/watcher/FileChangeEvent.kt
package com.anyproto.anyfile.domain.watcher

sealed class FileChangeEvent {
    data class FileChanged(val path: String) : FileChangeEvent()
    data class FileDeleted(val path: String) : FileChangeEvent()
    data class FileCreated(val path: String) : FileChangeEvent()
}
```

**Step 2: Implement FileWatcher**

```kotlin
// app/src/main/java/com/anyproto/anyfile/domain/watcher/FileWatcher.kt
package com.anyproto.anyfile.domain.watcher

import android.os.FileObserver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

class FileWatcher(
    private val path: String
) {
    private val _events = Channel<FileChangeEvent>()
    private var fileObserver: FileObserver? = null

    fun startWatching(): Flow<FileChangeEvent> = _events.receiveAsFlow()

    fun start() {
        val file = File(path)
        fileObserver = object : FileObserver(path, MODIFY + DELETE + CREATE + MOVED_TO + MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                when (event) {
                    CREATE, MOVED_TO -> {
                        _events.trySend(FileChangeEvent.FileCreated(path ?: ""))
                    }
                    MODIFY -> {
                        _events.trySend(FileChangeEvent.FileChanged(path ?: ""))
                    }
                    DELETE, MOVED_FROM -> {
                        _events.trySend(FileChangeEvent.FileDeleted(path ?: ""))
                    }
                }
            }
        }
        fileObserver?.startWatching()
    }

    fun stop() {
        fileObserver?.stopWatching()
    }
}
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/domain/watcher/
git commit -m "feat(android): implement FileWatcher for detecting local changes"
```

### Task 10: Implement Background Sync Worker

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/worker/SyncWorker.kt`

**Step 1: Implement SyncWorker**

```kotlin
// app/src/main/java/com/anyproto/anyfile/worker/SyncWorker.kt
package com.anyproto.anyfile.worker

import android.content.Context
import androidx.hilt.work.Worker
import androidx.hilt.work.WorkerParameters
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import dagger.assisted.Assisted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Assisted
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val syncOrchestrator: SyncOrchestrator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val spaceId = inputData.getString("spaceId") ?: return Result.failure()

        return withContext(Dispatchers.IO) {
            try {
                // Perform sync
                syncOrchestrator.syncSpace(spaceId)
                Result.success()
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/worker/
git commit -m "feat(android): implement SyncWorker for background sync"
```

---

## Phase 4: UI (Jetpack Compose)

### Task 11: Create Main UI Screen

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/ui/main/MainViewModel.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/ui/main/MainScreen.kt`

**Step 1: Create ViewModel**

```kotlin
// app/src/main/java/com/anyproto/anyfile/ui/main/MainViewModel.kt
package com.anyproto.anyfile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.database.dao.SpaceDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val spaceDao: SpaceDao
) : ViewModel() {

    val spaces = spaceDao.getAllSpaces()
        .stateIn(viewModelScope)
        .map { spaces -> spaces.filter { it.syncStatus != SyncStatus.ERROR } }

    fun refreshSpaces() {
        viewModelScope.launch {
            // Trigger space refresh from coordinator
        }
    }
}
```

**Step 2: Create main screen**

```kotlin
// app/src/main/java/com/anyproto/anyfile/ui/main/MainScreen.kt
package com.anyproto.anyfile.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.Material3
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToSettings: () -> Unit
) {
    val spaces by viewModel.spaces.collectAsStateWithLifecycle(initial = emptyList())
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("any-file") },
                actions = {
                    IconButton(onClick = { viewModel.refreshSpaces() }) {
                        Icon(Icons.Default.Refresh)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* Add folder */ }
            ) {
                Icon(Icons.Default.Add)
            }
        }
    ) { padding ->
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(spaces) { space ->
                    SpaceItem(
                        space = space,
                        onClick = { /* Navigate to files */ }
                    )
                }
            }
        }
    }
}

@Composable
fun SpaceItem(space: Space, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = space.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Last synced: ${space.lastSyncAt ?: "Never"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/ui/main/
git commit -m "feat(android): add main UI screen with space list"
```

---

## Phase 5: Polish & Testing

### Task 12: Add Error Handling and Reporting

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/util/NetworkAwareClient.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/util/ErrorHandler.kt`

**Step 1: Implement network-aware client**

```kotlin
// app/src/main/java/com/anyproto/anyfile/util/NetworkAwareClient.kt
package com.anyproto.anyfile.util

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.withTimeout
import java.net.UnknownHostException

class NetworkAwareClient(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun <T> withNetworkRetry(
        operation: String,
        maxRetries: Int = 3,
        block: suspend () -> T
    ): Result<T> {
        if (!isNetworkAvailable()) {
            return Result.failure(UnknownHostException("No network available"))
        }

        var currentDelay = 1000L
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: Exception) {
                when (e) {
                    is UnknownHostException -> return Result.failure(e)
                    else -> {
                        delay(currentDelay)
                        currentDelay = (currentDelay * 2).coerceAtMost(30000)
                    }
                }
            }
        }

        return Result.failure(Exception("Max retries exceeded"))
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetworkInfo
        return network?.isConnected == true
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/anyproto/anyfile/util/
git commit -m "feat(android): add network-aware client with retry logic"
```

### Task 13: Add Unit and Integration Tests

**Step 1: Run all tests**

Run: `./gradlew test`

**Step 2: Run instrumentation tests**

Run: `./gradlew connectedCheck`

**Step 3: Fix any failing tests**

**Step 4: Commit**

```bash
git add .
git commit -m "test(android): fix failing tests and improve coverage"
```

---

## Verification Commands

After implementation:

### Build
```bash
./gradlew build
```

### Run Tests
```bash
./gradlew test
./gradlew connectedAndroidTest
```

### Generate APK
```bash
./gradlew assembleDebug
```

### Install on Device
```bash
./gradlew installDebug
```

---

## Success Criteria Checklist

- [ ] Can register peer with coordinator
- [ ] Can create/join sync spaces
- [ ] Can upload files and download on another device
- [ ] Background sync works reliably
- [ ] Handles conflicts gracefully
- [ ] Works on Android 8+ (API 26+)
- [ ] Unit test coverage > 70%
- [ ] Build produces working APK

---

## Notes

- **any-sync gRPC definitions**: These must match the Go definitions in any-sync
- **Ed25519 key generation**: Use network.bytefiddler:crypt or native implementation
- **Blake3 hashing**: Implement via JNI to native blake3 library or use Kotlin implementation
- **Peer IDs**: Follow libp2p format (base58 encoded)
- **Content addressing**: Files are addressed by blake3 hash, stored as chunks
- **Space receipts**: Used for authentication with filenode
- **Background execution**: Android has strict limits - WorkManager is essential
- **Storage**: Use scoped storage for app-specific data

---

## Next Steps

Once this plan is complete and verified, consider:
1. Adding QUIC transport for LAN-first P2P
2. Implementing selective sync (per-folder sync)
3. Adding file versioning and conflict UI
4. Performance optimization and battery profiling
