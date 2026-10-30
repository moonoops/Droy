package io.droyagent.app.nativeui.setup

import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NativeOnboardingCoordinatorTest {
    private val avatar = "/droy/companions/assets/user-default.png"
    private fun route(step: Int = 1, completed: Boolean = false, ready: Boolean = true,
        policy: NativeAuthorizationPolicy? = NativeAuthorizationPolicy.ON_FIRST_USE) = NativeSetupRoute(completed, !completed, step, 5, "usb", 4,
        NativeRuntimeState(ready, ready, !ready, "", "en"), policy, emptyMap())
    private fun live(route: NativeSetupRoute, model: Boolean = true, tools: Boolean = true,
        profile: NativeUserProfile = NativeUserProfile("Saved", avatar), confirmed: Boolean = true): NativeSetupLive {
        val facts = linkedMapOf("runtime" to route.environment.ready, "model" to model, "modelTools" to (model && tools),
            "identity" to confirmed, "permissionPolicy" to (route.permissionPolicy != null))
        return NativeSetupLive(route, facts.map { NativeSetupCheck(it.key, true, it.value) }, facts.values.all { it },
            profile.name.isNotEmpty(), confirmed, profile, "fixture", "fixture-model")
    }
    private class Drafts : NativeSetupDraftStore {
        var value: NativeSetupIdentityDraft? = null
        var writes = 0; var clears = 0
        override fun read() = value
        override fun write(value: NativeSetupIdentityDraft) { writes++; this.value = value }
        override fun clear() { clears++; value = null }
    }
    private inner class Repo(initial: NativeSetupRoute) : NativeOnboardingRepository {
        var current = initial
        var profile = NativeUserProfile("Saved", avatar)
        var confirmed = true
        var model = true; var tools = true
        var statuses = 0; var confirms = 0; var completions = 0
        val steps = mutableListOf<Int>()
        val acknowledgements = mutableListOf<Pair<String, Boolean>>()
        val events = mutableListOf<String>()
        val statusQueue = ArrayDeque<suspend () -> NativeSetupLive>()
        var routeAction: suspend () -> NativeSetupRoute = { current }
        var stepAction: suspend (Int) -> NativeSetupRoute = { current = current.copy(step = it); current }
        var confirmAction: suspend (NativeUserProfile) -> NativeSetupLive = { saved -> profile = saved; confirmed = true; snapshot() }
        var completeAction: suspend () -> NativeSetupLive = { current = current.copy(completed = true, required = false, step = 6); snapshot() }
        fun snapshot() = live(current, model, tools, profile, confirmed)
        override suspend fun route() = routeAction()
        override suspend fun setStep(step: Int): NativeSetupRoute { steps += step; events += "step:$step"; return stepAction(step) }
        override suspend fun acknowledge(key: String, value: Boolean): NativeSetupRoute { acknowledgements += key to value; return current }
        override suspend fun status(): NativeSetupLive { statuses++; return if (statusQueue.isEmpty()) snapshot() else statusQueue.removeFirst().invoke() }
        override suspend fun confirmIdentity(profile: NativeUserProfile): NativeSetupLive { confirms++; events += "confirm"; return confirmAction(profile) }
        override suspend fun complete(): NativeSetupLive { completions++; return completeAction() }
    }
    private suspend fun unusedSave(name: String, url: String?): NativeUserProfile = error("Unexpected identity write: $name $url")

    @Test fun anAlreadyCompletedInstallationDoesNotRestartChecksOrRewriteLegacyProgress() = runBlocking {
        val repo = Repo(route(step = 6, completed = true, ready = false))
        val drafts = Drafts()
        val model = NativeOnboardingCoordinator(repo, drafts, this)
        model.loadDraft(); model.refreshLocal().join(); model.refreshLive().join(); model.complete().join()
        assertEquals(false, model.state.value.requiresSetup)
        assertEquals(5, model.state.value.route!!.legacyStep)
        assertEquals("usb", model.state.value.route!!.source)
        assertEquals(0, repo.statuses); assertEquals(0, repo.completions); assertTrue(repo.steps.isEmpty())
        assertEquals(0, drafts.writes)
    }

    @Test fun unreadableRoutingRemainsUnknownInsteadOfLookingCompletedOrFirstRun() = runBlocking {
        val repo = Repo(route()).apply { routeAction = { throw NativeApiException("storage_error") } }
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join()
        assertNull(model.state.value.requiresSetup)
        assertNull(model.state.value.route)
        assertEquals("storage_error", model.state.value.localError)
    }

    @Test fun welcomeAcknowledgementAdvancesOnlyTheIndependentNativeStep() = runBlocking {
        val repo = Repo(route())
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.next(1, ::unusedSave).join()
        assertEquals(listOf("welcome" to true), repo.acknowledgements)
        assertEquals(2, model.state.value.route!!.step)
        assertEquals(5, model.state.value.route!!.legacyStep)
        assertEquals("usb", model.state.value.route!!.source)
        assertEquals(0, repo.statuses)
    }

    @Test fun aFailedStepWriteDoesNotInventProgress() = runBlocking {
        val repo = Repo(route()).apply { stepAction = { throw NativeApiException("storage_error") } }
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.next(1, ::unusedSave).join()
        assertEquals(1, model.state.value.route!!.step)
        assertEquals("storage_error", model.state.value.error)
    }

    @Test fun environmentMustActuallyBeReadyBeforeModelConfiguration() = runBlocking {
        val repo = Repo(route(step = 2, ready = false))
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.next(2, ::unusedSave).join()
        assertEquals(2, model.state.value.route!!.step)
        assertEquals("setup_environment_required", model.state.value.error)
        assertTrue(repo.steps.isEmpty())
    }

    @Test fun nextRechecksLiveModelToolsInsteadOfTrustingAnEarlierGreenReceipt() = runBlocking {
        val repo = Repo(route(step = 3))
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.refreshLive().join()
        assertTrue(model.state.value.live!!.passed("modelTools"))
        repo.tools = false
        model.next(3, ::unusedSave).join()
        assertEquals(3, model.state.value.route!!.step)
        assertEquals("setup_model_required", model.state.value.error)
        assertFalse(model.state.value.live!!.passed("modelTools"))
    }

    @Test fun identityConfirmationFollowsTheExactSuccessfulSaveReceipt() = runBlocking {
        val repo = Repo(route(step = 4))
        val drafts = Drafts()
        val model = NativeOnboardingCoordinator(repo, drafts, this)
        model.refreshLocal().join(); model.refreshLive().join(); model.name("  New user  ")
        model.next(4) { name, url -> repo.events += "save"; NativeUserProfile(name, requireNotNull(url)) }.join()
        assertEquals(listOf("save", "confirm", "step:5"), repo.events)
        assertEquals("New user", repo.profile.name)
        assertEquals(5, model.state.value.route!!.step)
        assertFalse(model.state.value.draft.edited)
        assertEquals("New user", drafts.value!!.name)
    }

    @Test fun aFailedProfileSaveCannotProduceAnIdentityReceiptOrAdvance() = runBlocking {
        val repo = Repo(route(step = 4))
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.refreshLive().join(); model.name("My draft")
        var saves = 0
        model.next(4) { _, _ -> saves++; throw NativeApiException("network_error") }.join()
        assertEquals(1, saves); assertEquals(0, repo.confirms)
        assertEquals(4, model.state.value.route!!.step)
        assertEquals("My draft", model.state.value.draft.name)
        assertFalse(model.state.value.liveFresh)
    }

    @Test fun aChangedLiveProfileCannotBeConfirmedAsTheSavedOne() = runBlocking {
        val repo = Repo(route(step = 4)).apply {
            confirmAction = { profile = NativeUserProfile("Another write", avatar); confirmed = false; snapshot() }
        }
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.refreshLive().join(); model.name("My draft")
        model.next(4) { name, url -> NativeUserProfile(name, requireNotNull(url)) }.join()
        assertEquals("NATIVE_SETUP_IDENTITY_CHANGED", model.state.value.error)
        assertEquals(4, model.state.value.route!!.step)
        assertEquals("My draft", model.state.value.draft.name)
    }

    @Test fun permissionProgressRequiresASelectedPolicyWithoutRequiringSystemGrants() = runBlocking {
        val repo = Repo(route(step = 5, policy = null))
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.next(5, ::unusedSave).join()
        assertEquals("setup_policy_required", model.state.value.error)
        assertEquals(5, model.state.value.route!!.step)
        repo.current = repo.current.copy(permissionPolicy = NativeAuthorizationPolicy.ON_FIRST_USE)
        model.refreshLocal().join(); model.next(5, ::unusedSave).join()
        assertEquals(6, model.state.value.route!!.step)
        assertTrue(repo.acknowledgements.isEmpty())
        assertEquals(0, repo.confirms)
    }

    @Test fun completionRechecksTheLiveFingerprintAndDoesNotSubmitAfterInvalidation() = runBlocking {
        val repo = Repo(route(step = 6))
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.refreshLive().join()
        repo.model = false
        model.complete().join()
        assertEquals(0, repo.completions)
        assertEquals(true, model.state.value.requiresSetup)
        assertFalse(model.state.value.live!!.canComplete)
        assertEquals("NATIVE_SETUP_NOT_READY", model.state.value.error)
    }

    @Test fun aLostCompletionReceiptIsResolvedByAReadWithoutRepeatingCompletion() = runBlocking {
        val repo = Repo(route(step = 6)).apply {
            completeAction = { current = current.copy(completed = true, required = false); throw NativeApiException("network_error") }
        }
        val drafts = Drafts().apply { value = NativeSetupIdentityDraft("Draft", avatar, true) }
        val model = NativeOnboardingCoordinator(repo, drafts, this)
        model.loadDraft(); model.refreshLocal().join(); model.complete().join()
        assertEquals(1, repo.completions)
        assertEquals(false, model.state.value.requiresSetup)
        assertNull(model.state.value.error)
        assertEquals(1, drafts.clears)
    }

    @Test fun unknownCompletionAndFailedRefreshNeverLookLikeSuccess() = runBlocking {
        val repo = Repo(route(step = 6)).apply {
            completeAction = { throw NativeApiException("network_error") }
            statusQueue.add { snapshot() }; statusQueue.add { throw NativeApiException("NATIVE_SETUP_STATE_UNAVAILABLE") }
        }
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.complete().join()
        assertEquals(1, repo.completions)
        assertEquals(true, model.state.value.requiresSetup)
        assertFalse(model.state.value.liveFresh)
        assertEquals("setup_result_unconfirmed", model.state.value.error)
    }

    @Test fun lateStatusCannotRewindACommittedStepChange() = runBlocking {
        val repo = Repo(route(step = 3))
        val old = CompletableDeferred<NativeSetupLive>()
        repo.statusQueue.add { old.await() }
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join()
        val reading = model.refreshLive(); yield()
        model.next(3, ::unusedSave).join()
        old.complete(live(route(step = 3))); reading.join()
        assertEquals(4, model.state.value.route!!.step)
    }

    @Test fun editedNonSecretDraftSurvivesARefreshAndANewCoordinator() = runBlocking {
        val drafts = Drafts().apply { value = NativeSetupIdentityDraft("Unsaved", avatar, true) }
        val repo = Repo(route(step = 4))
        val first = NativeOnboardingCoordinator(repo, drafts, this)
        first.loadDraft(); first.refreshLocal().join(); first.refreshLive().join()
        assertEquals("Unsaved", first.state.value.draft.name)
        first.name("Updated draft")
        val recreated = NativeOnboardingCoordinator(repo, drafts, this)
        recreated.loadDraft(); recreated.refreshLocal().join(); recreated.refreshLive().join()
        assertEquals("Updated draft", recreated.state.value.draft.name)
        assertTrue(recreated.state.value.draft.edited)
    }

    @Test fun duplicateNextClicksCannotSaveOrConfirmTheIdentityTwice() = runBlocking {
        val repo = Repo(route(step = 4))
        val receipt = CompletableDeferred<NativeUserProfile>()
        var saves = 0
        val save: suspend (String, String?) -> NativeUserProfile = { _, _ -> saves++; receipt.await() }
        val model = NativeOnboardingCoordinator(repo, Drafts(), this)
        model.refreshLocal().join(); model.refreshLive().join()
        val first = model.next(4, save); val second = model.next(4, save); yield()
        assertEquals(1, saves)
        receipt.complete(NativeUserProfile("Saved", avatar)); first.join(); second.join()
        assertEquals(1, repo.confirms)
        assertEquals(listOf(5), repo.steps)
    }
}
