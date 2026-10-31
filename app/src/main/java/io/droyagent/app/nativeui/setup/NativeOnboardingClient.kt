package io.droyagent.app.nativeui.setup

import android.content.Context
import io.droyagent.app.NativeSetupHost
import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import io.droyagent.app.nativeui.data.NativeLocalTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface NativeOnboardingRepository {
    suspend fun route(): NativeSetupRoute
    suspend fun setStep(step: Int): NativeSetupRoute
    suspend fun acknowledge(key: String, value: Boolean): NativeSetupRoute
    suspend fun status(): NativeSetupLive
    suspend fun confirmIdentity(profile: NativeUserProfile): NativeSetupLive
    suspend fun complete(): NativeSetupLive
}

internal interface NativeSetupDraftStore {
    fun read(): NativeSetupIdentityDraft?
    fun write(value: NativeSetupIdentityDraft)
    fun clear()
}

internal class NativeOnboardingClient(private val context: Context) : NativeOnboardingRepository {
    private val transport = NativeLocalTransport()
    override suspend fun route() = withContext(Dispatchers.IO) { NativeSetupJson.route(NativeSetupHost.routeState(context)) }
    override suspend fun setStep(step: Int) = withContext(Dispatchers.IO) { NativeSetupJson.route(NativeSetupHost.setStep(context, step)) }
    override suspend fun acknowledge(key: String, value: Boolean) = withContext(Dispatchers.IO) { NativeSetupJson.route(NativeSetupHost.acknowledge(context, key, value)) }
    override suspend fun status() = withContext(Dispatchers.Default) { NativeSetupJson.live(transport.json("/droy/setup/native/status")) }
    override suspend fun confirmIdentity(profile: NativeUserProfile) = withContext(Dispatchers.Default) {
        NativeSetupJson.live(transport.json("/droy/setup/native/identity-confirm", "POST", NativeSetupJson.identityRequest(profile))) }
    override suspend fun complete() = withContext(Dispatchers.Default) { NativeSetupJson.live(transport.json("/droy/setup/native/complete", "POST", JSONObject())) }
}

internal class NativeSetupPreferences(context: Context) : NativeSetupDraftStore {
    private val preferences = context.getSharedPreferences("native_setup_draft", Context.MODE_PRIVATE)
    override fun read(): NativeSetupIdentityDraft? = preferences.getString("identity", null)?.let { NativeSetupJson.draft(JSONObject(it)) }
    override fun write(value: NativeSetupIdentityDraft) { preferences.edit().putString("identity", NativeSetupJson.draftValue(value).toString()).apply() }
    override fun clear() { preferences.edit().remove("identity").apply() }
}
